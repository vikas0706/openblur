package io.github.vikas0706.openblur.analysis

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vikas0706.openblur.api.AnalysisEvent
import io.github.vikas0706.openblur.api.AnalysisException
import io.github.vikas0706.openblur.api.AnalysisException.Kind
import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.FaceAnalyzer
import io.github.vikas0706.openblur.core.FaceAnalyzerFactory
import io.github.vikas0706.openblur.core.FaceDetector
import io.github.vikas0706.openblur.core.FaceDetectorFactory
import io.github.vikas0706.openblur.core.FaceEmbedder
import io.github.vikas0706.openblur.core.FrameCropper
import io.github.vikas0706.openblur.core.ThumbnailStore
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs whole scans of a clip encoded on the device, with a recording stand-in for the face analyzer. Checks
 * what only the real pipeline can show: frame order, timestamps, orientation and the flow's life cycle.
 */
@RunWith(AndroidJUnit4::class)
class Media3VideoAnalyzerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val video = File(context.cacheDir, "analyzer-test.mp4")
    private val thumbnails = MemoryThumbnails()
    private val embedder = FakeEmbedder()
    private val embedderRequests = AtomicInteger()

    private val noDetectors = object : FaceDetectorFactory {
        override fun create(inputWidth: Int, inputHeight: Int): FaceDetector = error("The stand-in analyzer has no detector")
    }

    @Before
    fun setUp() {
        TestVideo.write(video)
    }

    @After
    fun tearDown() {
        video.delete()
    }

    private fun analyzer(
        analyzers: FaceAnalyzerFactory,
        embedderProvider: () -> FaceEmbedder? = {
            embedderRequests.incrementAndGet()
            embedder
        },
    ) = Media3VideoAnalyzer(context, noDetectors, embedderProvider, analyzers)

    private fun scan(
        analyzers: FaceAnalyzerFactory,
        uri: Uri = Uri.fromFile(video),
        options: AnalysisOptions = AnalysisOptions.STANDARD,
        rotationDegrees: Int = 0,
        subject: Media3VideoAnalyzer = analyzer(analyzers),
    ): List<AnalysisEvent> = runBlocking {
        withTimeout(SCAN_TIMEOUT_MS) {
            subject.analyze(uri, TestVideo.info(rotationDegrees), options, thumbnails).toList()
        }
    }

    private fun scanFailure(analyzers: FaceAnalyzerFactory, uri: Uri = Uri.fromFile(video)): AnalysisException {
        try {
            scan(analyzers, uri)
        } catch (e: AnalysisException) {
            return e
        }
        throw AssertionError("The scan should have failed")
    }

    private fun waitUntil(what: String, condition: () -> Boolean) = runBlocking {
        withTimeout(TEARDOWN_TIMEOUT_MS) {
            while (!condition()) delay(25)
        }
        assertTrue(what, condition())
    }

    /** Expected brightness of the four display quadrants, each as an allowed range of 8-bit grey. */
    private fun assertQuadrants(label: String, actual: IntArray, luma: IntArray) {
        // Video range 16..235 stretches to 0..255 on decode; the margin covers the encoder and colour matrix.
        val expected = luma.map { ((it - 16) * 255.0 / 219.0) }
        for (q in 0 until 4) {
            assertTrue(
                "$label quadrant $q is ${actual[q]}, expected about ${expected[q].toInt()}; all: ${actual.toList()}",
                abs(actual[q] - expected[q]) <= 24,
            )
        }
    }

    @Test
    fun everyFrameArrivesInOrderUprightAndTheFlowFinishesOnce() {
        val analyzers = RecordingAnalyzers()
        val events = scan(analyzers)
        val recorded = analyzers.only()

        val finished = events.filterIsInstance<AnalysisEvent.Finished>()
        assertEquals("exactly one Finished", 1, finished.size)
        assertTrue("Finished comes last", events.last() is AnalysisEvent.Finished)
        assertSame(recorded.result, finished[0].result)
        assertEquals(TestVideo.info().durationUs, recorded.finishedWithDurationUs)

        assertEquals(TestVideo.WIDTH, recorded.frameWidth)
        assertEquals(TestVideo.HEIGHT, recorded.frameHeight)
        assertEquals(TestVideo.FRAMES, recorded.times.size)
        for ((index, timeUs) in recorded.times.withIndex()) {
            assertTrue("frame $index at $timeUs us", abs(timeUs - index * TestVideo.FRAME_US) <= 1_000L)
            if (index > 0) assertTrue(timeUs > recorded.times[index - 1])
        }
        assertEquals("every call on the one analysis thread", 1, recorded.threads.size)
        assertEquals(emptyList<String>(), recorded.contractViolations)

        // Pixels were asked for on every second frame.
        assertEquals(TestVideo.FRAMES / 2, recorded.pixelFrames.size)
        for (frame in recorded.pixelFrames) {
            assertQuadrants("detector buffer", frame.buffer, TestVideo.LUMA)
            assertQuadrants("cropper", frame.crops, TestVideo.LUMA)
            assertEquals("padding below the picture must be opaque black", listOf(0, 0, 0, 255), frame.padding)
        }

        val progress = events.filterIsInstance<AnalysisEvent.Progress>()
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.all { it.fraction in 0f..1f })
        assertEquals(progress.map { it.fraction }.sorted(), progress.map { it.fraction })
        assertEquals(progress.map { it.framesSeen }.sorted(), progress.map { it.framesSeen })

        assertEquals(1, analyzers.creations.get())
        assertSame("the analyzer gets the scan's embedder", embedder, recorded.embedder)
        assertSame(thumbnails, recorded.thumbnails)
        assertEquals(1, embedderRequests.get())
        waitUntil("analyzer closed after the scan") { recorded.closeCount.get() == 1 }
        waitUntil("embedder closed after the scan") { embedder.closeCount.get() == 1 }
    }

    @Test
    fun rotatedVideoArrivesInDisplayOrientation() {
        TestVideo.write(video, rotationDegrees = 90)
        val analyzers = RecordingAnalyzers()
        scan(analyzers, rotationDegrees = 90)
        val recorded = analyzers.only()

        assertEquals("width and height swap for a sideways video", TestVideo.HEIGHT, recorded.frameWidth)
        assertEquals(TestVideo.WIDTH, recorded.frameHeight)
        assertEquals(TestVideo.FRAMES, recorded.times.size)
        // Turned a quarter clockwise for display: the encoded bottom-left ends up top-left, and so on round.
        val display = intArrayOf(TestVideo.LUMA[2], TestVideo.LUMA[0], TestVideo.LUMA[3], TestVideo.LUMA[1])
        for (frame in recorded.pixelFrames) {
            assertQuadrants("detector buffer", frame.buffer, display)
            assertQuadrants("cropper", frame.crops, display)
        }
    }

    @Test
    fun cancellingTheCollectorStopsTheScanWithoutFinishingAndTheNextScanWorks() {
        val slow = RecordingAnalyzers(frameDelayMs = 40)
        val subject = analyzer(slow)
        val seen = runBlocking {
            withTimeout(SCAN_TIMEOUT_MS) {
                subject.analyze(Uri.fromFile(video), TestVideo.info(), AnalysisOptions.STANDARD, thumbnails)
                    .first { it is AnalysisEvent.Progress && it.framesSeen >= 3 }
            }
        }
        assertTrue(seen is AnalysisEvent.Progress)

        // Started at once, while the cancelled scan may still be letting go of its decoder.
        val events = scan(slow, subject = subject)
        assertTrue(events.last() is AnalysisEvent.Finished)
        assertEquals(2, slow.all.size)
        assertEquals(TestVideo.FRAMES, slow.all[1].times.size)

        val cancelled = slow.all[0]
        waitUntil("analyzer closed after cancel") { cancelled.closeCount.get() == 1 }
        waitUntil("both scans closed their embedder") { embedder.closeCount.get() == 2 }
        assertNull("finish must not run for a cancelled scan", cancelled.finishedWithDurationUs)
        assertTrue("cancel came before the end: ${cancelled.times.size} frames", cancelled.times.size < TestVideo.FRAMES)
        assertEquals(emptyList<String>(), cancelled.contractViolations)
    }

    @Test
    fun missingFileFailsAsSourceUnreadable() {
        val analyzers = RecordingAnalyzers()
        val error = scanFailure(analyzers, Uri.fromFile(File(context.cacheDir, "no-such-video.mp4")))
        assertEquals(Kind.SOURCE_UNREADABLE, error.kind)
        assertEquals(0, analyzers.creations.get())
        waitUntil("embedder closed after the failure") { embedder.closeCount.get() == 1 }
    }

    @Test
    fun fileThatIsNotAVideoFailsAsSourceUnreadable() {
        val junk = File(context.cacheDir, "not-a-video.mp4").apply { writeText("This is not a video file. ".repeat(200)) }
        try {
            val error = scanFailure(RecordingAnalyzers(), Uri.fromFile(junk))
            assertEquals(Kind.SOURCE_UNREADABLE, error.kind)
        } finally {
            junk.delete()
        }
    }

    @Test
    fun analyzerExceptionFailsAsModelFailedAndClosesTheAnalyzer() {
        val failing = RecordingAnalyzers(failAtFrame = 3, failure = { IllegalStateException("model exploded") })
        val error = scanFailure(failing)
        assertEquals(Kind.MODEL_FAILED, error.kind)
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it.message == "model exploded" })
        val recorded = failing.only()
        waitUntil("analyzer closed after the failure") { recorded.closeCount.get() == 1 }
        assertNull(recorded.finishedWithDurationUs)
        assertEquals(3, recorded.times.size)
    }

    @Test
    fun outOfMemoryInTheAnalyzerIsReportedAsOutOfMemory() {
        val failing = RecordingAnalyzers(failAtFrame = 2, failure = { OutOfMemoryError("pretend heap exhaustion") })
        assertEquals(Kind.OUT_OF_MEMORY, scanFailure(failing).kind)
        waitUntil("analyzer closed after the failure") { failing.only().closeCount.get() == 1 }
    }

    @Test
    fun analyzerThatCannotBeCreatedFailsAsModelFailed() {
        val broken = object : FaceAnalyzerFactory {
            override fun create(
                frameWidth: Int,
                frameHeight: Int,
                options: AnalysisOptions,
                detectors: FaceDetectorFactory,
                embedder: FaceEmbedder?,
                thumbnails: ThumbnailStore,
            ): FaceAnalyzer = throw IllegalStateException("model file is damaged")
        }
        assertEquals(Kind.MODEL_FAILED, scanFailure(broken).kind)
        waitUntil("embedder closed after the failure") { embedder.closeCount.get() == 1 }
    }

    @Test
    fun embedderThatFailsToLoadTurnsGroupingOffInsteadOfFailingTheScan() {
        val analyzers = RecordingAnalyzers()
        val subject = analyzer(analyzers) { throw IllegalStateException("embedding model is missing") }
        val events = scan(analyzers, subject = subject)
        assertTrue(events.last() is AnalysisEvent.Finished)
        assertNull(analyzers.only().embedder)
    }

    @Test
    fun embedderIsNotLoadedWhenGroupingIsOff() {
        val analyzers = RecordingAnalyzers()
        val events = scan(analyzers, options = AnalysisOptions.STANDARD.copy(groupPeople = false))
        assertTrue(events.last() is AnalysisEvent.Finished)
        assertEquals(0, embedderRequests.get())
        assertNull(analyzers.only().embedder)
    }

    @Test
    fun flowIsColdAndEachCollectionIsItsOwnScan() {
        val analyzers = RecordingAnalyzers()
        val subject = analyzer(analyzers)
        val flow = subject.analyze(Uri.fromFile(video), TestVideo.info(), AnalysisOptions.STANDARD, thumbnails)
        Thread.sleep(300)
        assertEquals("nothing may start before the flow is collected", 0, analyzers.creations.get())
        assertEquals(0, embedderRequests.get())

        repeat(2) {
            val events = runBlocking { withTimeout(SCAN_TIMEOUT_MS) { flow.toList() } }
            assertTrue(events.last() is AnalysisEvent.Finished)
        }
        assertEquals(2, analyzers.creations.get())
        assertEquals(2, embedderRequests.get())
        assertTrue(analyzers.all.all { it.times.size == TestVideo.FRAMES })
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 120_000L
        const val TEARDOWN_TIMEOUT_MS = 20_000L
    }
}

private class MemoryThumbnails : ThumbnailStore {
    val puts = AtomicInteger()

    override fun put(width: Int, height: Int, rgb: ByteArray): Int = puts.getAndIncrement()

    override fun release(handle: Int) = Unit
}

private class FakeEmbedder : FaceEmbedder {
    val closeCount = AtomicInteger()
    override val cropSize = 112
    override val embeddingSize = 4

    override fun alignedCropCorners(landmarksPx: FloatArray, outCorners: FloatArray) = Unit

    override fun embed(rgb: ByteArray) = FloatArray(embeddingSize)

    override fun close() {
        closeCount.incrementAndGet()
    }
}

/** What one frame that carried pixels looked like to the analyzer. Quadrant order: TL, TR, BL, BR. */
private class PixelFrame(val buffer: IntArray, val crops: IntArray, val padding: List<Int>)

private class RecordingAnalyzers(
    private val pixelsEvery: Int = 2,
    private val frameDelayMs: Long = 0,
    private val failAtFrame: Int = -1,
    private val failure: () -> Throwable = { IllegalStateException("unused") },
) : FaceAnalyzerFactory {
    val creations = AtomicInteger()
    val all = CopyOnWriteArrayList<RecordingAnalyzer>()

    fun only(): RecordingAnalyzer {
        assertEquals("analyzers created", 1, all.size)
        return all[0]
    }

    override fun create(
        frameWidth: Int,
        frameHeight: Int,
        options: AnalysisOptions,
        detectors: FaceDetectorFactory,
        embedder: FaceEmbedder?,
        thumbnails: ThumbnailStore,
    ): FaceAnalyzer {
        creations.incrementAndGet()
        return RecordingAnalyzer(frameWidth, frameHeight, embedder, thumbnails, pixelsEvery, frameDelayMs, failAtFrame, failure)
            .also(all::add)
    }
}

private class RecordingAnalyzer(
    val frameWidth: Int,
    val frameHeight: Int,
    val embedder: FaceEmbedder?,
    val thumbnails: ThumbnailStore,
    private val pixelsEvery: Int,
    private val frameDelayMs: Long,
    private val failAtFrame: Int,
    private val failure: () -> Throwable,
) : FaceAnalyzer {
    // Half the frame size, padded up to the multiple of 32 a detector would need.
    override val contentWidth = frameWidth / 2
    override val contentHeight = frameHeight / 2
    override val inputWidth = (contentWidth + 31) / 32 * 32
    override val inputHeight = (contentHeight + 31) / 32 * 32

    val times = CopyOnWriteArrayList<Long>()
    val pixelFrames = CopyOnWriteArrayList<PixelFrame>()
    val threads = CopyOnWriteArrayList<String>()
    val contractViolations = CopyOnWriteArrayList<String>()
    val closeCount = AtomicInteger()

    @Volatile
    var finishedWithDurationUs: Long? = null

    @Volatile
    var result: AnalysisResult? = null

    override val facesSoFar: Int get() = times.size / 10

    override fun wantsPixels(ptsUs: Long): Boolean = times.size % pixelsEvery == 0

    override fun onFrame(ptsUs: Long, rgba: ByteBuffer?, cropper: FrameCropper?) {
        val thread = Thread.currentThread().name
        if (thread !in threads) threads.add(thread)
        if (closeCount.get() > 0) contractViolations.add("onFrame after close at $ptsUs")
        val index = times.size
        if (index == failAtFrame) throw failure()
        val wanted = index % pixelsEvery == 0
        if (wanted) {
            if (rgba == null || cropper == null) {
                contractViolations.add("no pixels for frame $index although they were asked for")
            } else {
                if (rgba.remaining() < inputWidth * inputHeight * 4) contractViolations.add("short buffer at frame $index")
                pixelFrames.add(PixelFrame(sampleBuffer(rgba), sampleCrops(cropper), paddingPixel(rgba)))
                if (index == 0) thumbnails.put(2, 2, ByteArray(12))
            }
        }
        times.add(ptsUs)
        if (frameDelayMs > 0) Thread.sleep(frameDelayMs)
    }

    /** Red channel at the centre of each quarter of the content area. The picture is grey, so red is enough. */
    private fun sampleBuffer(rgba: ByteBuffer): IntArray = IntArray(4) { q ->
        val x = contentWidth * (if (q % 2 == 0) 1 else 3) / 4
        val y = contentHeight * (if (q < 2) 1 else 3) / 4
        val at = rgba.position() + (y * inputWidth + x) * 4
        if (rgba.get(at + 3).toInt() and 0xFF != 255) contractViolations.add("picture pixel is not opaque")
        rgba.get(at).toInt() and 0xFF
    }

    private fun paddingPixel(rgba: ByteBuffer): List<Int> {
        val x = if (inputWidth > contentWidth) inputWidth - 1 else 0
        val y = if (inputHeight > contentHeight) inputHeight - 1 else 0
        if (x == 0 && y == 0) return listOf(0, 0, 0, 255)
        val at = rgba.position() + (y * inputWidth + x) * 4
        return List(4) { rgba.get(at + it).toInt() and 0xFF }
    }

    /** Mean red of an 8x8 crop taken from the middle of each quarter of the full-resolution frame. */
    private fun sampleCrops(cropper: FrameCropper): IntArray = IntArray(4) { q ->
        val left = if (q % 2 == 0) 0.125f else 0.625f
        val top = if (q < 2) 0.125f else 0.625f
        val out = ByteArray(8 * 8 * 3)
        cropper.crop(floatArrayOf(left, top, left + 0.25f, top, left, top + 0.25f), 8, 8, out)
        (0 until 64).sumOf { out[it * 3].toInt() and 0xFF } / 64
    }

    override fun finish(durationUs: Long): AnalysisResult {
        finishedWithDurationUs = durationUs
        return AnalysisResult(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            durationUs = durationUs,
            tracks = emptyList(),
            people = emptyList(),
            cutsUs = LongArray(0),
            framesSeen = times.size,
            framesDetected = pixelFrames.size,
        ).also { result = it }
    }

    override fun close() {
        closeCount.incrementAndGet()
    }
}
