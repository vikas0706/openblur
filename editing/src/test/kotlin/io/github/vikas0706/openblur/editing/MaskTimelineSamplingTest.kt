package io.github.vikas0706.openblur.editing

import io.github.vikas0706.openblur.core.EditOps
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.FaceTrack
import io.github.vikas0706.openblur.core.ManualRegion
import io.github.vikas0706.openblur.core.MaskAppearance
import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.MaskStyle
import io.github.vikas0706.openblur.core.RegionKeyframe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskTimelineSamplingTest {
    private val rect = MaskAppearance(shape = MaskShape.RECT)

    /** With a RECT appearance at scale 1 on a 16:9 frame, the box can be read back from the mask. */
    private fun movingTrack(): FaceTrack = track(
        7,
        Sample(1_000_000L, 0.20f, 0.30f, 0.10f, 0.20f),
        Sample(2_000_000L, 0.40f, 0.50f, 0.20f, 0.40f),
        Sample(4_000_000L, 0.80f, 0.10f, 0.10f, 0.10f),
    )

    private fun assertRectFor(mask: Mask, cx: Float, cy: Float, w: Float, h: Float) {
        assertEquals(cx, mask.cx, TOLERANCE)
        assertEquals(cy - 0.05f * h, mask.cy, TOLERANCE)
        assertEquals(0.62f * w * 1920f / 1080f, mask.halfW, TOLERANCE)
        assertEquals(0.72f * h, mask.halfH, TOLERANCE)
        assertEquals(0f, mask.rotation, 0f)
    }

    @Test
    fun atASampleTheMaskComesFromThatSample() {
        val timeline = timeline(listOf(movingTrack()), EditState(appearance = rect))

        assertRectFor(timeline.at(1_000_000L).single(), 0.20f, 0.30f, 0.10f, 0.20f)
        assertRectFor(timeline.at(2_000_000L).single(), 0.40f, 0.50f, 0.20f, 0.40f)
        assertRectFor(timeline.at(4_000_000L).single(), 0.80f, 0.10f, 0.10f, 0.10f)
    }

    @Test
    fun betweenSamplesTheBoxMovesLinearly() {
        val timeline = timeline(listOf(movingTrack()), EditState(appearance = rect))

        assertRectFor(timeline.at(1_500_000L).single(), 0.30f, 0.40f, 0.15f, 0.30f)
        assertRectFor(timeline.at(1_250_000L).single(), 0.25f, 0.35f, 0.125f, 0.25f)
        assertRectFor(timeline.at(3_500_000L).single(), 0.70f, 0.20f, 0.125f, 0.175f)
        assertRectFor(timeline.at(2_000_001L).single(), 0.40f, 0.50f, 0.20f, 0.40f)
    }

    @Test
    fun theTrackCoversItsClosedIntervalAndNothingElse() {
        val timeline = timeline(listOf(movingTrack()))

        assertTrue(timeline.at(999_999L).isEmpty())
        assertEquals(1, timeline.at(1_000_000L).size)
        assertEquals(1, timeline.at(4_000_000L).size)
        assertTrue(timeline.at(4_000_001L).isEmpty())
        assertTrue(timeline.at(0L).isEmpty())
        assertTrue(timeline.at(-5L).isEmpty())
        assertTrue(timeline.at(Long.MAX_VALUE).isEmpty())
        assertTrue(timeline.at(Long.MIN_VALUE).isEmpty())
    }

    @Test
    fun aMaskCarriesItsTrackAndAppearance() {
        val look = MaskAppearance(MaskStyle.SOLID, MaskShape.OVAL, strength = 0.35f, scale = 1.2f, solidColor = 0xFF112233.toInt())
        val mask = timeline(listOf(movingTrack()), EditState(appearance = look)).at(1_000_000L).single()

        assertEquals(MaskStyle.SOLID, mask.style)
        assertEquals(MaskShape.OVAL, mask.shape)
        assertEquals(0.35f, mask.strength, 0f)
        assertEquals(0xFF112233.toInt(), mask.color)
        assertEquals(MaskBatch.KIND_FACE, mask.kind)
        assertEquals(7, mask.sourceId)
        assertFalse(mask.hidden)
    }

    @Test
    fun visibleFacesAreLeftOutUnlessHiddenOnesAreAskedFor() {
        val tracks = listOf(stillTrack(1, 0L, 5_000_000L), stillTrack(2, 0L, 5_000_000L), stillTrack(3, 0L, 5_000_000L))
        val timeline = timeline(tracks, EditState(visibleTrackIds = setOf(2)))

        val drawn = timeline.at(1_000_000L)
        assertEquals(listOf(1, 3), drawn.map { it.sourceId })
        assertTrue(drawn.none { it.hidden })

        val all = timeline.at(1_000_000L, includeHidden = true)
        assertEquals(listOf(1, 2, 3), all.map { it.sourceId })
        assertEquals(listOf(false, true, false), all.map { it.hidden })
        assertEquals("a hidden face keeps its geometry for outlines", all[0].copy(sourceId = 2, hidden = true), all[1])
    }

    @Test
    fun aPersonOverrideAppliesToEveryTrackOfThatPersonOnly() {
        val tracks = listOf(
            stillTrack(1, 0L, 5_000_000L, personId = 10),
            stillTrack(2, 0L, 5_000_000L, personId = 20),
            stillTrack(3, 0L, 5_000_000L, personId = 10),
        )
        val shared = MaskAppearance(MaskStyle.BLUR, MaskShape.OVAL, strength = 0.7f)
        val override = MaskAppearance(MaskStyle.MOSAIC, MaskShape.RECT, strength = 0.2f, solidColor = 0xFFFF0000.toInt())
        val edit = EditOps.setPersonAppearance(EditState(appearance = shared), 10, override)

        val masks = timeline(tracks, edit).at(2_000_000L)

        assertEquals(listOf(MaskStyle.MOSAIC, MaskStyle.BLUR, MaskStyle.MOSAIC), masks.map { it.style })
        assertEquals(listOf(MaskShape.RECT, MaskShape.OVAL, MaskShape.RECT), masks.map { it.shape })
        assertEquals(listOf(0.2f, 0.7f, 0.2f), masks.map { it.strength })
        assertEquals(0xFFFF0000.toInt(), masks[0].color)

        val cleared = EditOps.setPersonAppearance(edit, 10, null)
        assertTrue(timeline(tracks, cleared).at(2_000_000L).all { it.style == MaskStyle.BLUR })
    }

    @Test
    fun facesComeFirstInTrackOrderThenAreasInListOrder() {
        val tracks = listOf(stillTrack(30, 0L, 9_000_000L), stillTrack(10, 0L, 9_000_000L), stillTrack(20, 2_000_000L, 9_000_000L))
        val areas = listOf(
            ManualRegion(5, MaskAppearance(), 0L, 9_000_000L, listOf(RegionKeyframe(0L, 0.5f, 0.5f, 0.2f, 0.2f))),
            ManualRegion(4, MaskAppearance(), 0L, 9_000_000L, listOf(RegionKeyframe(0L, 0.2f, 0.2f, 0.1f, 0.1f))),
        )
        val timeline = timeline(tracks, EditState(regions = areas))

        val masks = timeline.at(3_000_000L)
        assertEquals(listOf(30, 10, 20, 5, 4), masks.map { it.sourceId })
        assertEquals(
            listOf(MaskBatch.KIND_FACE, MaskBatch.KIND_FACE, MaskBatch.KIND_FACE, MaskBatch.KIND_REGION, MaskBatch.KIND_REGION),
            masks.map { it.kind },
        )
        assertEquals(listOf(30, 10, 5, 4), timeline.at(1_000_000L).map { it.sourceId })
    }

    @Test
    fun sampleReplacesWhatTheBatchHeld() {
        val timeline = timeline(listOf(stillTrack(1, 0L, 1_000_000L), stillTrack(2, 0L, 1_000_000L)))
        val batch = MaskBatch()

        timeline.sample(500_000L, batch)
        assertEquals(2, batch.count)
        timeline.sample(2_000_000L, batch)
        assertEquals(0, batch.count)
        timeline.sample(1_000_000L, batch, includeHidden = true)
        assertEquals(2, batch.count)
    }

    @Test
    fun theBatchGrowsToHoldAHundredSimultaneousMasks() {
        val tracks = List(100) { stillTrack(it + 1, 0L, 2_000_000L, cx = it / 100f) }
        val timeline = timeline(tracks)
        val batch = MaskBatch()
        val initialCapacity = batch.style.size
        assertTrue(initialCapacity < 100)

        timeline.sample(1_000_000L, batch)

        assertEquals(100, batch.count)
        assertTrue(batch.style.size >= 100)
        assertTrue(batch.geometry.size >= 100 * MaskBatch.GEOMETRY_STRIDE)
        val masks = batch.masks()
        assertEquals(List(100) { it + 1 }, masks.map { it.sourceId })
        masks.forEachIndexed { i, mask -> assertEquals(i / 100f, mask.cx, 0f) }

        val grown = batch.geometry
        timeline.sample(1_500_000L, batch)
        assertTrue("a batch that is large enough is reused as is", grown === batch.geometry)
    }

    @Test
    fun aSmallBatchGrowsToo() {
        val tracks = List(9) { stillTrack(it, 0L, 1_000L) }
        val batch = MaskBatch(initialCapacity = 1)

        timeline(tracks).sample(500L, batch)

        assertEquals(List(9) { it }, batch.masks().map { it.sourceId })
    }

    @Test
    fun durationComesFromTheScan() {
        assertEquals(123_456_789L, timeline(emptyList(), durationUs = 123_456_789L).durationUs)
    }

    @Test
    fun isEmptyWhenNothingIsEverDrawn() {
        val face = stillTrack(1, 0L, 1_000_000L)
        val other = stillTrack(2, 0L, 1_000_000L)
        val area = ManualRegion(1, MaskAppearance(), 0L, 1_000_000L, listOf(RegionKeyframe(0L, 0.5f, 0.5f, 0.2f, 0.2f)))

        assertTrue(timeline(emptyList()).isEmpty)
        assertFalse(timeline(listOf(face)).isEmpty)
        assertTrue(timeline(listOf(face), EditState(visibleTrackIds = setOf(1))).isEmpty)
        assertFalse(timeline(listOf(face, other), EditState(visibleTrackIds = setOf(1))).isEmpty)
        assertTrue(timeline(listOf(face, other), EditState(visibleTrackIds = setOf(1, 2))).isEmpty)
        assertFalse(timeline(emptyList(), EditState(regions = listOf(area))).isEmpty)
        assertFalse(timeline(listOf(face), EditState(visibleTrackIds = setOf(1), regions = listOf(area))).isEmpty)
    }

    @Test
    fun anAreaThatEndsBeforeItStartsDrawsNothing() {
        val never = ManualRegion(1, MaskAppearance(), 2_000_000L, 1_000_000L, listOf(RegionKeyframe(0L, 0.5f, 0.5f, 0.2f, 0.2f)))
        val timeline = timeline(emptyList(), EditState(regions = listOf(never)))

        assertTrue(timeline.isEmpty)
        assertTrue(timeline.at(1_500_000L).isEmpty())
    }

    @Test
    fun allBlurredAfterEveryFaceWasShownIsNotEmptyAgain() {
        val result = scan(listOf(stillTrack(1, 0L, 1_000_000L), stillTrack(2, 0L, 1_000_000L)))
        val factory = DefaultMaskTimelineFactory()
        val shown = EditOps.setAllBlurred(EditState(), result, blurred = false)

        assertTrue(factory.build(result, shown).isEmpty)
        assertFalse(factory.build(result, EditOps.setAllBlurred(shown, result, blurred = true)).isEmpty)
    }

    @Test
    fun aTimelineDoesNotChangeWhenLaterEditsAreMade() {
        val result = scan(listOf(stillTrack(1, 0L, 1_000_000L)))
        val factory = DefaultMaskTimelineFactory()
        val before = EditState()
        val timeline = factory.build(result, before)

        val after = EditOps.setTrackBlurred(before, 1, blurred = false)
        val later = factory.build(result, after)

        assertEquals(1, timeline.at(500_000L).size)
        assertTrue(later.at(500_000L).isEmpty())
    }

    @Test
    fun regionAtIsTheEditOpsAnswer() {
        val area = ManualRegion(
            3, MaskAppearance(), 0L, 9_000_000L,
            listOf(RegionKeyframe(1_000_000L, 0.2f, 0.3f, 0.1f, 0.2f), RegionKeyframe(3_000_000L, 0.6f, 0.7f, 0.3f, 0.1f)),
        )
        val factory = DefaultMaskTimelineFactory()

        for (timeUs in longArrayOf(-1L, 0L, 1_000_000L, 1_700_000L, 3_000_000L, 20_000_000L)) {
            assertEquals(EditOps.regionAt(area, timeUs), factory.regionAt(area, timeUs))
        }
    }

    @Test
    fun aTrackWithoutSamplesIsIgnored() {
        val empty = FaceTrack(9, 9, LongArray(0), FloatArray(0), FloatArray(0), ByteArray(0), -1, 0f)
        val timeline = timeline(listOf(empty, stillTrack(1, 0L, 1_000_000L)))

        assertEquals(listOf(1), timeline.at(0L).map { it.sourceId })
        assertFalse(timeline.isEmpty)
        assertTrue(timeline(listOf(empty)).isEmpty)
    }

    @Test
    fun aSingleSampleTrackIsDrawnAtThatInstant() {
        val timeline = timeline(listOf(track(1, Sample(500L, 0.5f, 0.5f, 0.1f, 0.1f))))

        assertEquals(1, timeline.at(500L).size)
        assertTrue(timeline.at(499L).isEmpty())
        assertTrue(timeline.at(501L).isEmpty())
    }

    @Test
    fun strengthAndScaleOutsideTheirRangesAreMadeDrawable() {
        val tracks = listOf(stillTrack(1, 0L, 1_000L, personId = 1), stillTrack(2, 0L, 1_000L, personId = 2))
        val edit = EditState(
            appearance = MaskAppearance(shape = MaskShape.RECT, strength = Float.NaN, scale = Float.NaN),
            personAppearance = mapOf(2 to MaskAppearance(shape = MaskShape.RECT, strength = 7f, scale = 50f)),
        )

        val masks = timeline(tracks, edit).at(0L)

        assertEquals(1f, masks[0].strength, 0f)
        assertEquals(0.72f * 0.3f, masks[0].halfH, TOLERANCE)
        assertEquals(1f, masks[1].strength, 0f)
        assertEquals(0.72f * 0.3f * MaskAppearance.MAX_SCALE, masks[1].halfH, TOLERANCE)
    }

    @Test
    fun manyThreadsSamplingAtOnceGetTheSingleThreadedAnswers() {
        val random = Random(42)
        val tracks = List(400) { id ->
            val start = random.nextLong(0L, 50_000_000L)
            val count = random.nextInt(2, 40)
            val samples = Array(count) { i ->
                Sample(
                    start + i * 100_000L,
                    random.nextFloat(), random.nextFloat(), 0.05f + random.nextFloat() * 0.2f, 0.05f + random.nextFloat() * 0.2f,
                    if (random.nextInt(4) == 0) null else floatArrayOf(random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat()),
                )
            }
            track(id, *samples, personId = id % 7)
        }
        val areas = List(5) { id ->
            ManualRegion(
                id + 1, MaskAppearance(shape = MaskShape.RECT), id * 5_000_000L, 40_000_000L + id * 3_000_000L,
                List(4) { k -> RegionKeyframe(k * 12_000_000L, random.nextFloat(), random.nextFloat(), 0.1f, 0.1f) },
            )
        }
        val edit = EditState(
            visibleTrackIds = (0 until 400 step 5).toSet(),
            personAppearance = mapOf(
                1 to MaskAppearance(shape = MaskShape.EYES),
                2 to MaskAppearance(shape = MaskShape.RECT, scale = 1.5f),
            ),
            regions = areas,
        )
        val timeline = timeline(tracks, edit, durationUs = 60_000_000L)

        val times = LongArray(3_000) { random.nextLong(-1_000_000L, 56_000_000L) }
        val reference = MaskBatch()
        val expectedGeometry = arrayOfNulls<FloatArray>(times.size)
        val expectedIds = arrayOfNulls<IntArray>(times.size)
        for (i in times.indices) {
            timeline.sample(times[i], reference, includeHidden = i % 2 == 0)
            expectedGeometry[i] = reference.geometry.copyOf(reference.count * MaskBatch.GEOMETRY_STRIDE)
            expectedIds[i] = reference.sourceId.copyOf(reference.count)
        }
        assertTrue("the scene must put masks on screen", expectedIds.sumOf { it!!.size } > 10_000)

        val threadCount = 8
        val ready = CountDownLatch(threadCount)
        val go = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val workers = List(threadCount) { worker ->
            thread(name = "sampler-$worker") {
                try {
                    val batch = MaskBatch()
                    val order = times.indices.shuffled(Random(worker))
                    ready.countDown()
                    go.await()
                    repeat(4) {
                        for (i in order) {
                            timeline.sample(times[i], batch, includeHidden = i % 2 == 0)
                            assertArrayEquals(expectedIds[i], batch.sourceId.copyOf(batch.count))
                            assertArrayEquals(
                                expectedGeometry[i],
                                batch.geometry.copyOf(batch.count * MaskBatch.GEOMETRY_STRIDE),
                                0f,
                            )
                        }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS))
        go.countDown()
        workers.forEach { it.join(120_000L) }

        assertTrue(workers.none { it.isAlive })
        failure.get()?.let { throw AssertionError("a sampling thread saw a different answer", it) }
    }
}
