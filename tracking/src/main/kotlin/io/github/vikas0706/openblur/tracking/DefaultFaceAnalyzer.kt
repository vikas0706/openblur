package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.Detection
import io.github.vikas0706.openblur.core.FaceAnalyzer
import io.github.vikas0706.openblur.core.FaceDetector
import io.github.vikas0706.openblur.core.FaceEmbedder
import io.github.vikas0706.openblur.core.FaceTrack
import io.github.vikas0706.openblur.core.FrameCropper
import io.github.vikas0706.openblur.core.ThumbnailStore
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Drives one scan: picks the frames that run the detector, watches for scene cuts and feeds the tracker.
 * Runs after a possible cut wait, with their pictures, until two later runs show whether the cut is real.
 */
internal class DefaultFaceAnalyzer(
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val geometry: DetectorGeometry,
    options: AnalysisOptions,
    private val detector: FaceDetector,
    /** Null when people are not grouped. */
    embedder: FaceEmbedder?,
    thumbnails: ThumbnailStore,
) : FaceAnalyzer {
    override val inputWidth: Int get() = geometry.inputWidth
    override val inputHeight: Int get() = geometry.inputHeight
    override val contentWidth: Int get() = geometry.contentWidth
    override val contentHeight: Int get() = geometry.contentHeight

    private val cadence = DetectionCadence(options.maxDetectionsPerSecond)
    private val cuts = SceneCutDetector(geometry.contentWidth, geometry.contentHeight)
    private val embedding = EmbeddingState(embedder)
    private val tracker = FaceTracker(thumbnails, embedding)
    private val liveCrops = LiveCropSource(frameWidth, frameHeight, embedding)
    private val liveBatch = DetectionBatch(frameWidth, frameHeight)
    private val thumbPool = BytePool(Tuning.THUMBNAIL_SIZE * Tuning.THUMBNAIL_SIZE * 3)

    // Three at most in practice: a possible cut at run c is decided when run c + 2 arrives.
    private var queue = arrayOfNulls<QueuedRun>(4)
    private var queued = 0
    private var spare = arrayOfNulls<QueuedRun>(4)
    private var spares = 0

    private var runPtsUs = LongArray(1024)
    private var runCount = 0
    private var cutPtsUs = LongArray(8)
    private var cutEarliestUs = LongArray(8)
    private var cutCount = 0

    private var framesSeen = 0
    private var firstPtsUs = 0L
    private var lastPtsUs = 0L
    private var previousFrameRan = false

    /** Time of the first frame after the most recent detector run. */
    private var afterRunPtsUs = 0L

    @Volatile
    private var faces = 0
    private var closed = false
    private var finished = false
    private var delivered = false
    private var detectorClosed = false

    override val facesSoFar: Int get() = faces

    override fun wantsPixels(ptsUs: Long): Boolean {
        checkUsable()
        if (framesSeen > 0 && ptsUs <= lastPtsUs) return false
        return cadence.wouldRun(ptsUs)
    }

    override fun onFrame(ptsUs: Long, rgba: ByteBuffer?, cropper: FrameCropper?) {
        checkUsable()
        require(framesSeen == 0 || ptsUs > lastPtsUs) { "ptsUs must increase: got $ptsUs after $lastPtsUs" }
        val runs = cadence.wouldRun(ptsUs)
        var detections: List<Detection>? = null
        if (runs) {
            require(rgba != null && cropper != null) { "the frame at $ptsUs runs the detector and needs pixels and a cropper" }
            val needed = geometry.inputWidth.toLong() * geometry.inputHeight * 4
            require(rgba.remaining() >= needed) { "rgba holds ${rgba.remaining()} bytes, the detector input needs $needed" }
            // The picture for cut detection is read before the detector sees the buffer, and nothing
            // is recorded until the detector has returned, so a failed frame leaves the scan unchanged.
            cuts.ingest(runCount, rgba, geometry.inputWidth * 4)
            detections = detector.detect(rgba, geometry.contentWidth, geometry.contentHeight)
        }
        cadence.advance(ptsUs)
        if (framesSeen == 0) firstPtsUs = ptsUs
        framesSeen++
        lastPtsUs = ptsUs
        if (previousFrameRan) afterRunPtsUs = ptsUs
        previousFrameRan = runs
        if (detections != null) onRun(ptsUs, detections, cropper!!)
    }

    override fun finish(durationUs: Long): AnalysisResult {
        checkUsable()
        finished = true
        try {
            drain(runCount - 1, finishing = true, liveEntry = null)
            tracker.closeAll(trim = false)
            val rangeStartUs = if (framesSeen > 0) min(0L, firstPtsUs) else 0L
            // The last frame is on screen for some time after its timestamp, so the range ends after it
            // even when the container reports a shorter duration.
            val rangeEndUs = if (framesSeen > 0) max(durationUs, lastPtsUs + 1) else max(durationUs, 0L)
            val finisher = TrackFinisher(runPtsUs, cutPtsUs, cutEarliestUs, cutCount, rangeStartUs, rangeEndUs)
            val closedTracks = tracker.closed
            var longest = 0
            for (i in closedTracks.indices) longest = max(longest, closedTracks[i].size)
            finisher.reserve(longest)
            val built = ArrayList<BuiltTrack>(closedTracks.size)
            for (i in closedTracks.indices) built.add(finisher.build(closedTracks[i]))
            built.sortWith(START_ORDER)
            val grouping = if (embedding.embedder != null) {
                PeopleGrouper.group(built, embedding.vectorSize)
            } else {
                PeopleGrouper.ungrouped(built)
            }
            val tracks = ArrayList<FaceTrack>(built.size)
            for (i in built.indices) {
                val track = built[i]
                tracks.add(
                    FaceTrack(
                        id = i + 1,
                        personId = grouping.personOfTrack[i],
                        timesUs = track.timesUs,
                        boxes = track.boxes,
                        eyes = track.eyes,
                        sampleKinds = track.kinds,
                        thumbnailHandle = track.thumbHandle,
                        meanScore = track.meanScore,
                    ),
                )
            }
            val result = AnalysisResult(
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                durationUs = rangeEndUs,
                tracks = tracks,
                people = grouping.people,
                cutsUs = cutPtsUs.copyOf(cutCount),
                framesSeen = framesSeen,
                framesDetected = runCount,
            )
            faces = tracks.size
            delivered = true
            return result
        } finally {
            closeDetector()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            // Once a result was returned its thumbnails belong to the caller.
            if (!delivered) tracker.releaseAllThumbnails()
        } finally {
            closeDetector()
        }
    }

    private fun onRun(ptsUs: Long, detections: List<Detection>, cropper: FrameCropper) {
        val run = runCount
        if (run == runPtsUs.size) runPtsUs = runPtsUs.copyOf(run * 2)
        runPtsUs[run] = ptsUs
        runCount = run + 1
        val sinceCut = if (cutCount == 0) Long.MAX_VALUE else ptsUs - cutPtsUs[cutCount - 1]
        val candidate = sinceCut >= Tuning.MIN_SHOT_US && cuts.isCandidate(run)
        try {
            if (queued == 0 && !candidate) {
                liveBatch.load(detections)
                liveCrops.bind(cropper, liveBatch)
                tracker.processRun(run, ptsUs, liveBatch, liveCrops)
            } else {
                val entry = if (spares > 0) spare[--spares]!! else QueuedRun(frameWidth, frameHeight)
                entry.batch.load(detections)
                entry.run = run
                entry.ptsUs = ptsUs
                entry.earliestUs = afterRunPtsUs
                entry.cutCandidate = candidate
                if (queued == queue.size) queue = queue.copyOf(queued * 2)
                queue[queued++] = entry
                liveCrops.bind(cropper, entry.batch)
                drain(run, finishing = false, liveEntry = entry)
                // Still waiting on an earlier run: take its pictures now, the frame is gone after this call.
                if (queued > 0 && queue[queued - 1] === entry) {
                    entry.capture(liveCrops, embedding, thumbPool, tracker.cropBuffers)
                }
            }
        } finally {
            liveCrops.unbind()
        }
        faces = tracker.faceCount()
    }

    /** Feeds the tracker every waiting run whose possible cut can be decided, oldest first. */
    private fun drain(newestRun: Int, finishing: Boolean, liveEntry: QueuedRun?) {
        while (queued > 0) {
            val head = queue[0]!!
            if (head.cutCandidate) {
                val tooSoon = cutCount > 0 && head.ptsUs - cutPtsUs[cutCount - 1] < Tuning.MIN_SHOT_US
                if (!tooSoon) {
                    if (newestRun < head.run + 2 && !finishing) return
                    if (cuts.confirm(head.run, newestRun)) recordCut(head)
                }
                head.cutCandidate = false
            }
            tracker.processRun(head.run, head.ptsUs, head.batch, if (head === liveEntry) liveCrops else head)
            for (i in 1 until queued) queue[i - 1] = queue[i]
            queue[--queued] = null
            head.release(thumbPool, tracker.cropBuffers)
            if (spares == spare.size) spare = spare.copyOf(spares * 2)
            spare[spares++] = head
        }
    }

    private fun recordCut(at: QueuedRun) {
        if (cutCount == cutPtsUs.size) {
            cutPtsUs = cutPtsUs.copyOf(cutCount * 2)
            cutEarliestUs = cutEarliestUs.copyOf(cutCount * 2)
        }
        cutPtsUs[cutCount] = at.ptsUs
        cutEarliestUs[cutCount] = at.earliestUs
        cutCount++
        tracker.cut()
    }

    private fun closeDetector() {
        if (detectorClosed) return
        detectorClosed = true
        detector.close()
    }

    private fun checkUsable() {
        check(!closed) { "the analyzer is closed" }
        check(!finished) { "finish() was already called" }
    }

    private companion object {
        val START_ORDER = Comparator<BuiltTrack> { a, b ->
            val byStart = a.timesUs[0].compareTo(b.timesUs[0])
            if (byStart != 0) byStart else a.serial.compareTo(b.serial)
        }
    }
}
