package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.Detection
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerContractTest {
    @Test
    fun detectorInputSizes() {
        assertSizes(1920, 1080, AnalysisOptions.STANDARD, 640, 360, 640, 384)
        assertSizes(1080, 1920, AnalysisOptions.STANDARD, 360, 640, 384, 640)
        assertSizes(3840, 2160, AnalysisOptions.STANDARD, 640, 360, 640, 384)
        assertSizes(320, 240, AnalysisOptions.STANDARD, 320, 240, 320, 256)
        assertSizes(3840, 2160, AnalysisOptions.THOROUGH, 1280, 720, 1280, 736)
        assertSizes(1920, 1080, AnalysisOptions.THOROUGH, 1280, 720, 1280, 736)
        assertSizes(640, 480, AnalysisOptions.THOROUGH, 640, 480, 640, 480)
        assertSizes(4000, 3, AnalysisOptions.STANDARD, 640, 1, 640, 32)
        assertSizes(1, 1, AnalysisOptions.STANDARD, 1, 1, 32, 32)
    }

    private fun assertSizes(frameWidth: Int, frameHeight: Int, options: AnalysisOptions, cw: Int, ch: Int, iw: Int, ih: Int) {
        val detectors = ScriptedDetectors()
        val analyzer = DefaultFaceAnalyzerFactory().create(frameWidth, frameHeight, options, detectors, null, FakeThumbnails())
        val label = "${frameWidth}x$frameHeight at ${options.detectorLongSide}"
        assertEquals("$label content width", cw, analyzer.contentWidth)
        assertEquals("$label content height", ch, analyzer.contentHeight)
        assertEquals("$label input width", iw, analyzer.inputWidth)
        assertEquals("$label input height", ih, analyzer.inputHeight)
        assertEquals("$label detector width", iw, detectors.inputWidth)
        assertEquals("$label detector height", ih, detectors.inputHeight)
        analyzer.onFrame(0, ByteBuffer.allocateDirect(iw * ih * 4), FakeCropper(emptyList(), frameWidth, frameHeight, CropLog()))
        assertEquals("$label content width given to the detector", cw, detectors.lastContentWidth)
        assertEquals("$label content height given to the detector", ch, detectors.lastContentHeight)
        analyzer.close()
        assertEquals(1, detectors.closed)
    }

    @Test
    fun thirtyFramesPerSecondRunsTheDetectorOnEveryFrame() {
        val scan = Scan()
        for (i in 0 until 3000) scan.frame(at30(i), emptyList())
        assertEquals(3000, scan.detectors.calls)
        val result = scan.finish(at30(3000))
        assertEquals(3000, result.framesSeen)
        assertEquals(3000, result.framesDetected)
    }

    @Test
    fun ntscRateRunsTheDetectorOnEveryFrame() {
        val scan = Scan()
        for (i in 0 until 3000) scan.frame(i * 1_001_000_000L / 30_000, emptyList())
        assertEquals(3000, scan.detectors.calls)
    }

    @Test
    fun millisecondTimestampsAtThirtyFramesPerSecondRunEveryFrame() {
        val scan = Scan()
        for (i in 0 until 3000) scan.frame((i * 1000L / 30) * 1000, emptyList())
        assertEquals(3000, scan.detectors.calls)
    }

    @Test
    fun sixtyFramesPerSecondRunsTheDetectorOnEveryOtherFrame() {
        val scan = Scan()
        for (i in 0 until 3000) scan.frame(i * 1_000_000L / 60, emptyList())
        assertEquals(1500, scan.detectors.calls)
        for (k in scan.runTimes.indices) assertEquals("run $k", (2 * k) * 1_000_000L / 60, scan.runTimes[k])
    }

    @Test
    fun thoroughOptionsRunEveryFrameAtSixty() {
        val scan = Scan(options = AnalysisOptions.THOROUGH)
        for (i in 0 until 600) scan.frame(i * 1_000_000L / 60, emptyList())
        assertEquals(600, scan.detectors.calls)
    }

    @Test
    fun fastAndSlowRatesRespectTheLimit() {
        // The second run comes one frame early (three quarters of an interval is close enough), then every fourth.
        val at120 = Scan()
        for (i in 0 until 1200) at120.frame(i * 1_000_000L / 120, emptyList())
        assertEquals(301, at120.detectors.calls)
        assertEquals(listOf(0L, 25_000L, 58_333L, 91_666L), at120.runTimes.take(4))

        val at50 = Scan()
        for (i in 0 until 500) at50.frame(i * 1_000_000L / 50, emptyList())
        assertEquals(300, at50.detectors.calls)

        val at24 = Scan()
        for (i in 0 until 240) at24.frame(i * 1_000_000L / 24, emptyList())
        assertEquals(240, at24.detectors.calls)

        val capped = Scan(options = AnalysisOptions(maxDetectionsPerSecond = 5f))
        for (i in 0 until 300) capped.frame(at30(i), emptyList())
        assertEquals(51, capped.detectors.calls)
    }

    @Test
    fun variableFrameRateKeepsOneTrackAndStaysUnderTheLimit() {
        val scan = Scan()
        val random = java.util.Random(11)
        val start = 5_000L
        var pts = start
        for (i in 0 until 600) {
            scan.frame(pts, listOf(Face(1, 400f + i * 1.5f, 500f, 200f)))
            // Between a third and one and a half frame intervals, with a long stall now and then.
            pts += if (i % 97 == 96) 500_000L else 11_000L + random.nextInt(39_000)
        }
        val result = scan.finish(pts)

        assertTrue("first frame always runs", scan.runTimes[0] == start)
        for (window in 0 until scan.runTimes.size - 31) {
            val span = scan.runTimes[window + 31] - scan.runTimes[window]
            assertTrue("32 runs within $span us is more than 30 per second allows", span >= 1_000_000L)
        }
        assertTrue("most frames still run: ${scan.detectors.calls}", scan.detectors.calls > 400)
        assertEquals(1, result.tracks.size)
        val track = result.tracks[0]
        assertEquals(scan.runTimes, track.detectedTimes())
        assertEquals(result.framesDetected, scan.detectors.calls)
    }

    @Test
    fun wantsPixelsIsPureAndMatchesOnFrame() {
        val scan = Scan()
        scan.frame(0, emptyList())
        val pts = 1_000_000L / 60
        repeat(5) { assertFalse(scan.analyzer.wantsPixels(pts)) }
        scan.frame(pts, emptyList())
        repeat(5) { assertTrue(scan.analyzer.wantsPixels(2 * pts)) }
        assertFalse("a time that does not increase is never wanted", scan.analyzer.wantsPixels(pts))
        scan.frame(2 * pts, emptyList())
        assertEquals(2, scan.detectors.calls)
    }

    @Test
    fun timestampsMustIncrease() {
        val scan = Scan()
        scan.frame(1000, emptyList())
        assertThrows(IllegalArgumentException::class.java) { scan.frame(1000, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { scan.frame(999, emptyList()) }
        scan.frame(1001, emptyList())
    }

    @Test
    fun wantedFrameWithoutPixelsIsRejectedAndChangesNothing() {
        val scan = Scan()
        assertThrows(IllegalArgumentException::class.java) { scan.analyzer.onFrame(0, null, null) }
        val small = ByteBuffer.allocateDirect(16)
        assertThrows(IllegalArgumentException::class.java) {
            scan.analyzer.onFrame(0, small, FakeCropper(emptyList(), 1920, 1080, scan.crops))
        }
        scan.frame(0, listOf(Face(1, 500f, 500f, 200f)))
        scan.frame(at30(1), listOf(Face(1, 500f, 500f, 200f)))
        val result = scan.finish(at30(2))
        assertEquals(2, result.framesSeen)
        assertEquals(1, result.tracks.size)
    }

    @Test
    fun pixelsOnAFrameThatDoesNotNeedThemAreIgnored() {
        val scan = Scan()
        scan.frame(0, emptyList())
        val pts = 1_000_000L / 60
        assertFalse(scan.analyzer.wantsPixels(pts))
        scan.analyzer.onFrame(pts, Pixels.scene(0, 640, 384), FakeCropper(emptyList(), 1920, 1080, scan.crops))
        assertEquals(1, scan.detectors.calls)
    }

    @Test
    fun bufferPositionIsRespectedAndLeftUnchanged() {
        val scan = Scan()
        val padded = ByteBuffer.allocateDirect(640 * 384 * 4 + 100)
        padded.position(100)
        scan.analyzer.onFrame(0, padded, FakeCropper(emptyList(), 1920, 1080, scan.crops))
        assertEquals(100, padded.position())
        val heap = ByteBuffer.wrap(ByteArray(640 * 384 * 4 + 7), 7, 640 * 384 * 4)
        scan.analyzer.onFrame(at30(1), heap, FakeCropper(emptyList(), 1920, 1080, scan.crops))
        assertEquals(7, heap.position())
    }

    @Test
    fun closeWithoutFinishReleasesEveryThumbnailAndClosesTheDetector() {
        val scan = Scan()
        for (i in 0 until 90) {
            val faces = ArrayList<Face>()
            if (i < 20) faces += Face(1, 400f, 400f, 200f)
            if (i > 40) faces += Face(2, 1400f, 600f, 200f)
            scan.frame(at30(i), faces)
        }
        assertEquals("one finished and one live track hold thumbnails", 2, scan.thumbnails.live.size)
        scan.analyzer.close()
        assertEquals(0, scan.thumbnails.live.size)
        assertEquals(emptyList<String>(), scan.thumbnails.errors)
        assertEquals(1, scan.detectors.closed)
        scan.analyzer.close()
        assertEquals(1, scan.detectors.closed)
    }

    @Test
    fun callsAfterCloseThrow() {
        val scan = Scan()
        scan.frame(0, emptyList())
        scan.analyzer.close()
        assertThrows(IllegalStateException::class.java) { scan.analyzer.wantsPixels(at30(1)) }
        assertThrows(IllegalStateException::class.java) { scan.analyzer.onFrame(at30(1), null, null) }
        assertThrows(IllegalStateException::class.java) { scan.analyzer.finish(at30(2)) }
        assertEquals(640, scan.analyzer.inputWidth)
        assertEquals(0, scan.analyzer.facesSoFar)
    }

    @Test
    fun finishClosesTheDetectorAndTheResultKeepsItsThumbnails() {
        val scan = Scan()
        for (i in 0 until 30) scan.frame(at30(i), listOf(Face(1, 400f, 400f, 200f)))
        val result = scan.finish(at30(30))
        assertEquals(1, scan.detectors.closed)
        assertThrows(IllegalStateException::class.java) { scan.analyzer.finish(at30(30)) }
        assertThrows(IllegalStateException::class.java) { scan.analyzer.onFrame(at30(31), null, null) }
        scan.analyzer.close()
        assertEquals(1, scan.detectors.closed)
        assertEquals(setOf(result.tracks[0].thumbnailHandle), scan.thumbnails.live.keys)
        assertEquals(0, scan.thumbnails.releases)
    }

    @Test
    fun facesSoFarCountsLiveAndFinishedTracks() {
        val scan = Scan()
        assertEquals(0, scan.analyzer.facesSoFar)
        for (i in 0 until 20) scan.frame(at30(i), listOf(Face(1, 400f, 400f, 200f)))
        assertEquals(1, scan.analyzer.facesSoFar)
        for (i in 20 until 60) scan.frame(at30(i), listOf(Face(2, 1400f, 600f, 200f), Face(3, 900f, 300f, 150f)))
        assertEquals(3, scan.analyzer.facesSoFar)
        // A single detection below the keep score is being followed but would not be in the result.
        scan.frame(at30(60), listOf(Face(4, 200f, 900f, 150f, score = 0.62f)))
        assertEquals(3, scan.analyzer.facesSoFar)
        assertEquals(3, scan.finish(at30(61)).tracks.size)
        assertEquals(3, scan.analyzer.facesSoFar)
    }

    @Test
    fun emptyVideoGivesAnEmptyResult() {
        val scan = Scan()
        val result = scan.finish(0)
        assertEquals(0, result.tracks.size)
        assertEquals(0, result.people.size)
        assertEquals(0, result.framesSeen)
        assertEquals(0L, result.durationUs)
        assertEquals(1, scan.detectors.closed)
    }

    @Test
    fun invalidArgumentsAreRejectedBeforeADetectorIsCreated() {
        val detectors = ScriptedDetectors()
        val factory = DefaultFaceAnalyzerFactory()
        val store = FakeThumbnails()
        assertThrows(IllegalArgumentException::class.java) { factory.create(0, 1080, AnalysisOptions.STANDARD, detectors, null, store) }
        assertThrows(IllegalArgumentException::class.java) { factory.create(1920, -1, AnalysisOptions.STANDARD, detectors, null, store) }
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(1920, 1080, AnalysisOptions(detectorLongSide = 0), detectors, null, store)
        }
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(1920, 1080, AnalysisOptions(maxDetectionsPerSecond = 0f), detectors, null, store)
        }
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(1920, 1080, AnalysisOptions(maxDetectionsPerSecond = Float.NaN), detectors, null, store)
        }
        assertEquals(0, detectors.created)
    }

    @Test
    fun detectorFailureLeavesTheScanUsable() {
        val scan = Scan()
        for (i in 0 until 10) scan.frame(at30(i), listOf(Face(1, 400f, 400f, 200f)))
        scan.detectors.failOnCall = scan.detectors.calls
        assertThrows(IllegalStateException::class.java) {
            scan.analyzer.onFrame(at30(10), Pixels.scene(0, 640, 384), FakeCropper(emptyList(), 1920, 1080, scan.crops))
        }
        scan.detectors.failOnCall = -1
        // The failed frame was not recorded, so the same timestamp can be offered again.
        for (i in 10 until 20) scan.frame(at30(i), listOf(Face(1, 400f, 400f, 200f)))
        val result = scan.finish(at30(20))
        assertEquals(20, result.framesSeen)
        assertEquals(20, result.tracks.single().detectedTimes().size)
    }

    @Test
    fun brokenEmbedderLeavesTracksUngroupedInsteadOfFailingTheScan() {
        val embedder = FakeEmbedder()
        embedder.failEmbedding = true
        val scan = Scan(embedder = embedder)
        for (i in 0 until 120) {
            val faces = if (i in 30..89) emptyList() else listOf(Face(3, 700f, 500f, 220f))
            scan.frame(at30(i), faces)
        }
        val result = scan.finish(4_000_000)
        assertEquals(2, result.tracks.size)
        assertEquals(2, result.people.size)
        assertEquals("the embedder is not asked again after it failed", 1, embedder.embedCalls)
    }

    @Test
    fun failingThumbnailStoreLeavesTracksWithoutPictures() {
        val store = FakeThumbnails()
        store.failPuts = true
        val scan = Scan(thumbnails = store)
        for (i in 0 until 60) scan.frame(at30(i), listOf(Face(1, 700f, 500f, 220f)))
        val result = scan.finish(at30(60))
        assertEquals(1, result.tracks.size)
        assertEquals(-1, result.tracks[0].thumbnailHandle)
        assertEquals(-1, result.people[0].thumbnailHandle)
        assertEquals("the store is left alone after it keeps failing", 3, scan.crops.sizes[128])
    }

    @Test
    fun unusableDetectionsAreIgnored() {
        val scan = Scan()
        val lm = FloatArray(10) { 0.5f }
        val junk = listOf(
            Detection(Float.NaN, 0.5f, 0.1f, 0.1f, 0.9f, lm),
            Detection(0.5f, Float.POSITIVE_INFINITY, 0.1f, 0.1f, 0.9f, lm),
            Detection(0.5f, 0.5f, 0f, 0.1f, 0.9f, lm),
            Detection(0.5f, 0.5f, 0.1f, -0.1f, 0.9f, lm),
            Detection(0.5f, 0.5f, 0.1f, 0.1f, Float.NaN, lm),
            Detection(1.5f, 0.5f, 0.1f, 0.1f, 0.9f, lm),
            Detection(0.5f, -0.3f, 0.1f, 0.1f, 0.9f, lm),
        )
        for (i in 0 until 30) scan.frame(at30(i), listOf(Face(1, 400f, 400f, 200f)), extra = junk)
        val result = scan.finish(at30(30))
        assertEquals(1, result.tracks.size)
    }

    @Test
    fun faceHalfOutsideTheFrameIsStoredClippedToIt() {
        val scan = Scan()
        for (i in 0 until 30) scan.frame(at30(i), listOf(Face(1, 20f, 1070f, 200f, landmarks = false)))
        val track = scan.finish(at30(30)).tracks.single()
        for (s in 0 until track.sampleCount) {
            val left = track.boxes[s * 4] - track.boxes[s * 4 + 2] / 2
            val right = track.boxes[s * 4] + track.boxes[s * 4 + 2] / 2
            val top = track.boxes[s * 4 + 1] - track.boxes[s * 4 + 3] / 2
            val bottom = track.boxes[s * 4 + 1] + track.boxes[s * 4 + 3] / 2
            assertTrue("sample $s inside the frame", left >= 0f && right <= 1f && top >= 0f && bottom <= 1f)
            assertTrue("sample $s covers the visible part", right >= 120f / 1920 - 1e-5f && top <= 970f / 1080 + 1e-5f)
        }
        assertTrue(track.thumbnailHandle >= 0)
    }
}
