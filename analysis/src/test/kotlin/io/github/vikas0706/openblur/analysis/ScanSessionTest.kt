package io.github.vikas0706.openblur.analysis

import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.FaceAnalyzer
import io.github.vikas0706.openblur.core.FaceAnalyzerFactory
import io.github.vikas0706.openblur.core.FaceDetector
import io.github.vikas0706.openblur.core.FaceDetectorFactory
import io.github.vikas0706.openblur.core.FaceEmbedder
import io.github.vikas0706.openblur.core.FrameCropper
import io.github.vikas0706.openblur.core.ThumbnailStore
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the session's bookkeeping with an analyzer that never asks for pixels, so no GL is involved. The one
 * test that does ask for pixels relies on the JVM test stubs of GLES20 reporting an incomplete framebuffer.
 */
class ScanSessionTest {
    private val sampler = FrameSampler()
    private val thumbnails = object : ThumbnailStore {
        override fun put(width: Int, height: Int, rgb: ByteArray) = 0

        override fun release(handle: Int) = Unit
    }
    private val detectors = object : FaceDetectorFactory {
        override fun create(inputWidth: Int, inputHeight: Int): FaceDetector = error("unused")
    }

    private class StubAnalyzer(
        val frameWidth: Int,
        val frameHeight: Int,
        val embedder: FaceEmbedder?,
        override val contentWidth: Int = 320,
        override val contentHeight: Int = 180,
        private val pixels: Boolean = false,
    ) : FaceAnalyzer {
        override val inputWidth = 320
        override val inputHeight = 192
        val times = ArrayList<Long>()
        var onFrame: (Long) -> Unit = {}
        var closes = 0
        var finishedWith: Long? = null
        val result = AnalysisResult(frameWidth, frameHeight, 0L, emptyList(), emptyList(), LongArray(0), 0, 0)

        override fun wantsPixels(ptsUs: Long) = pixels

        override fun onFrame(ptsUs: Long, rgba: ByteBuffer?, cropper: FrameCropper?) {
            check(closes == 0) { "onFrame after close" }
            onFrame.invoke(ptsUs)
            times.add(ptsUs)
        }

        override val facesSoFar: Int get() = times.size * 2

        override fun finish(durationUs: Long): AnalysisResult {
            finishedWith = durationUs
            return result
        }

        override fun close() {
            closes++
        }
    }

    private class StubFactory(private val build: (Int, Int, FaceEmbedder?) -> FaceAnalyzer) : FaceAnalyzerFactory {
        var creations = 0
        var created: StubAnalyzer? = null

        override fun create(
            frameWidth: Int,
            frameHeight: Int,
            options: AnalysisOptions,
            detectors: FaceDetectorFactory,
            embedder: FaceEmbedder?,
            thumbnails: ThumbnailStore,
        ): FaceAnalyzer {
            creations++
            return build(frameWidth, frameHeight, embedder).also { created = it as? StubAnalyzer }
        }
    }

    private fun factory(pixels: Boolean = false, contentWidth: Int = 320) =
        StubFactory { width, height, embedder -> StubAnalyzer(width, height, embedder, contentWidth, pixels = pixels) }

    private fun session(factory: FaceAnalyzerFactory, embedder: FaceEmbedder? = null) =
        ScanSession(factory, detectors, embedder, AnalysisOptions.STANDARD, thumbnails)

    @Test
    fun analyzerIsCreatedOnceWithTheFirstFrameSize() {
        val factory = factory()
        val session = session(factory)
        session.configure(1080, 1920)
        session.configure(1920, 1080)
        assertEquals(1, factory.creations)
        assertEquals(1080, factory.created!!.frameWidth)
        assertEquals(1920, factory.created!!.frameHeight)
        assertNull(session.failure)
    }

    @Test
    fun framesReachTheAnalyzerAndTheCountersFollow() {
        val factory = factory()
        val session = session(factory)
        assertEquals(0, session.framesSeen)
        assertEquals(-1L, session.progressPtsUs)
        session.configure(640, 360)
        val before = session.activity
        for (pts in listOf(0L, 33_333L, 66_666L)) session.frame(sampler, 7, pts)

        assertEquals(listOf(0L, 33_333L, 66_666L), factory.created!!.times)
        assertEquals(3, session.framesSeen)
        assertEquals(6, session.facesSoFar)
        assertEquals(66_666L, session.progressPtsUs)
        assertFalse(session.busy)
        assertTrue(session.activity != before)
    }

    @Test
    fun repeatedAndEarlierTimestampsAreDropped() {
        val factory = factory()
        val session = session(factory)
        session.configure(640, 360)
        for (pts in listOf(100L, 100L, 50L, 200L, 199L, 201L)) session.frame(sampler, 7, pts)
        assertEquals(listOf(100L, 200L, 201L), factory.created!!.times)
        assertEquals(3, session.framesSeen)
        assertNull(session.failure)
    }

    @Test
    fun framesBeforeConfigureAreIgnored() {
        val session = session(factory())
        session.frame(sampler, 7, 0L)
        assertEquals(0, session.framesSeen)
        assertNull(session.failure)
    }

    @Test
    fun sessionReportsBusyWhileTheAnalyzerWorks() {
        val factory = factory()
        val session = session(factory)
        session.configure(640, 360)
        var busyInside = false
        factory.created!!.onFrame = { busyInside = session.busy }
        session.frame(sampler, 7, 0L)
        assertTrue(busyInside)
        assertFalse(session.busy)
    }

    @Test
    fun analyzerExceptionIsRecordedAsAModelFailureAndStopsTheFeed() {
        val factory = factory()
        val session = session(factory)
        session.configure(640, 360)
        val boom = IllegalStateException("inference failed")
        factory.created!!.onFrame = { if (it == 2L) throw boom }

        session.frame(sampler, 7, 1L)
        val thrown = assertThrows(IllegalStateException::class.java) { session.frame(sampler, 7, 2L) }
        assertSame(boom, thrown)
        assertEquals(FailureStage.MODEL, session.failure!!.stage)
        assertSame(boom, session.failure!!.cause)
        assertFalse(session.busy)

        session.frame(sampler, 7, 3L)
        assertEquals("nothing is fed after a failure", listOf(1L), factory.created!!.times)
        assertEquals(1, session.framesSeen)
    }

    @Test
    fun errorsAreRecordedToo() {
        val factory = factory()
        val session = session(factory)
        session.configure(640, 360)
        factory.created!!.onFrame = { throw OutOfMemoryError("pretend") }
        assertThrows(OutOfMemoryError::class.java) { session.frame(sampler, 7, 1L) }
        assertTrue(ScanErrors.isOutOfMemory(session.failure!!.cause))
    }

    @Test
    fun analyzerThatCannotBeCreatedIsAModelFailure() {
        val session = session(StubFactory { _, _, _ -> throw IllegalStateException("model file is damaged") })
        assertThrows(IllegalStateException::class.java) { session.configure(640, 360) }
        assertEquals(FailureStage.MODEL, session.failure!!.stage)
        session.frame(sampler, 7, 0L)
        assertEquals(0, session.framesSeen)
        session.close()
    }

    @Test
    fun analyzerWhoseContentDoesNotFitItsInputIsRejectedAndStillClosed() {
        val factory = factory(contentWidth = 321)
        val session = session(factory)
        assertThrows(IllegalStateException::class.java) { session.configure(640, 360) }
        assertEquals(FailureStage.MODEL, session.failure!!.stage)
        session.close()
        assertEquals(1, factory.created!!.closes)
    }

    @Test
    fun aRenderingFailureIsAGraphicsFailureAndTheAnalyzerNeverSeesTheFrame() {
        val factory = factory(pixels = true)
        val session = session(factory)
        session.configure(640, 360)
        assertThrows(GlFailure::class.java) { session.frame(sampler, 7, 0L) }
        assertEquals(FailureStage.GRAPHICS, session.failure!!.stage)
        assertEquals(emptyList<Long>(), factory.created!!.times)
        assertEquals(0, session.framesSeen)
    }

    @Test
    fun theEmbedderIsPassedThrough() {
        val embedder = object : FaceEmbedder {
            override val cropSize = 112
            override val embeddingSize = 128

            override fun alignedCropCorners(landmarksPx: FloatArray, outCorners: FloatArray) = Unit

            override fun embed(rgb: ByteArray) = FloatArray(embeddingSize)

            override fun close() = Unit
        }
        val factory = factory()
        session(factory, embedder).configure(640, 360)
        assertSame(embedder, factory.created!!.embedder)
    }

    @Test
    fun finishReturnsTheAnalyzersResult() {
        val factory = factory()
        val session = session(factory)
        session.configure(640, 360)
        session.frame(sampler, 7, 0L)
        assertSame(factory.created!!.result, session.finish(5_000_000L))
        assertEquals(5_000_000L, factory.created!!.finishedWith)
    }

    @Test
    fun finishWithoutAnAnalyzerOrAfterCloseThrows() {
        val unconfigured = session(factory())
        assertThrows(IllegalStateException::class.java) { unconfigured.finish(1L) }

        val factory = factory()
        val closed = session(factory)
        closed.configure(640, 360)
        closed.close()
        assertThrows(IllegalStateException::class.java) { closed.finish(1L) }
        assertNull(factory.created!!.finishedWith)
    }

    @Test
    fun closeClosesTheAnalyzerOnceAndNothingIsFedAfterwards() {
        val factory = factory()
        val session = session(factory)
        session.configure(640, 360)
        session.frame(sampler, 7, 0L)
        session.close()
        session.close()
        assertEquals(1, factory.created!!.closes)
        session.frame(sampler, 7, 1L)
        session.configure(640, 360)
        assertEquals(listOf(0L), factory.created!!.times)
        assertEquals(1, factory.creations)
    }

    @Test
    fun abandonSkipsFramesWithoutClosing() {
        val factory = factory()
        val session = session(factory)
        session.configure(640, 360)
        session.frame(sampler, 7, 0L)
        session.abandon()
        session.frame(sampler, 7, 1L)
        assertEquals(listOf(0L), factory.created!!.times)
        assertEquals(0, factory.created!!.closes)
    }

    @Test
    fun closeWaitsForTheFrameThatIsBeingAnalyzed() {
        val factory = factory()
        val session = session(factory)
        session.configure(640, 360)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        factory.created!!.onFrame = {
            inside.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        val feeder = thread { session.frame(sampler, 7, 0L) }
        assertTrue(inside.await(10, TimeUnit.SECONDS))

        val closed = CountDownLatch(1)
        val closer = thread {
            session.close()
            closed.countDown()
        }
        assertFalse("close must not return while a frame is in the analyzer", closed.await(300, TimeUnit.MILLISECONDS))
        assertEquals(0, factory.created!!.closes)

        release.countDown()
        feeder.join(10_000)
        closer.join(10_000)
        assertTrue(closed.await(1, TimeUnit.SECONDS))
        assertEquals(1, factory.created!!.closes)
        assertEquals("the frame in flight completed before the close", listOf(0L), factory.created!!.times)
    }
}
