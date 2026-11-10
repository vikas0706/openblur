package io.github.vikas0706.openblur.analysis

import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.FaceAnalyzer
import io.github.vikas0706.openblur.core.FaceAnalyzerFactory
import io.github.vikas0706.openblur.core.FaceDetectorFactory
import io.github.vikas0706.openblur.core.FaceEmbedder
import io.github.vikas0706.openblur.core.FrameCropper
import io.github.vikas0706.openblur.core.ThumbnailStore
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One decode pass's analyzer. It is fed on the GL thread and finished or closed on the scan thread, so every
 * use holds one lock, which also makes [close] wait for a detection that is in flight.
 */
internal class ScanSession(
    private val analyzers: FaceAnalyzerFactory,
    private val detectors: FaceDetectorFactory,
    private val embedder: FaceEmbedder?,
    private val options: AnalysisOptions,
    private val thumbnails: ThumbnailStore,
) {
    private val lock = Any()
    private var analyzer: FaceAnalyzer? = null
    private var pixels: ByteBuffer? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var lastPtsUs = Long.MIN_VALUE
    private var closed = false
    private val cropper = Cropper()

    /** Frames handed to the analyzer so far. */
    @Volatile
    var framesSeen = 0
        private set

    @Volatile
    var facesSoFar = 0
        private set

    /** Presentation time of the newest frame handed to the analyzer, or -1 before the first. */
    @Volatile
    var progressPtsUs = -1L
        private set

    /** True while the GL thread is inside the analyzer or this class's rendering. */
    @Volatile
    var busy = false
        private set

    /** Changes whenever the GL thread starts or ends work here; lets a watchdog tell slow from stuck. */
    @Volatile
    var activity = 0L
        private set

    /** The first thing that went wrong in the per-frame work, if anything did. */
    @Volatile
    var failure: ScanFailure? = null
        private set

    @Volatile
    private var abandoned = false

    /** GL thread. Creates the analyzer for the first frame size; a later size change keeps the same analyzer. */
    fun configure(width: Int, height: Int) {
        synchronized(lock) {
            if (closed || abandoned || failure != null) return
            frameWidth = width
            frameHeight = height
            if (analyzer != null) return
            working {
                guarded(FailureStage.MODEL) {
                    val created = analyzers.create(width, height, options, detectors, embedder, thumbnails)
                    analyzer = created
                    check(
                        created.contentWidth in 1..created.inputWidth &&
                            created.contentHeight in 1..created.inputHeight,
                    ) {
                        "Analyzer content ${created.contentWidth}x${created.contentHeight} does not fit its " +
                            "input ${created.inputWidth}x${created.inputHeight}"
                    }
                    pixels = ByteBuffer.allocateDirect(created.inputWidth * created.inputHeight * 4)
                        .order(ByteOrder.nativeOrder())
                }
            }
        }
    }

    /** GL thread, with the context that owns [texture] current. */
    fun frame(sampler: FrameSampler, texture: Int, ptsUs: Long) {
        if (abandoned) return
        synchronized(lock) {
            val analyzer = analyzer
            if (closed || abandoned || failure != null || analyzer == null) return
            // The analyzer requires increasing times; a repeated or reordered frame is dropped, not passed on.
            if (ptsUs <= lastPtsUs) return
            working {
                if (guarded(FailureStage.MODEL) { analyzer.wantsPixels(ptsUs) }) {
                    val buffer = checkNotNull(pixels)
                    guarded(FailureStage.GRAPHICS) {
                        sampler.readLetterbox(
                            texture,
                            frameWidth,
                            frameHeight,
                            analyzer.inputWidth,
                            analyzer.inputHeight,
                            analyzer.contentWidth,
                            analyzer.contentHeight,
                            buffer,
                        )
                    }
                    buffer.clear()
                    cropper.open(sampler, texture)
                    try {
                        guarded(FailureStage.MODEL) { analyzer.onFrame(ptsUs, buffer, cropper) }
                    } finally {
                        cropper.close()
                    }
                } else {
                    guarded(FailureStage.MODEL) { analyzer.onFrame(ptsUs, null, null) }
                }
                lastPtsUs = ptsUs
                progressPtsUs = ptsUs
                framesSeen++
                facesSoFar = guarded(FailureStage.MODEL) { analyzer.facesSoFar }
            }
        }
    }

    /** Makes the GL thread skip every further frame without waiting for the lock. */
    fun abandon() {
        abandoned = true
    }

    fun finish(durationUs: Long): AnalysisResult = synchronized(lock) {
        check(!closed) { "Scan session is closed" }
        checkNotNull(analyzer) { "No frame reached the analyzer" }.finish(durationUs)
    }

    /** Waits for the frame being analyzed, if any, then closes the analyzer. Safe to call twice. */
    fun close() {
        abandoned = true
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                analyzer?.close()
            } catch (e: Exception) {
                // Nothing useful can be done with a failed close; the scan's outcome is already decided.
            }
            analyzer = null
            pixels = null
        }
    }

    private inline fun working(block: () -> Unit) {
        busy = true
        activity++
        try {
            block()
        } finally {
            busy = false
            activity++
        }
    }

    private inline fun <T> guarded(stage: FailureStage, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        // First failure wins: a crop that failed in GL also surfaces as an analyzer exception.
        if (failure == null) failure = ScanFailure(stage, t)
        throw t
    }

    private inner class Cropper : FrameCropper {
        private var sampler: FrameSampler? = null
        private var texture = 0
        private var thread: Thread? = null

        fun open(sampler: FrameSampler, texture: Int) {
            this.sampler = sampler
            this.texture = texture
            thread = Thread.currentThread()
        }

        fun close() {
            sampler = null
            thread = null
        }

        override fun crop(corners: FloatArray, outWidth: Int, outHeight: Int, outRgb: ByteArray) {
            val sampler = checkNotNull(sampler) { "FrameCropper used after its onFrame call returned" }
            // GL contexts belong to one thread; anywhere else the calls would silently do nothing.
            check(Thread.currentThread() === thread) { "FrameCropper used off the analysis thread" }
            try {
                sampler.crop(texture, corners, outWidth, outHeight, outRgb)
            } catch (e: GlFailure) {
                if (failure == null) failure = ScanFailure(FailureStage.GRAPHICS, e)
                throw e
            }
        }
    }
}
