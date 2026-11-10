package io.github.vikas0706.openblur.tracking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneCutTest {
    private val face = listOf(Face(1, 960f, 540f, 200f))

    @Test
    fun hardCutIsFoundOnBothOrientations() {
        for ((width, height) in listOf(640 to 360, 360 to 640, 1280 to 720, 33 to 17, 1 to 1)) {
            val inputWidth = (width + 31) / 32 * 32
            val inputHeight = (height + 31) / 32 * 32
            val detector = SceneCutDetector(width, height)
            for (run in 0..7) {
                val scene = if (run < 5) 0 else 9
                val score = detector.ingest(run, Pixels.scene(scene, inputWidth, inputHeight, brighten = if (run < 5) 0 else 150), inputWidth * 4)
                if (run == 5) assertTrue("${width}x$height cut score $score", score > 50f) else assertEquals(0f, score, 0f)
                assertEquals("${width}x$height candidate at $run", run == 5, detector.isCandidate(run))
            }
            assertTrue("${width}x$height", detector.confirm(5, 7))
        }
    }

    @Test
    fun slowPanIsNotACandidate() {
        val detector = SceneCutDetector(640, 360)
        for (run in 0 until 30) {
            val score = detector.ingest(run, Pixels.scene(4, 640, 384, shiftX = run * 20), 640 * 4)
            assertTrue("pan score $score at run $run", score < Tuning.CUT_MIN_SCORE)
            assertFalse(detector.isCandidate(run))
        }
    }

    @Test
    fun whipPanIsRejectedOnceTheFollowingRunsAreKnown() {
        val scan = Scan()
        // Still, then the picture jumps by half a screen per frame for a third of a second, then still again.
        for (i in 0 until 70) scan.frame(at30(i), face, scene = 4, shiftX = 320 * (i - 29).coerceIn(0, 10))
        val result = scan.finish(at30(70))
        assertEquals(0, result.cutsUs.size)
        assertEquals(1, result.tracks.size)
        assertEquals(70, result.tracks[0].detectedTimes().size)
    }

    @Test
    fun twoFrameFlashIsNotACut() {
        val scan = Scan()
        for (i in 0 until 60) scan.frame(at30(i), face, scene = 5, brighten = if (i == 30 || i == 31) 110 else 0)
        val result = scan.finish(at30(60))
        assertEquals(0, result.cutsUs.size)
        assertEquals(1, result.tracks.size)
    }

    @Test
    fun flashNextToACutDoesNotHideIt() {
        for (flashAt in listOf(27, 28, 31)) {
            val scan = Scan()
            for (i in 0 until 60) {
                scan.frame(at30(i), face, scene = if (i < 30) 1 else 2, brighten = if (i == flashAt) 110 else 0)
            }
            val result = scan.finish(at30(60))
            assertArrayEquals("flash at frame $flashAt", longArrayOf(at30(30)), result.cutsUs)
            assertEquals("flash at frame $flashAt", 2, result.tracks.size)
        }
    }

    @Test
    fun flashOnTheLastFrameOfAShotMovesTheCutOneFrameEarly() {
        val scan = Scan()
        for (i in 0 until 60) scan.frame(at30(i), face, scene = if (i < 30) 1 else 2, brighten = if (i == 29) 110 else 0)
        val result = scan.finish(at30(60))
        // The washed-out frame differs from both shots; it is taken as the first frame of the new one.
        assertArrayEquals(longArrayOf(at30(29)), result.cutsUs)
        assertEquals(2, result.tracks.size)
        assertEquals(60, result.tracks.sumOf { it.detectedTimes().size })
    }

    @Test
    fun secondCutWithinHalfASecondIsNotRecorded() {
        val scan = Scan()
        for (i in 0 until 30) scan.frame(at30(i), face, scene = 1)
        for (i in 30 until 36) scan.frame(at30(i), face, scene = 2)
        for (i in 36 until 70) scan.frame(at30(i), face, scene = 3)
        val result = scan.finish(at30(70))
        assertArrayEquals(longArrayOf(at30(30)), result.cutsUs)
    }

    @Test
    fun cutsFurtherApartAreAllRecorded() {
        val scan = Scan()
        for (i in 0 until 120) scan.frame(at30(i), face, scene = 1 + i / 20)
        val result = scan.finish(at30(120))
        assertArrayEquals(LongArray(5) { at30((it + 1) * 20) }, result.cutsUs)
        assertEquals(6, result.tracks.size)
        assertEquals("the same face in every shot", 1, result.people.size)
    }

    @Test
    fun cutBackToAnEarlierShotIsStillACut() {
        val scan = Scan()
        for (i in 0 until 90) scan.frame(at30(i), face, scene = if (i in 30..59) 2 else 1)
        val result = scan.finish(at30(90))
        assertArrayEquals(longArrayOf(at30(30), at30(60)), result.cutsUs)
    }

    @Test
    fun cutSeenBetweenDetectorRunsLetsBothSidesCoverTheSkippedFrames() {
        val scan = Scan()
        // 60 fps: the detector runs on even frames. The cut falls on an odd frame, which it never sees.
        for (i in 0 until 121) {
            val pts = i * 1_000_000L / 60
            val before = i < 61
            scan.frame(pts, listOf(if (before) Face(1, 500f, 500f, 200f) else Face(2, 1400f, 500f, 200f)), scene = if (before) 1 else 2)
        }
        val result = scan.finish(121 * 1_000_000L / 60)

        val seenAt = 62 * 1_000_000L / 60
        val skipped = 61 * 1_000_000L / 60
        assertArrayEquals(longArrayOf(seenAt), result.cutsUs)
        assertEquals(2, result.tracks.size)
        assertEquals("the old shot may run up to the frame that showed the cut", seenAt - 1, result.tracks[0].endUs)
        assertEquals("the new shot may start at the first frame the detector skipped", skipped, result.tracks[1].startUs)
    }

    @Test
    fun faceSeenOnlyWhileTheCutWasUndecidedIsStillEmbedded() {
        val scan = Scan()
        for (i in 0 until 30) scan.frame(at30(i), listOf(Face(5, 500f, 500f, 200f)), scene = 1)
        // In the new shot the same person shows for two frames only, the two that wait for the cut to be confirmed.
        for (i in 30 until 60) scan.frame(at30(i), if (i < 32) listOf(Face(5, 1400f, 600f, 240f)) else emptyList(), scene = 2)
        val result = scan.finish(at30(60))

        assertArrayEquals(longArrayOf(at30(30)), result.cutsUs)
        assertEquals(2, result.tracks.size)
        assertEquals(listOf(at30(30), at30(31)), result.tracks[1].detectedTimes())
        assertEquals("its crop was kept from the waiting frames", 1, result.people.size)
        assertEquals(5, scan.thumbnails.live[result.tracks[1].thumbnailHandle])
    }

    @Test
    fun closingWhileACutIsUndecidedReleasesEverything() {
        val scan = Scan()
        for (i in 0 until 30) scan.frame(at30(i), face, scene = 1)
        scan.frame(at30(30), listOf(Face(2, 1400f, 600f, 240f)), scene = 2)
        scan.analyzer.close()
        assertEquals(0, scan.thumbnails.live.size)
        assertEquals(emptyList<String>(), scan.thumbnails.errors)
        assertEquals(1, scan.detectors.closed)
    }

    @Test
    fun cutOnTheVeryLastRunIsNotActedOn() {
        val scan = Scan()
        for (i in 0 until 30) scan.frame(at30(i), face, scene = 1)
        scan.frame(at30(30), face, scene = 2)
        val result = scan.finish(at30(31))
        // One frame cannot show that the change lasts, and ending the track here could leave that frame uncovered.
        assertEquals(0, result.cutsUs.size)
        assertEquals(31, result.tracks.single().detectedTimes().size)
    }
}
