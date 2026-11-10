package io.github.vikas0706.openblur.editing

import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.FaceTrack
import io.github.vikas0706.openblur.core.ManualRegion
import io.github.vikas0706.openblur.core.MaskAppearance
import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.RegionKeyframe
import java.lang.management.ManagementFactory
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Five thousand tracks spread over an hour: the load named in the timeline's performance requirement. */
class MaskTimelineScaleTest {
    private val hourUs = 3_600_000_000L
    private val frameUs = 33_333L
    private val frameCount = (hourUs / frameUs).toInt()

    private val tracks: List<FaceTrack> = run {
        val random = Random(2026)
        List(5_000) { id ->
            val lengthUs = random.nextLong(2_000_000L, 20_000_000L)
            val startUs = random.nextLong(0L, hourUs - lengthUs)
            val count = (lengthUs / 100_000L).toInt() + 1
            val boxes = FloatArray(count * 4)
            val eyes = FloatArray(count * 4)
            val cx = random.nextFloat()
            val cy = random.nextFloat()
            for (i in 0 until count) {
                boxes[i * 4] = cx + i * 0.0005f
                boxes[i * 4 + 1] = cy
                boxes[i * 4 + 2] = 0.08f
                boxes[i * 4 + 3] = 0.14f
                eyes[i * 4] = boxes[i * 4] - 0.017f
                eyes[i * 4 + 1] = cy - 0.02f
                eyes[i * 4 + 2] = boxes[i * 4] + 0.017f
                eyes[i * 4 + 3] = cy - 0.015f
            }
            FaceTrack(
                id = id,
                personId = id % 40,
                timesUs = LongArray(count) { startUs + it * (lengthUs / (count - 1)) },
                boxes = boxes,
                eyes = eyes,
                sampleKinds = ByteArray(count),
                thumbnailHandle = -1,
                meanScore = 0.8f,
            )
        }
    }

    private val edit = EditState(
        visibleTrackIds = (0 until 5_000 step 9).toSet(),
        personAppearance = mapOf(
            3 to MaskAppearance(shape = MaskShape.EYES),
            4 to MaskAppearance(shape = MaskShape.RECT, scale = 1.4f),
        ),
        regions = listOf(
            ManualRegion(
                1, MaskAppearance(shape = MaskShape.RECT), 0L, hourUs,
                List(20) { RegionKeyframe(it * 180_000_000L, 0.1f + it * 0.04f, 0.5f, 0.2f, 0.1f) },
            ),
        ),
    )

    private val timeline = DefaultMaskTimelineFactory().build(scan(tracks, durationUs = hourUs), edit) as SnapshotMaskTimeline

    @Test
    fun theIndexedLookupFindsExactlyTheTracksABruteForceScanFinds() {
        val random = Random(7)
        val batch = MaskBatch()
        repeat(4_000) {
            val timeUs = random.nextLong(-1_000_000L, hourUs + 1_000_000L)
            val expected = tracks.filter { timeUs >= it.startUs && timeUs <= it.endUs }.map { it.id }

            timeline.sample(timeUs, batch, includeHidden = true)
            val faces = batch.masks().filter { it.kind == MaskBatch.KIND_FACE }

            assertEquals(expected, faces.map { it.sourceId })
            assertEquals(expected.map { it % 9 == 0 }, faces.map { it.hidden })
        }
    }

    @Test
    fun trackBoundariesAreFoundExactly() {
        val batch = MaskBatch()
        for (track in tracks.take(600)) {
            for (timeUs in longArrayOf(track.startUs, track.endUs)) {
                timeline.sample(timeUs, batch, includeHidden = true)
                assertTrue(batch.masks().any { it.kind == MaskBatch.KIND_FACE && it.sourceId == track.id })
            }
            for (timeUs in longArrayOf(track.startUs - 1, track.endUs + 1)) {
                timeline.sample(timeUs, batch, includeHidden = true)
                assertTrue(batch.masks().none { it.kind == MaskBatch.KIND_FACE && it.sourceId == track.id })
            }
        }
    }

    @Test
    fun aLookupComparesOnlyTheTracksNearItsTime() {
        val batch = MaskBatch()
        var candidates = 0L
        var active = 0L
        var worst = 0
        for (frame in 0 until frameCount) {
            val timeUs = frame * frameUs
            val seen = timeline.candidateCount(timeUs)
            candidates += seen
            if (seen > worst) worst = seen
            timeline.sample(timeUs, batch, includeHidden = true)
            active += batch.count - 1
        }
        val meanCandidates = candidates.toDouble() / frameCount
        val meanActive = active.toDouble() / frameCount
        println("index: %.1f tracks compared per lookup for %.1f active, worst case %d of 5000".format(meanCandidates, meanActive, worst))

        assertTrue("mean candidates $meanCandidates for $meanActive active", meanCandidates < meanActive * 2 + 4)
        assertTrue("worst case $worst", worst < 500)
    }

    @Test
    fun samplingAnHourOfFramesStaysFarInsideAFrameBudget() {
        val batch = MaskBatch()
        var masks = 0L
        repeat(2) {
            for (frame in 0 until frameCount) {
                timeline.sample(frame * frameUs, batch, includeHidden = frame and 1 == 0)
                masks += batch.count
            }
        }

        masks = 0L
        val started = System.nanoTime()
        for (frame in 0 until frameCount) {
            timeline.sample(frame * frameUs, batch, includeHidden = false)
            masks += batch.count
        }
        val nanosPerSample = (System.nanoTime() - started).toDouble() / frameCount
        println(
            "timing: %d samples over 5000 tracks, %.0f ns per sample, %.1f masks per sample"
                .format(frameCount, nanosPerSample, masks.toDouble() / frameCount),
        )

        assertTrue(masks > frameCount)
        // A frame at 60 fps lasts 16.7 ms; this leaves room for a slow, busy build machine.
        assertTrue("$nanosPerSample ns per sample", nanosPerSample < 50_000.0)
    }

    @Test
    fun buildingTheTimelineIsCheapEnoughToRunOnEveryEdit() {
        val factory = DefaultMaskTimelineFactory()
        val result = scan(tracks, durationUs = hourUs)
        repeat(20) { factory.build(result, edit) }

        val rounds = 50
        val started = System.nanoTime()
        repeat(rounds) { factory.build(result, edit) }
        val millis = (System.nanoTime() - started) / 1e6 / rounds
        println("build: %.3f ms for 5000 tracks".format(millis))

        assertTrue("$millis ms per build", millis < 50.0)
    }

    @Test
    fun samplingAllocatesNothingOnceTheBatchHasGrown() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        if (bean == null || !bean.isThreadAllocatedMemorySupported) {
            assumeTrue("needs a JVM that reports per-thread allocation", false)
            return
        }
        val batch = MaskBatch()
        fun sweep() {
            for (frame in 0 until frameCount step 3) {
                timeline.sample(frame * frameUs, batch, includeHidden = frame and 1 == 0)
            }
        }
        repeat(3) { sweep() }
        bean.currentThreadAllocatedBytes

        val before = bean.currentThreadAllocatedBytes
        sweep()
        val allocated = bean.currentThreadAllocatedBytes - before
        println("allocation: %d bytes over %d samples".format(allocated, frameCount / 3))

        // One boxed float per sample would already be more than half a megabyte.
        assertTrue("$allocated bytes allocated", allocated < 4_096)
    }
}
