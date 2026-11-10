package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.Detection
import io.github.vikas0706.openblur.core.FaceTrack
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackInvariantsTest {
    private class Actor(
        val identity: Int,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var size: Float,
        var framesLeft: Int,
        val seenChance: Float,
        val weakChance: Float,
    )

    /** A messy video: faces come and go, are missed, turn weak, leave the frame; cuts, stalls and junk detections. */
    private fun randomScan(seed: Long, frameWidth: Int, frameHeight: Int, options: AnalysisOptions, frames: Int): Scan {
        val random = Random(seed)
        val scan = Scan(frameWidth, frameHeight, options)
        val actors = ArrayList<Actor>()
        var scene = 1
        var pts = if (seed % 2 == 0L) 0L else 41_000L
        var nextCut = 150 + random.nextInt(250)
        for (i in 0 until frames) {
            if (i == nextCut) {
                scene++
                scan.scriptedCuts++
                actors.clear()
                nextCut = i + 40 + random.nextInt(400)
            }
            if (actors.size < 6 && random.nextFloat() < 0.04f) {
                actors += Actor(
                    identity = 1 + random.nextInt(8),
                    x = random.nextFloat() * frameWidth,
                    y = random.nextFloat() * frameHeight,
                    vx = (random.nextFloat() - 0.5f) * 30f,
                    vy = (random.nextFloat() - 0.5f) * 20f,
                    size = 24f + random.nextFloat() * 300f,
                    framesLeft = 1 + random.nextInt(400),
                    seenChance = 0.6f + random.nextFloat() * 0.4f,
                    weakChance = random.nextFloat() * 0.5f,
                )
            }
            val faces = ArrayList<Face>()
            val iterator = actors.iterator()
            while (iterator.hasNext()) {
                val actor = iterator.next()
                actor.x += actor.vx + (random.nextFloat() - 0.5f) * 6f
                actor.y += actor.vy + (random.nextFloat() - 0.5f) * 6f
                actor.vx += (random.nextFloat() - 0.5f) * 2f
                actor.vy += (random.nextFloat() - 0.5f) * 2f
                actor.size = (actor.size * (0.98f + random.nextFloat() * 0.04f)).coerceIn(16f, 500f)
                actor.framesLeft--
                val gone = actor.x < -actor.size || actor.x > frameWidth + actor.size || actor.y < -actor.size || actor.y > frameHeight + actor.size
                if (actor.framesLeft <= 0 || gone) {
                    iterator.remove()
                    continue
                }
                if (random.nextFloat() > actor.seenChance) continue
                val score = if (random.nextFloat() < actor.weakChance) 0.16f + random.nextFloat() * 0.4f else 0.6f + random.nextFloat() * 0.39f
                faces += Face(
                    actor.identity, actor.x, actor.y, actor.size, score,
                    landmarks = random.nextFloat() > 0.05f,
                    turn = if (random.nextFloat() < 0.3f) random.nextFloat() * 0.6f else 0f,
                )
            }
            val extra = ArrayList<Detection>()
            if (random.nextFloat() < 0.05f) {
                extra += Detection(random.nextFloat(), random.nextFloat(), 0.02f + random.nextFloat() * 0.1f, 0.03f + random.nextFloat() * 0.1f, 0.15f + random.nextFloat() * 0.8f, FloatArray(10) { random.nextFloat() })
            }
            if (random.nextFloat() < 0.02f) {
                extra += Detection(Float.NaN, 0.5f, 0.1f, 0.1f, 0.9f, FloatArray(10))
                extra += Detection(0.5f, 0.5f, -0.1f, 0.1f, 0.9f, FloatArray(3))
                extra += Detection(0.5f, 0.5f, 40f, 40f, 0.3f, FloatArray(10) { Float.NaN })
                extra += Detection(0.999f, 0.001f, 0.2f, 0.2f, 0.95f, FloatArray(4))
            }
            scan.frame(pts, faces, scene = scene, brighten = if (random.nextFloat() < 0.01f) 100 else 0, extra = extra)
            pts += when {
                random.nextFloat() < 0.005f -> 300_000L + random.nextInt(1_500_000)
                else -> 12_000L + random.nextInt(40_000)
            }
        }
        return scan
    }

    private fun checkInvariants(scan: Scan, result: AnalysisResult, grouped: Boolean) {
        assertEquals(scan.frameTimes.size, result.framesSeen)
        assertEquals(scan.runTimes.size, result.framesDetected)
        assertEquals(emptyList<String>(), scan.thumbnails.errors)
        val firstFrame = scan.frameTimes.first()
        val lastFrame = scan.frameTimes.last()
        assertTrue(result.durationUs > lastFrame)
        for (i in 1 until result.cutsUs.size) assertTrue("cuts are sorted", result.cutsUs[i] > result.cutsUs[i - 1])
        val runIndex = HashMap<Long, Int>()
        for ((index, time) in scan.runTimes.withIndex()) runIndex[time] = index
        // Frames the detector skipped between two runs may sit on either side of a cut seen at the later run.
        val earliestOfCut = HashMap<Long, Long>()
        for (cut in result.cutsUs) {
            assertTrue("a cut is seen at a detector run", cut in runIndex)
            val previousRun = scan.runTimes[runIndex[cut]!! - 1]
            earliestOfCut[cut] = scan.frameTimes.first { it > previousRun }
        }

        val handles = HashSet<Int>()
        for ((index, track) in result.tracks.withIndex()) {
            val label = "track ${track.id}"
            assertEquals("ids count from 1 in list order", index + 1, track.id)
            if (index > 0) assertTrue("$label starts no earlier than the one before", track.startUs >= result.tracks[index - 1].startUs)
            val n = track.sampleCount
            assertTrue("$label has at least two samples", n >= 2)
            assertEquals(n * 4, track.boxes.size)
            assertEquals(n * 4, track.eyes.size)
            assertEquals(n, track.sampleKinds.size)
            assertTrue("$label starts inside the video", track.startUs >= min(0L, firstFrame))
            assertTrue("$label ends inside the video", track.endUs <= result.durationUs)
            assertTrue("$label mean score", track.meanScore.isFinite() && track.meanScore > 0f && track.meanScore <= 1f)
            var detectedSamples = 0
            for (s in 0 until n) {
                if (s > 0) assertTrue("$label times increase at $s", track.timesUs[s] > track.timesUs[s - 1])
                val cx = track.boxes[s * 4]
                val cy = track.boxes[s * 4 + 1]
                val w = track.boxes[s * 4 + 2]
                val h = track.boxes[s * 4 + 3]
                assertTrue("$label box $s is finite", cx.isFinite() && cy.isFinite() && w.isFinite() && h.isFinite())
                assertTrue("$label box $s has a size", w > 0f && h > 0f)
                assertTrue(
                    "$label box $s is inside the frame: $cx $cy $w $h",
                    cx - w / 2 >= 0f && cx + w / 2 <= 1f && cy - h / 2 >= 0f && cy + h / 2 <= 1f,
                )
                val eyesKnown = !track.eyes[s * 4].isNaN()
                for (e in 0 until 4) {
                    val value = track.eyes[s * 4 + e]
                    assertTrue("$label eyes $s are all known or all NaN", if (eyesKnown) value.isFinite() else value.isNaN())
                }
                val kind = track.sampleKinds[s]
                assertTrue("$label kind $s", kind in 0..2)
                if (kind == FaceTrack.SAMPLE_EXTRAPOLATED) assertTrue("$label extrapolates only at its ends", s == 0 || s == n - 1)
                if (kind == FaceTrack.SAMPLE_INTERPOLATED) {
                    assertTrue("$label interpolates between detections", s in 1 until n - 1)
                    assertTrue("$label interpolates at a detector run", track.timesUs[s] in scan.reported)
                }
                if (kind == FaceTrack.SAMPLE_DETECTED) {
                    detectedSamples++
                    assertCoversADetection(label, s, track, scan.reported[track.timesUs[s]])
                }
            }
            assertTrue("$label has a detection", detectedSamples >= 1)
            val detectedTimes = track.detectedTimes()
            // Inside a track the detector never misses the face for longer than the gap limit, unless runs are sparse.
            for (k in 1 until detectedTimes.size) {
                val gap = detectedTimes[k] - detectedTimes[k - 1]
                val runsBetween = runIndex[detectedTimes[k]]!! - runIndex[detectedTimes[k - 1]]!!
                assertTrue("$label bridges $gap us over $runsBetween runs", gap <= Tuning.MAX_GAP_US || runsBetween <= Tuning.MIN_BRIDGED_RUNS)
            }
            assertTrue("$label pre-roll is at most 0.2 s", detectedTimes.first() - track.startUs <= Tuning.ROLL_US)
            assertTrue("$label post-roll is at most 0.2 s", track.endUs - detectedTimes.last() <= Tuning.ROLL_US)
            for (cut in result.cutsUs) {
                assertTrue(
                    "$label (${track.startUs}..${track.endUs}) crosses the cut at $cut",
                    track.endUs < cut || track.startUs >= earliestOfCut[cut]!!,
                )
                assertTrue("$label detections stay on one side of the cut at $cut", detectedTimes.last() < cut || detectedTimes.first() >= cut)
            }
            if (track.thumbnailHandle >= 0) assertTrue("$label thumbnail handle is unique", handles.add(track.thumbnailHandle))
        }
        assertEquals("live thumbnails are exactly the ones the result refers to", handles, scan.thumbnails.live.keys)

        val owner = HashMap<Int, Int>()
        for ((index, person) in result.people.withIndex()) {
            assertEquals("person ids count from 1 in list order", index + 1, person.id)
            assertTrue("person ${person.id} has tracks", person.trackIds.isNotEmpty())
            val sorted = person.trackIds.sortedArray()
            assertArrayEquals(sorted, person.trackIds)
            var first = Long.MAX_VALUE
            var visible = 0L
            val own = HashSet<Int>()
            for (id in person.trackIds) {
                val track = result.track(id)!!
                assertEquals(person.id, track.personId)
                assertTrue("track $id belongs to one person", owner.put(id, person.id) == null)
                first = min(first, track.startUs)
                visible += track.endUs - track.startUs
                if (track.thumbnailHandle >= 0) own += track.thumbnailHandle
            }
            assertEquals(first, person.firstSeenUs)
            assertEquals(visible, person.visibleUs)
            if (index > 0) assertTrue("people are ordered by first appearance", person.firstSeenUs >= result.people[index - 1].firstSeenUs)
            if (own.isEmpty()) assertEquals(-1, person.thumbnailHandle) else assertTrue(person.thumbnailHandle in own)
            if (!grouped) assertEquals(1, person.trackIds.size)
            // Tracks of one person were never detected at the same moment.
            val spans = person.trackIds.map { result.track(it)!!.detectedTimes() }.map { it.first() to it.last() }.sortedBy { it.first }
            for (k in 1 until spans.size) assertTrue("person ${person.id} is in two places at once", spans[k].first > spans[k - 1].second)
        }
        assertEquals("every track has a person", result.tracks.size, owner.size)
    }

    private fun assertCoversADetection(label: String, s: Int, track: FaceTrack, reported: List<Detection>?) {
        assertTrue("$label sample $s is at a detector run", reported != null)
        val left = track.boxes[s * 4] - track.boxes[s * 4 + 2] / 2
        val right = track.boxes[s * 4] + track.boxes[s * 4 + 2] / 2
        val top = track.boxes[s * 4 + 1] - track.boxes[s * 4 + 3] / 2
        val bottom = track.boxes[s * 4 + 1] + track.boxes[s * 4 + 3] / 2
        val eps = 1e-5f
        val covered = reported!!.any { d ->
            d.cx.isFinite() && d.w > 0f && d.h > 0f &&
                max(0f, d.cx - d.w / 2) >= left - eps && min(1f, d.cx + d.w / 2) <= right + eps &&
                max(0f, d.cy - d.h / 2) >= top - eps && min(1f, d.cy + d.h / 2) <= bottom + eps
        }
        assertTrue("$label sample $s at ${track.timesUs[s]} contains no detection of that frame", covered)
    }

    @Test
    fun invariantsHoldOverLongRandomVideos() {
        var tracks = 0
        var cuts = 0
        var scripted = 0
        var people = 0
        for (seed in 1L..6L) {
            val portrait = seed % 3 == 0L
            val scan = randomScan(seed, if (portrait) 1080 else 1920, if (portrait) 1920 else 1080, AnalysisOptions.STANDARD, 4000)
            val result = scan.finish(scan.frameTimes.last() + 33_333)
            checkInvariants(scan, result, grouped = true)
            tracks += result.tracks.size
            cuts += result.cutsUs.size
            scripted += scan.scriptedCuts
            people += result.people.size
            assertTrue("seed $seed: ${result.cutsUs.size} cuts found, ${scan.scriptedCuts} made", result.cutsUs.size <= scan.scriptedCuts)
        }
        println("random videos: $tracks tracks, $people people, $cuts of $scripted cuts found")
        assertTrue("the random videos produced tracks: $tracks", tracks > 100)
        assertTrue("most cuts are found: $cuts of $scripted", cuts > 20 && cuts >= scripted * 9 / 10)
        assertTrue("grouping merged some tracks: $people people for $tracks tracks", people < tracks)
    }

    @Test
    fun invariantsHoldWithoutGroupingAndAtOtherCadences() {
        val ungrouped = randomScan(21, 1920, 1080, AnalysisOptions(groupPeople = false), 3000)
        checkInvariants(ungrouped, ungrouped.finish(ungrouped.frameTimes.last() + 1), grouped = false)
        assertEquals(0, ungrouped.embedder!!.embedCalls)

        val thorough = randomScan(22, 3840, 2160, AnalysisOptions.THOROUGH, 3000)
        checkInvariants(thorough, thorough.finish(thorough.frameTimes.last() + 1), grouped = true)

        val sparse = randomScan(23, 1280, 720, AnalysisOptions(detectorLongSide = 320, maxDetectionsPerSecond = 4f), 3000)
        checkInvariants(sparse, sparse.finish(0), grouped = true)
    }

    @Test
    fun sameInputGivesTheSameResult() {
        val codec = BinaryAnalysisResultCodec()
        fun run(): ByteArray {
            val scan = randomScan(5, 1920, 1080, AnalysisOptions.STANDARD, 1500)
            val out = java.io.ByteArrayOutputStream()
            codec.write(scan.finish(scan.frameTimes.last() + 1), out)
            return out.toByteArray()
        }
        assertArrayEquals(run(), run())
    }
}
