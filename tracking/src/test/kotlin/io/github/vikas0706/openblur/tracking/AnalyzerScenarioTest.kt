package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.FaceTrack
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerScenarioTest {
    private val detected = FaceTrack.SAMPLE_DETECTED
    private val interpolated = FaceTrack.SAMPLE_INTERPOLATED
    private val extrapolated = FaceTrack.SAMPLE_EXTRAPOLATED

    @Test
    fun oneSteadyFaceBecomesOneTrackAndOnePerson() {
        val scan = Scan()
        for (i in 0 until 60) scan.frame(at30(i), listOf(Face(1, 960f, 540f, 200f)))
        val result = scan.finish(2_000_000)

        assertEquals(1, result.tracks.size)
        assertEquals(1, result.people.size)
        val track = result.tracks[0]
        assertEquals(1, track.id)
        assertEquals(1, track.personId)
        assertEquals(0L, track.startUs)
        assertEquals(2_000_000L, track.endUs)
        assertEquals(61, track.sampleCount)
        assertEquals(List(60) { detected } + extrapolated, track.kindsList())
        for (i in 0 until 60) {
            assertEquals(0.5f, track.boxes[i * 4], 1e-4f)
            assertEquals(0.5f, track.boxes[i * 4 + 1], 1e-4f)
            assertEquals(200f / 1920, track.boxes[i * 4 + 2], 1e-4f)
            assertEquals(200f / 1080, track.boxes[i * 4 + 3], 1e-4f)
            assertEquals((960f - 42f) / 1920, track.eyes[i * 4], 1e-4f)
            assertEquals((960f + 42f) / 1920, track.eyes[i * 4 + 2], 1e-4f)
        }
        assertEquals(0.9f, track.meanScore, 1e-4f)
        assertTrue(track.thumbnailHandle >= 0)
        assertEquals(setOf(track.thumbnailHandle), scan.thumbnails.live.keys)
        assertEquals(1, scan.thumbnails.live[track.thumbnailHandle])

        val person = result.people[0]
        assertEquals(1, person.id)
        assertArrayEquals(intArrayOf(1), person.trackIds)
        assertEquals(track.thumbnailHandle, person.thumbnailHandle)
        assertEquals(0L, person.firstSeenUs)
        assertEquals(2_000_000L, person.visibleUs)

        assertEquals(1920, result.frameWidth)
        assertEquals(1080, result.frameHeight)
        assertEquals(2_000_000L, result.durationUs)
        assertEquals(60, result.framesSeen)
        assertEquals(60, result.framesDetected)
        assertEquals(0, result.cutsUs.size)
        assertEquals(1, scan.detectors.closed)
        assertEquals(emptyList<String>(), scan.thumbnails.errors)
    }

    @Test
    fun twoFacesCrossingKeepTheirIdentity() {
        val scan = Scan()
        for (i in 0 until 60) {
            val ax = 400f + i * 1100f / 59
            val bx = 1500f - i * 1100f / 59
            val faces = ArrayList<Face>()
            faces += Face(1, ax, 500f, 200f)
            // The face behind is hidden while the two overlap heavily, as a detector's de-duplication would do.
            if (abs(ax - bx) >= 120f) faces += Face(2, bx, 560f, 200f, score = 0.85f)
            scan.frame(at30(i), faces)
        }
        val result = scan.finish(2_000_000)

        assertEquals(2, result.tracks.size)
        assertEquals(2, result.people.size)
        for (track in result.tracks) {
            val n = track.sampleCount
            val startsLeft = track.boxes[0] < 0.5f
            val firstY = track.boxes[1] * 1080
            val lastDetected = (0 until n).last { track.sampleKinds[it] == detected }
            val lastX = track.boxes[lastDetected * 4] * 1920
            val lastY = track.boxes[lastDetected * 4 + 1] * 1080
            if (startsLeft) {
                assertEquals(500f, firstY, 12f)
                assertEquals(1500f, lastX, 12f)
                assertEquals(500f, lastY, 12f)
                assertEquals(1, scan.thumbnails.live[track.thumbnailHandle])
            } else {
                assertEquals(560f, firstY, 12f)
                assertEquals(400f, lastX, 12f)
                assertEquals(560f, lastY, 12f)
                assertEquals(2, scan.thumbnails.live[track.thumbnailHandle])
                assertTrue("the hidden stretch is interpolated", track.kindsList().contains(interpolated))
            }
        }
        assertNotEquals(result.tracks[0].personId, result.tracks[1].personId)
    }

    @Test
    fun faceMissedForSeveralRunsIsBridged() {
        val scan = Scan()
        for (i in 0 until 60) {
            val faces = if (i in 20..26) emptyList() else listOf(Face(1, 300f + i * 10f, 400f, 160f))
            scan.frame(at30(i), faces)
        }
        val result = scan.finish(at30(60))

        assertEquals(1, result.tracks.size)
        val track = result.tracks[0]
        assertEquals(1 + 53 + 7, track.sampleCount)
        for (s in 0 until track.sampleCount) {
            val frame = s
            val expected = if (frame in 20..26) interpolated else if (frame == 60) extrapolated else detected
            assertEquals("kind of sample $s", expected, track.sampleKinds[s])
            if (frame in 20..26) {
                assertEquals(at30(frame), track.timesUs[s])
                assertEquals((300f + frame * 10f) / 1920, track.boxes[s * 4], 2e-3f)
                assertEquals(400f / 1080, track.boxes[s * 4 + 1], 2e-3f)
                assertTrue("an interpolated box is larger than the face", track.boxes[s * 4 + 2] > 160f / 1920 * 1.1f)
                assertTrue("eyes are interpolated too", track.eyes[s * 4].isFinite())
            }
        }
    }

    @Test
    fun gapLongerThanTheLimitSplitsTheTrack() {
        val scan = Scan()
        for (i in 0 until 90) {
            val faces = if (i in 20..44) emptyList() else listOf(Face(1, 600f, 400f, 160f))
            scan.frame(at30(i), faces)
        }
        val result = scan.finish(at30(90))

        assertEquals(2, result.tracks.size)
        assertEquals(at30(19) + 200_000, result.tracks[0].endUs)
        assertEquals(at30(45) - 200_000, result.tracks[1].startUs)
        assertTrue(result.tracks.none { it.kindsList().contains(interpolated) })
    }

    @Test
    fun faceLeavingAndReturningIsOnePerson() {
        val scan = Scan()
        for (i in 0 until 120) {
            val faces = if (i in 30..89) emptyList() else listOf(Face(3, 700f, 500f, 220f))
            scan.frame(at30(i), faces)
        }
        val result = scan.finish(4_000_000)

        assertEquals(2, result.tracks.size)
        assertEquals(1, result.people.size)
        val person = result.people[0]
        assertArrayEquals(intArrayOf(1, 2), person.trackIds)
        assertEquals(1, result.tracks[0].personId)
        assertEquals(1, result.tracks[1].personId)
        assertEquals(result.tracks[0].startUs, person.firstSeenUs)
        assertEquals(
            result.tracks[0].endUs - result.tracks[0].startUs + result.tracks[1].endUs - result.tracks[1].startUs,
            person.visibleUs,
        )
        assertTrue(person.thumbnailHandle == result.tracks[0].thumbnailHandle || person.thumbnailHandle == result.tracks[1].thumbnailHandle)
        assertTrue("crops are embedded when a track closes", scan.embedder!!.embedCalls >= 2)
    }

    @Test
    fun twoSimultaneousSimilarFacesStayTwoPeople() {
        val embedder = FakeEmbedder()
        embedder.vectors[1] = unit(1f, 0f)
        embedder.vectors[2] = unit(0.95f, 0.3122f)
        val scan = Scan(embedder = embedder)
        for (i in 0 until 60) scan.frame(at30(i), listOf(Face(1, 500f, 500f, 200f), Face(2, 1300f, 500f, 200f)))
        // The first face comes back later: it joins its own earlier track, not the look-alike.
        for (i in 60 until 120) scan.frame(at30(i), emptyList())
        for (i in 120 until 150) scan.frame(at30(i), listOf(Face(1, 900f, 300f, 200f)))
        val result = scan.finish(5_000_000)

        assertEquals(3, result.tracks.size)
        assertEquals(2, result.people.size)
        val left = result.tracks.first { it.boxes[0] < 0.4f && it.startUs == 0L }
        val right = result.tracks.first { it.boxes[0] > 0.6f }
        val later = result.tracks.first { it.startUs > 3_000_000 }
        assertNotEquals(left.personId, right.personId)
        assertEquals(left.personId, later.personId)
    }

    @Test
    fun cutClosesTracksAndNothingCrossesIt() {
        val scan = Scan()
        for (i in 0 until 30) {
            val faces = if (i >= 27) emptyList() else listOf(Face(1, 960f, 540f, 200f))
            scan.frame(at30(i), faces, scene = 1)
        }
        for (i in 30 until 60) scan.frame(at30(i), listOf(Face(2, 960f, 540f, 200f)), scene = 2)
        val result = scan.finish(2_000_000)

        assertArrayEquals(longArrayOf(at30(30)), result.cutsUs)
        assertEquals(2, result.tracks.size)
        val before = result.tracks[0]
        val after = result.tracks[1]
        assertEquals(at30(26), before.detectedTimes().last())
        assertEquals("post-roll stops just before the cut", at30(30) - 1, before.endUs)
        assertEquals(extrapolated, before.sampleKinds[before.sampleCount - 1])
        assertTrue(before.kindsList().none { it == interpolated })
        assertEquals("no pre-roll before the first frame of the shot", at30(30), after.startUs)
        assertEquals(detected, after.sampleKinds[0])
        assertEquals(2, result.people.size)
        // The picture of the new face was taken while the cut was still undecided.
        assertEquals(1, scan.thumbnails.live[before.thumbnailHandle])
        assertEquals(2, scan.thumbnails.live[after.thumbnailHandle])
        assertEquals(emptyList<String>(), scan.thumbnails.errors)
    }

    @Test
    fun samePersonOnBothSidesOfACutIsTwoTracksOnePerson() {
        val scan = Scan()
        for (i in 0 until 30) scan.frame(at30(i), listOf(Face(4, 500f, 400f, 200f)), scene = 1)
        for (i in 30 until 60) scan.frame(at30(i), listOf(Face(4, 1300f, 700f, 260f)), scene = 2)
        val result = scan.finish(2_000_000)

        assertEquals(2, result.tracks.size)
        assertEquals(1, result.people.size)
        assertArrayEquals(intArrayOf(1, 2), result.people[0].trackIds)
    }

    @Test
    fun flashIsNotACut() {
        val scan = Scan()
        for (i in 0 until 60) {
            // One washed-out frame in which the detector only finds the face weakly.
            val flash = i == 30
            scan.frame(at30(i), listOf(Face(1, 960f, 540f, 200f, score = if (flash) 0.3f else 0.9f)), scene = 3, brighten = if (flash) 120 else 0)
        }
        val result = scan.finish(2_000_000)

        assertEquals(0, result.cutsUs.size)
        assertEquals(1, result.tracks.size)
        assertEquals(60, result.tracks[0].detectedTimes().size)
    }

    @Test
    fun cutInTheLastFramesIsDecidedAtFinish() {
        val scan = Scan()
        for (i in 0 until 30) scan.frame(at30(i), listOf(Face(1, 500f, 500f, 200f)), scene = 1)
        // Only two frames follow the cut, so its confirmation cannot wait for two later runs.
        for (i in 30 until 32) scan.frame(at30(i), listOf(Face(2, 1400f, 500f, 200f)), scene = 2)
        val result = scan.finish(at30(32))

        assertArrayEquals(longArrayOf(at30(30)), result.cutsUs)
        assertEquals(2, result.tracks.size)
        assertEquals(at30(30) - 1, result.tracks[0].endUs)
        assertEquals(at30(30), result.tracks[1].startUs)
        assertEquals(at30(32), result.tracks[1].endUs)
    }

    @Test
    fun weakDetectionsAloneNeverStartATrack() {
        val scan = Scan()
        for (i in 0 until 40) scan.frame(at30(i), listOf(Face(1, 800f, 500f, 180f, score = 0.45f)))
        val result = scan.finish(at30(40))

        assertEquals(0, result.tracks.size)
        assertEquals(0, result.people.size)
        assertEquals(0, scan.thumbnails.puts)
        assertEquals(0, scan.crops.total)
    }

    @Test
    fun weakDetectionsExtendATrack() {
        val scan = Scan()
        for (i in 0 until 40) {
            val score = if (i < 10) 0.9f else 0.3f
            scan.frame(at30(i), listOf(Face(1, 800f + i * 4f, 500f, 180f, score = score)))
        }
        val result = scan.finish(at30(40))

        assertEquals(1, result.tracks.size)
        val track = result.tracks[0]
        assertEquals(at30(39), track.detectedTimes().last())
        assertEquals(40, track.detectedTimes().size)
        assertEquals((10 * 0.9f + 30 * 0.3f) / 40, track.meanScore, 1e-3f)
    }

    @Test
    fun weakDetectionsBeforeAFaceIsConfirmedAreRecovered() {
        val scan = Scan()
        for (i in 0 until 40) {
            val score = if (i < 12) 0.35f else 0.9f
            scan.frame(at30(i + 30), listOf(Face(1, 800f + i * 4f, 500f, 180f, score = score)))
        }
        val result = scan.finish(at30(70))

        assertEquals(1, result.tracks.size)
        val track = result.tracks[0]
        assertEquals(at30(30), track.detectedTimes().first())
        assertEquals(40, track.detectedTimes().size)
        assertEquals(at30(30) - 200_000, track.startUs)
    }

    @Test
    fun scoreBetweenTheStrongAndNewTrackThresholdsDoesNotStartATrack() {
        val scan = Scan()
        for (i in 0 until 20) scan.frame(at30(i), listOf(Face(1, 800f, 500f, 180f, score = 0.55f)))
        assertEquals(0, scan.finish(at30(20)).tracks.size)
    }

    @Test
    fun singleStrongDetectionBecomesAShortTrack() {
        val scan = Scan()
        for (i in 0 until 40) scan.frame(at30(i), if (i == 15) listOf(Face(1, 800f, 500f, 180f, score = 0.8f)) else emptyList())
        val result = scan.finish(at30(40))

        assertEquals(1, result.tracks.size)
        val track = result.tracks[0]
        assertEquals(listOf(extrapolated, detected, extrapolated), track.kindsList())
        assertArrayEquals(longArrayOf(at30(15) - 200_000, at30(15), at30(15) + 200_000), track.timesUs)
        for (s in 0 until 3) {
            assertTrue("the box covers the face in sample $s", track.boxes[s * 4 + 2] >= 180f / 1920 - 1e-5f)
            assertEquals(800f / 1920, track.boxes[s * 4], 1e-3f)
        }
        assertEquals(1, result.people.size)
        assertEquals(setOf(track.thumbnailHandle), scan.thumbnails.live.keys)
    }

    @Test
    fun singleUnconfirmedDetectionBelowTheKeepScoreIsDropped() {
        val scan = Scan()
        for (i in 0 until 40) scan.frame(at30(i), if (i == 15) listOf(Face(1, 800f, 500f, 180f, score = 0.65f)) else emptyList())
        val result = scan.finish(at30(40))

        assertEquals(0, result.tracks.size)
        assertEquals("its thumbnail was made and given back", 1, scan.thumbnails.puts)
        assertEquals(0, scan.thumbnails.live.size)
        assertEquals(emptyList<String>(), scan.thumbnails.errors)
    }

    @Test
    fun twoDetectionsBelowTheKeepScoreAreKept() {
        val scan = Scan()
        for (i in 0 until 40) scan.frame(at30(i), if (i == 15 || i == 16) listOf(Face(1, 800f, 500f, 180f, score = 0.62f)) else emptyList())
        assertEquals(1, scan.finish(at30(40)).tracks.size)
    }

    @Test
    fun rollIsClampedToTheVideoStartAndEnd() {
        val scan = Scan()
        for (i in 0 until 90) {
            val faces = ArrayList<Face>()
            faces += Face(1, 400f, 400f, 200f)
            if (i in 30..50) faces += Face(2, 1400f, 600f, 200f)
            scan.frame(at30(i), faces)
        }
        // The reported duration is a little after the last frame, as it is for a real file.
        val result = scan.finish(at30(90))

        val whole = result.tracks.first { it.startUs == 0L }
        assertEquals(detected, whole.sampleKinds[0])
        assertEquals(at30(90), whole.endUs)
        assertEquals(extrapolated, whole.sampleKinds[whole.sampleCount - 1])
        val middle = result.tracks.first { it !== whole }
        assertEquals(at30(30) - 200_000, middle.startUs)
        assertEquals(at30(50) + 200_000, middle.endUs)
        assertEquals(extrapolated, middle.sampleKinds[0])
        assertEquals(extrapolated, middle.sampleKinds[middle.sampleCount - 1])
    }

    @Test
    fun durationShorterThanTheLastFrameStillLeavesRoomAfterIt() {
        val scan = Scan()
        scan.frame(0, listOf(Face(1, 400f, 400f, 200f, score = 0.95f)))
        val result = scan.finish(0)

        assertEquals(1L, result.durationUs)
        assertEquals(1, result.tracks.size)
        assertArrayEquals(longArrayOf(0, 1), result.tracks[0].timesUs)
    }

    @Test
    fun rollFollowsAMovingFace() {
        val scan = Scan()
        for (i in 0 until 60) {
            val faces = if (i in 20..40) listOf(Face(1, 400f + (i - 20) * 30f, 500f, 150f)) else emptyList()
            scan.frame(at30(i), faces)
        }
        val track = scan.finish(at30(60)).tracks.single()

        val n = track.sampleCount
        val preLeft = (track.boxes[0] - track.boxes[2] / 2) * 1920
        val postRight = (track.boxes[(n - 1) * 4] + track.boxes[(n - 1) * 4 + 2] / 2) * 1920
        // Six frames at 30 pixels per frame: the face was about 180 pixels further back, and will be further on.
        assertTrue("pre-roll reaches back along the motion, left edge at $preLeft", preLeft < 400f - 75f - 150f)
        assertTrue("post-roll reaches ahead along the motion, right edge at $postRight", postRight > 1000f + 75f + 150f)
        assertTrue("pre-roll still covers the first detection", (track.boxes[0] + track.boxes[2] / 2) * 1920 >= 475f - 1f)
    }

    @Test
    fun thumbnailIsReplacedOnlyByAClearlyBetterOne() {
        val scan = Scan()
        for (i in 0 until 90) scan.frame(at30(i), listOf(Face(1, 960f, 540f, 30f + i * 2f)))
        val result = scan.finish(at30(90))

        val track = result.tracks.single()
        assertTrue("several thumbnails were taken as the face grew: ${scan.thumbnails.puts}", scan.thumbnails.puts in 3..12)
        assertEquals(scan.thumbnails.puts - 1, scan.thumbnails.releases)
        assertEquals(setOf(track.thumbnailHandle), scan.thumbnails.live.keys)
        assertEquals("the kept one is the newest", scan.thumbnails.puts - 1, track.thumbnailHandle)
        assertEquals(emptyList<String>(), scan.thumbnails.errors)
    }

    @Test
    fun steadyFaceIsCroppedOnlyAFewTimes() {
        val scan = Scan()
        for (i in 0 until 300) scan.frame(at30(i), listOf(Face(1, 960f, 540f, 200f)))
        val embedsBefore = scan.embedder!!.embedCalls
        scan.finish(at30(300))

        assertEquals(1, scan.crops.sizes[128])
        // Five kept samples, and one crop a second to compare the face with the embedding of its track.
        assertTrue("aligned crops: ${scan.crops.sizes[16]}", scan.crops.sizes[16]!! in 5..5 + 10)
        assertEquals("the five kept samples are embedded when the track closes", 5, scan.embedder.embedCalls - embedsBefore)
    }

    @Test
    fun bestFiveSamplesAreKeptAndSpaced() {
        val scan = Scan()
        // Quality rises through the whole clip, so later crops keep replacing earlier ones.
        for (i in 0 until 240) scan.frame(at30(i), listOf(Face(1, 960f, 540f, 200f, turn = 0.45f * (1f - i / 240f))))
        val embedsBefore = scan.embedder!!.embedCalls
        scan.finish(at30(240))

        assertEquals(5, scan.embedder.embedCalls - embedsBefore)
        assertTrue("crops were retaken as quality improved: ${scan.crops.sizes[16]}", scan.crops.sizes[16]!! > 5 + 8)
    }

    @Test
    fun strangerWhereAFaceWasLastSeenGetsItsOwnTrack() {
        val scan = Scan()
        for (i in 0 until 80) {
            val faces = when {
                i < 30 -> listOf(Face(1, 800f, 500f, 200f))
                i < 36 -> emptyList()
                else -> listOf(Face(2, 806f, 502f, 200f))
            }
            scan.frame(at30(i), faces)
        }
        val result = scan.finish(at30(80))

        // Six missed runs is well inside what a track bridges, and the boxes overlap almost fully.
        assertEquals(2, result.tracks.size)
        assertEquals(at30(29), result.tracks[0].detectedTimes().last())
        assertEquals(at30(36), result.tracks[1].detectedTimes().first())
        assertEquals(2, result.people.size)
        assertEquals(1, scan.thumbnails.live[result.tracks[0].thumbnailHandle])
        assertEquals(2, scan.thumbnails.live[result.tracks[1].thumbnailHandle])
    }

    @Test
    fun samePersonReappearingAfterAShortGapStaysOneTrack() {
        val scan = Scan()
        for (i in 0 until 80) scan.frame(at30(i), if (i in 30..35) emptyList() else listOf(Face(1, 800f + i, 500f, 200f)))
        val result = scan.finish(at30(80))

        assertEquals(1, result.tracks.size)
        assertEquals(74, result.tracks[0].detectedTimes().size)
    }

    @Test
    fun withoutAnEmbedderOverlapAloneDecides() {
        val scan = Scan(embedder = null)
        for (i in 0 until 80) {
            val faces = when {
                i < 30 -> listOf(Face(1, 800f, 500f, 200f))
                i < 36 -> emptyList()
                else -> listOf(Face(2, 806f, 502f, 200f))
            }
            scan.frame(at30(i), faces)
        }
        assertEquals(1, scan.finish(at30(80)).tracks.size)
    }

    @Test
    fun faceNextToAMissedOneIsNotTakenForIt() {
        val scan = Scan()
        for (i in 0 until 60) {
            val faces = ArrayList<Face>()
            // The second person comes out from behind the first, in the one frame where the first is missed.
            if (i != 20) faces += Face(1, 700f, 500f, 200f)
            if (i >= 20) faces += Face(2, 830f, 500f, 200f)
            scan.frame(at30(i), faces)
        }
        val result = scan.finish(at30(60))

        assertEquals(2, result.tracks.size)
        val first = result.tracks[0]
        val second = result.tracks[1]
        assertEquals(59, first.detectedTimes().size)
        assertEquals(40, second.detectedTimes().size)
        for (s in 0 until first.sampleCount) assertEquals(700f / 1920, first.boxes[s * 4], 0.01f)
        for (s in 0 until second.sampleCount) assertEquals(830f / 1920, second.boxes[s * 4], 0.01f)
    }

    @Test
    fun handOverWithoutAGapIsCaughtByTheRoutineCheck() {
        val scan = Scan()
        // One face is swapped for another in the same place from one frame to the next.
        for (i in 0 until 120) scan.frame(at30(i), listOf(Face(if (i < 45) 1 else 2, 800f, 500f, 200f)))
        val result = scan.finish(at30(120))

        assertEquals(2, result.tracks.size)
        assertEquals(2, result.people.size)
        val handOver = result.tracks[0].detectedTimes().last()
        assertTrue("the first track ends within about a second of the swap: $handOver", handOver in at30(45)..at30(45) + 1_100_000)
        assertEquals(1, scan.thumbnails.live[result.tracks[0].thumbnailHandle])
        assertEquals(2, scan.thumbnails.live[result.tracks[1].thumbnailHandle])
    }

    @Test
    fun betterPictureOfAStrangerDoesNotBecomeTheReference() {
        val scan = Scan()
        // The first person is small and turned away, so the swapped-in face is by far the better picture.
        for (i in 0 until 150) {
            val face = if (i < 40) Face(1, 800f, 500f, 60f, turn = 0.25f) else Face(2, 800f, 500f, 62f)
            scan.frame(at30(i), listOf(face))
        }
        val result = scan.finish(at30(150))

        assertEquals(2, result.tracks.size)
        assertEquals(2, result.people.size)
        assertTrue(result.tracks[0].detectedTimes().last() < at30(40) + 1_200_000)
        assertEquals(1, scan.thumbnails.live[result.tracks[0].thumbnailHandle])
        assertEquals(2, scan.thumbnails.live[result.tracks[1].thumbnailHandle])
    }

    @Test
    fun oneBadCropDoesNotSplitATrack() {
        val scan = Scan()
        // A single frame in which the crop looks like someone else, right when a routine check is due.
        for (i in 0 until 120) scan.frame(at30(i), listOf(Face(if (i == 60) 9 else 1, 800f, 500f, 200f)))
        val result = scan.finish(at30(120))

        assertEquals(1, result.tracks.size)
        assertEquals(120, result.tracks[0].detectedTimes().size)
    }

    @Test
    fun groupingDisabledMakesEveryTrackItsOwnPersonAndNeverTouchesTheEmbedder() {
        val embedder = FakeEmbedder()
        val scan = Scan(options = AnalysisOptions(groupPeople = false), embedder = embedder)
        for (i in 0 until 120) {
            val faces = if (i in 30..89) emptyList() else listOf(Face(3, 700f, 500f, 220f))
            scan.frame(at30(i), faces)
        }
        val result = scan.finish(4_000_000)

        assertEquals(2, result.tracks.size)
        assertEquals(2, result.people.size)
        assertEquals(listOf(1, 2), result.tracks.map { it.personId })
        assertEquals(0, embedder.embedCalls)
        assertEquals(0, embedder.alignCalls)
        assertEquals("only thumbnails are cropped", setOf(128), scan.crops.sizes.keys)
        assertTrue(result.tracks.all { it.thumbnailHandle >= 0 })
        assertEquals(listOf(result.tracks[0].thumbnailHandle, result.tracks[1].thumbnailHandle), result.people.map { it.thumbnailHandle })
    }

    @Test
    fun nullEmbedderDisablesGroupingTheSameWay() {
        val scan = Scan(embedder = null)
        for (i in 0 until 120) {
            val faces = if (i in 30..89) emptyList() else listOf(Face(3, 700f, 500f, 220f))
            scan.frame(at30(i), faces)
        }
        val result = scan.finish(4_000_000)

        assertEquals(2, result.people.size)
        assertEquals(setOf(128), scan.crops.sizes.keys)
    }

    @Test
    fun smallFacesAreNotEmbeddedButStillGetThumbnails() {
        val scan = Scan()
        for (i in 0 until 60) scan.frame(at30(i), listOf(Face(1, 960f, 540f, 30f)))
        val result = scan.finish(at30(60))

        assertEquals(1, result.tracks.size)
        assertEquals(0, scan.embedder!!.embedCalls)
        assertTrue(result.tracks[0].thumbnailHandle >= 0)
    }

    @Test
    fun faceWithoutLandmarksHasUnknownEyes() {
        val scan = Scan()
        for (i in 0 until 30) scan.frame(at30(i), listOf(Face(1, 960f, 540f, 200f, landmarks = i % 2 == 0)))
        val track = scan.finish(at30(30)).tracks.single()

        for (s in 0 until track.sampleCount) {
            val known = track.eyes[s * 4].isFinite()
            for (e in 1 until 4) assertEquals("eyes of sample $s are all known or all unknown", known, track.eyes[s * 4 + e].isFinite())
            if (track.sampleKinds[s] == detected) assertEquals(s % 2 == 0, known)
        }
    }

    @Test
    fun storedBoxCoversAJitteringDetection() {
        val scan = Scan()
        val random = java.util.Random(7)
        val raw = ArrayList<FloatArray>()
        for (i in 0 until 120) {
            val x = 600f + i * 3f + random.nextInt(9) - 4
            val y = 500f + random.nextInt(9) - 4
            val size = 180f + random.nextInt(13) - 6
            raw += floatArrayOf(x, y, size)
            scan.frame(at30(i), listOf(Face(1, x, y, size)))
        }
        val track = scan.finish(at30(120)).tracks.single()

        var rawTravel = 0f
        var storedTravel = 0f
        for (i in 0 until 120) {
            val (x, y, size) = raw[i]
            val left = (track.boxes[i * 4] - track.boxes[i * 4 + 2] / 2) * 1920
            val right = (track.boxes[i * 4] + track.boxes[i * 4 + 2] / 2) * 1920
            val top = (track.boxes[i * 4 + 1] - track.boxes[i * 4 + 3] / 2) * 1080
            val bottom = (track.boxes[i * 4 + 1] + track.boxes[i * 4 + 3] / 2) * 1080
            assertTrue("sample $i covers its detection", left <= x - size / 2 + 0.02f && right >= x + size / 2 - 0.02f)
            assertTrue("sample $i covers its detection", top <= y - size / 2 + 0.02f && bottom >= y + size / 2 - 0.02f)
            assertTrue("sample $i is not much larger than the face", right - left < size + 40f)
            if (i in 11..109) {
                rawTravel += abs(raw[i][1] - raw[i - 1][1])
                storedTravel += abs(track.boxes[i * 4 + 1] - track.boxes[(i - 1) * 4 + 1]) * 1080
            }
        }
        assertTrue("smoothing removes most of the jitter: $storedTravel against $rawTravel", storedTravel < rawTravel * 0.5f)
    }

    @Test
    fun smoothingDoesNotLagBehindASteadilyMovingFace() {
        val scan = Scan()
        for (i in 0 until 90) scan.frame(at30(i), listOf(Face(1, 300f + i * 15f, 500f, 180f)))
        val track = scan.finish(at30(90)).tracks.single()

        for (i in 0 until 90) assertEquals("center of sample $i", 300f + i * 15f, track.boxes[i * 4] * 1920, 0.5f)
    }

    private fun unit(a: Float, b: Float): FloatArray = FloatArray(16).also {
        it[0] = a
        it[1] = b
    }
}
