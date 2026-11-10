package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.FaceTrack
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Handheld footage in miniature: people drift around, the camera shakes, the detector jitters, misses and doubts. */
class TrackingQualityTest {
    private class Walker(val identity: Int, var x: Float, var y: Float, var vx: Float, var vy: Float, val size: Float)

    private class Outcome(
        val result: AnalysisResult,
        /** For every detector run, where each person in view truly was and what the detector reported. */
        val truth: Map<Long, List<Face>>,
        val seen: Map<Long, List<Face>>,
    )

    private fun film(seed: Long, people: Int, frames: Int, shake: Float, missChance: Float, weakChance: Float): Outcome {
        val random = Random(seed)
        val scan = Scan()
        val walkers = List(people) {
            Walker(it + 1, 300f + random.nextFloat() * 1300f, 250f + random.nextFloat() * 600f, 0f, 0f, 40f + random.nextFloat() * 140f)
        }
        val truth = HashMap<Long, List<Face>>()
        val seen = HashMap<Long, List<Face>>()
        var cameraX = 0f
        var cameraY = 0f
        for (i in 0 until frames) {
            cameraX = cameraX * 0.8f + (random.nextFloat() - 0.5f) * 2f * shake
            cameraY = cameraY * 0.8f + (random.nextFloat() - 0.5f) * 2f * shake
            val real = ArrayList<Face>()
            val reported = ArrayList<Face>()
            for (w in walkers) {
                w.vx = (w.vx + (random.nextFloat() - 0.5f) * 1.5f).coerceIn(-8f, 8f)
                w.vy = (w.vy + (random.nextFloat() - 0.5f) * 1.0f).coerceIn(-5f, 5f)
                w.x += w.vx
                w.y += w.vy
                if (w.x < 150f || w.x > 1770f) w.vx = -w.vx
                if (w.y < 150f || w.y > 930f) w.vy = -w.vy
                val x = w.x + cameraX
                val y = w.y + cameraY
                real += Face(w.identity, x, y, w.size)
                if (random.nextFloat() < missChance) continue
                val jitter = w.size * 0.04f
                val score = if (random.nextFloat() < weakChance) 0.2f + random.nextFloat() * 0.3f else 0.62f + random.nextFloat() * 0.35f
                reported += Face(
                    w.identity,
                    x + (random.nextFloat() - 0.5f) * 2f * jitter,
                    y + (random.nextFloat() - 0.5f) * 2f * jitter,
                    w.size * (1f + (random.nextFloat() - 0.5f) * 0.1f),
                    score,
                )
            }
            // People crossing in front of each other: the larger face is the nearer one and hides the other.
            val hidden = real.filter { a ->
                real.any { b -> b.size > a.size && hypot(a.x - b.x, a.y - b.y) < 0.45f * (a.size + b.size) }
            }.map { it.identity }
            val kept = reported.filter { it.identity !in hidden }
            scan.frame(at30(i), kept)
            truth[at30(i)] = real.filter { it.identity !in hidden }
            seen[at30(i)] = kept
        }
        return Outcome(scan.finish(at30(frames)), truth, seen)
    }

    /** The person whose reported box a detected sample came from. */
    private fun identityAt(track: FaceTrack, s: Int, seen: List<Face>): Int {
        val cx = track.boxes[s * 4] * 1920
        val cy = track.boxes[s * 4 + 1] * 1080
        return seen.minByOrNull { hypot(it.x - cx, it.y - cy) }!!.identity
    }

    private class Score(var tracks: Int = 0, var mixed: Int = 0, var samples: Int = 0, var wrong: Int = 0, var uncovered: Int = 0, var visible: Int = 0)

    private fun judge(outcome: Outcome, score: Score) {
        val result = outcome.result
        score.tracks += result.tracks.size
        for (track in result.tracks) {
            val counts = HashMap<Int, Int>()
            for (s in 0 until track.sampleCount) {
                if (track.sampleKinds[s] != FaceTrack.SAMPLE_DETECTED) continue
                val identity = identityAt(track, s, outcome.seen[track.timesUs[s]]!!)
                counts[identity] = (counts[identity] ?: 0) + 1
            }
            val total = counts.values.sum()
            val main = counts.values.max()
            score.samples += total
            score.wrong += total - main
            if (main < total) score.mixed++
        }
        // Coverage: at every run, is each person's true face center inside some stored box (interpolated in time)?
        for ((time, faces) in outcome.truth) {
            for (face in faces) {
                score.visible++
                val covered = result.tracks.any { track ->
                    if (time < track.startUs || time > track.endUs) return@any false
                    var s = 0
                    while (s + 1 < track.sampleCount && track.timesUs[s + 1] <= time) s++
                    val next = minOf(s + 1, track.sampleCount - 1)
                    val span = (track.timesUs[next] - track.timesUs[s]).toFloat()
                    val f = if (span > 0f) (time - track.timesUs[s]) / span else 0f
                    fun at(k: Int) = track.boxes[s * 4 + k] + (track.boxes[next * 4 + k] - track.boxes[s * 4 + k]) * f
                    abs(face.x / 1920 - at(0)) <= at(2) / 2 && abs(face.y / 1080 - at(1)) <= at(3) / 2
                }
                if (!covered) score.uncovered++
            }
        }
    }

    @Test
    fun steadyHandsFewPeople() {
        val score = Score()
        for (seed in 1L..4L) judge(film(seed, people = 3, frames = 1500, shake = 3f, missChance = 0.05f, weakChance = 0.1f), score)
        println("steady: ${score.tracks} tracks for 12 people, ${score.wrong} of ${score.samples} samples on the wrong person, ${score.uncovered} of ${score.visible} faces uncovered")
        // A person hidden behind another for longer than the gap limit comes back as a new track.
        assertTrue("tracks fragment: ${score.tracks} for 12 people", score.tracks <= 24)
        assertTrue("identity mix: ${score.wrong} of ${score.samples}", score.wrong <= score.samples / 500)
        assertTrue("uncovered faces: ${score.uncovered} of ${score.visible}", score.uncovered <= score.visible / 200)
    }

    @Test
    fun shakyCameraUnreliableDetector() {
        val score = Score()
        for (seed in 11L..14L) judge(film(seed, people = 6, frames = 1500, shake = 14f, missChance = 0.2f, weakChance = 0.3f), score)
        println("shaky: ${score.tracks} tracks for 24 people, ${score.wrong} of ${score.samples} samples on the wrong person, ${score.uncovered} of ${score.visible} faces uncovered")
        assertTrue("tracks fragment: ${score.tracks} for 24 people", score.tracks <= 200)
        assertTrue("identity mix: ${score.wrong} of ${score.samples}", score.wrong <= score.samples / 200)
        assertTrue("uncovered faces: ${score.uncovered} of ${score.visible}", score.uncovered <= score.visible / 100)
    }

    @Test
    fun onePersonStaysOneTrackUnderModerateShake() {
        val score = Score()
        for (seed in 31L..46L) judge(film(seed, people = 1, frames = 900, shake = 6f, missChance = 0.25f, weakChance = 0.35f), score)
        println("alone: ${score.tracks} tracks for 16 people, ${score.uncovered} of ${score.visible} faces uncovered")
        assertTrue("tracks fragment: ${score.tracks} for 16 people", score.tracks <= 18)
        assertEquals(0, score.uncovered)
    }

    @Test
    fun heavyShakeFragmentsTracksButFacesStayCovered() {
        val score = Score()
        for (seed in 31L..38L) judge(film(seed, people = 1, frames = 900, shake = 20f, missChance = 0.25f, weakChance = 0.35f), score)
        println("heavy shake: ${score.tracks} tracks for 8 people, ${score.uncovered} of ${score.visible} faces uncovered")
        // No motion model predicts a jolt of half a face width per frame; what must hold is that the face stays blurred.
        assertTrue("uncovered faces: ${score.uncovered} of ${score.visible}", score.uncovered <= score.visible / 100)
    }

    @Test
    fun peopleAreGroupedDespiteFragmentedTracks() {
        val outcome = film(21, people = 5, frames = 2400, shake = 10f, missChance = 0.25f, weakChance = 0.3f)
        val result = outcome.result
        println("grouping: ${result.tracks.size} tracks, ${result.people.size} people for 5")
        // Every track of a person carries that person's embedding, so fragments end up together.
        assertTrue("${result.people.size} people for 5", result.people.size in 5..6)
        assertEquals(result.tracks.size, result.people.sumOf { it.trackIds.size })
    }
}
