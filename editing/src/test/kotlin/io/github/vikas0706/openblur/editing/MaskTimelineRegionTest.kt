package io.github.vikas0706.openblur.editing

import io.github.vikas0706.openblur.core.EditOps
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.ManualRegion
import io.github.vikas0706.openblur.core.MaskAppearance
import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.MaskStyle
import io.github.vikas0706.openblur.core.MaskTimeline
import io.github.vikas0706.openblur.core.RegionKeyframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskTimelineRegionTest {
    private val durationUs = 10_000_000L

    private fun areaTimeline(vararg areas: ManualRegion, frameWidth: Int = 1920, frameHeight: Int = 1080): MaskTimeline =
        timeline(emptyList(), EditState(regions = areas.toList()), frameWidth, frameHeight, durationUs)

    private fun assertMatches(expected: RegionKeyframe, mask: Mask, aspect: Float) {
        assertEquals(expected.cx, mask.cx, 0f)
        assertEquals(expected.cy, mask.cy, 0f)
        assertEquals(expected.w * 0.5f * aspect, mask.halfW, 0f)
        assertEquals(expected.h * 0.5f, mask.halfH, 0f)
        assertEquals(0f, mask.rotation, 0f)
    }

    @Test
    fun anAreaWithOneKeyframeStaysPutWhileItIsActive() {
        val area = ManualRegion(
            4, MaskAppearance(MaskStyle.MOSAIC, MaskShape.RECT, strength = 0.4f, solidColor = 0xFF00FF00.toInt()),
            2_000_000L, 6_000_000L, listOf(RegionKeyframe(5_000_000L, 0.25f, 0.75f, 0.30f, 0.20f)),
        )
        val timeline = areaTimeline(area)

        for (timeUs in longArrayOf(2_000_000L, 3_333_333L, 5_000_000L, 5_999_999L, 6_000_000L)) {
            val mask = timeline.at(timeUs).single()
            assertEquals(0.25f, mask.cx, 0f)
            assertEquals(0.75f, mask.cy, 0f)
            assertEquals(0.15f * 1920f / 1080f, mask.halfW, TOLERANCE)
            assertEquals(0.10f, mask.halfH, TOLERANCE)
            assertEquals(0f, mask.rotation, 0f)
            assertEquals(MaskBatch.KIND_REGION, mask.kind)
            assertEquals(4, mask.sourceId)
            assertEquals(MaskStyle.MOSAIC, mask.style)
            assertEquals(MaskShape.RECT, mask.shape)
            assertEquals(0.4f, mask.strength, 0f)
            assertEquals(0xFF00FF00.toInt(), mask.color)
            assertFalse(mask.hidden)
        }
    }

    @Test
    fun anAreaIsDrawnFromItsStartToItsEndInclusive() {
        val area = ManualRegion(1, MaskAppearance(), 2_000_000L, 6_000_000L, listOf(RegionKeyframe(0L, 0.5f, 0.5f, 0.2f, 0.2f)))
        val timeline = areaTimeline(area)

        assertTrue(timeline.at(1_999_999L).isEmpty())
        assertEquals(1, timeline.at(2_000_000L).size)
        assertEquals(1, timeline.at(6_000_000L).size)
        assertTrue(timeline.at(6_000_001L).isEmpty())
        assertTrue(timeline.at(0L).isEmpty())
    }

    @Test
    fun halfSizesAreInFrameHeightsOnWideAndTallFrames() {
        val look = MaskAppearance(shape = MaskShape.RECT)
        val area = ManualRegion(1, look, 0L, durationUs, listOf(RegionKeyframe(0L, 0.5f, 0.5f, 0.40f, 0.10f)))

        val wide = areaTimeline(area).at(0L).single()
        val tall = areaTimeline(area, frameWidth = 1080, frameHeight = 1920).at(0L).single()

        assertEquals(0.20f * 16f / 9f, wide.halfW, TOLERANCE)
        assertEquals(0.20f * 9f / 16f, tall.halfW, TOLERANCE)
        assertEquals(0.05f, wide.halfH, TOLERANCE)
        assertEquals(0.05f, tall.halfH, TOLERANCE)
        assertTrue(wide.contains(0.5f + 0.19f, 0.5f + 0.04f, 1920, 1080))
        assertFalse(wide.contains(0.5f + 0.21f, 0.5f, 1920, 1080))
        assertTrue(tall.contains(0.5f - 0.19f, 0.5f - 0.04f, 1080, 1920))
        assertFalse(tall.contains(0.5f, 0.5f + 0.06f, 1080, 1920))
    }

    @Test
    fun anAreaWithSeveralKeyframesMovesBetweenThemAndHoldsOutsideThem() {
        val area = ManualRegion(
            2, MaskAppearance(shape = MaskShape.OVAL), 0L, durationUs,
            listOf(
                RegionKeyframe(2_000_000L, 0.20f, 0.20f, 0.10f, 0.10f),
                RegionKeyframe(4_000_000L, 0.60f, 0.40f, 0.30f, 0.20f),
                RegionKeyframe(8_000_000L, 0.60f, 0.80f, 0.10f, 0.40f),
            ),
        )
        val timeline = areaTimeline(area)
        val aspect = 1920f / 1080f

        fun rect(timeUs: Long): FloatArray {
            val mask = timeline.at(timeUs).single()
            return floatArrayOf(mask.cx, mask.cy, mask.halfW * 2f / aspect, mask.halfH * 2f)
        }

        fun assertRect(timeUs: Long, cx: Float, cy: Float, w: Float, h: Float) {
            val actual = rect(timeUs)
            assertEquals(cx, actual[0], TOLERANCE)
            assertEquals(cy, actual[1], TOLERANCE)
            assertEquals(w, actual[2], TOLERANCE)
            assertEquals(h, actual[3], TOLERANCE)
        }

        assertRect(0L, 0.20f, 0.20f, 0.10f, 0.10f)
        assertRect(1_999_999L, 0.20f, 0.20f, 0.10f, 0.10f)
        assertRect(2_000_000L, 0.20f, 0.20f, 0.10f, 0.10f)
        assertRect(3_000_000L, 0.40f, 0.30f, 0.20f, 0.15f)
        assertRect(4_000_000L, 0.60f, 0.40f, 0.30f, 0.20f)
        assertRect(5_000_000L, 0.60f, 0.50f, 0.25f, 0.25f)
        assertRect(7_000_000L, 0.60f, 0.70f, 0.15f, 0.35f)
        assertRect(8_000_000L, 0.60f, 0.80f, 0.10f, 0.40f)
        assertRect(durationUs, 0.60f, 0.80f, 0.10f, 0.40f)
        assertEquals(MaskShape.OVAL, timeline.at(3_000_000L).single().shape)
    }

    @Test
    fun theDrawnAreaIsExactlyTheRectangleTheEditorShows() {
        val area = ManualRegion(
            2, MaskAppearance(), 1_000_000L, 9_000_000L,
            listOf(
                RegionKeyframe(1_500_000L, 0.13f, 0.27f, 0.11f, 0.19f),
                RegionKeyframe(2_250_000L, 0.61f, 0.43f, 0.37f, 0.23f),
                RegionKeyframe(2_300_000L, 0.59f, 0.47f, 0.31f, 0.29f),
                RegionKeyframe(7_700_000L, 0.07f, 0.83f, 0.17f, 0.41f),
            ),
        )
        val factory = DefaultMaskTimelineFactory()
        val timeline = areaTimeline(area)
        val aspect = 1920f / 1080f

        var timeUs = 1_000_000L
        while (timeUs <= 9_000_000L) {
            assertMatches(factory.regionAt(area, timeUs), timeline.at(timeUs).single(), aspect)
            timeUs += 12_345L
        }
        for (frame in area.keyframes) {
            assertMatches(factory.regionAt(area, frame.timeUs), timeline.at(frame.timeUs).single(), aspect)
            assertMatches(factory.regionAt(area, frame.timeUs - 1), timeline.at(frame.timeUs - 1).single(), aspect)
            assertMatches(factory.regionAt(area, frame.timeUs + 1), timeline.at(frame.timeUs + 1).single(), aspect)
        }
    }

    @Test
    fun areasBuiltThroughEditOpsFollowTheirStartEndAndKeyframes() {
        var (edit, id) = EditOps.addRegion(EditState(), 3_000_000L, durationUs, MaskAppearance(shape = MaskShape.RECT), 0.3f, 0.3f, 0.2f, 0.2f)
        edit = EditOps.placeRegion(edit, id, 6_000_000L, 0.7f, 0.5f, 0.4f, 0.2f, asKeyframe = true)
        edit = EditOps.setRegionStart(edit, id, 1_000_000L)
        edit = EditOps.setRegionEnd(edit, id, 8_000_000L)
        val timeline = timeline(emptyList(), edit, durationUs = durationUs)

        assertTrue(timeline.at(999_999L).isEmpty())
        assertEquals(0.3f, timeline.at(1_000_000L).single().cx, 0f)
        assertEquals(0.5f, timeline.at(4_500_000L).single().cx, TOLERANCE)
        assertEquals(0.4f, timeline.at(4_500_000L).single().cy, TOLERANCE)
        assertEquals(0.15f, timeline.at(4_500_000L).single().halfW * 1080f / 1920f, TOLERANCE)
        assertEquals(0.7f, timeline.at(8_000_000L).single().cx, 0f)
        assertTrue(timeline.at(8_000_001L).isEmpty())
        assertFalse(timeline.isEmpty)

        assertTrue(timeline(emptyList(), EditOps.removeRegion(edit, id), durationUs = durationUs).isEmpty)
    }

    @Test
    fun areasIgnoreScaleAndAreNeverHidden() {
        val plain = ManualRegion(1, MaskAppearance(scale = 1f), 0L, durationUs, listOf(RegionKeyframe(0L, 0.5f, 0.5f, 0.2f, 0.2f)))
        val scaled = plain.copy(id = 2, appearance = MaskAppearance(scale = 1.8f))
        val timeline = timeline(
            listOf(stillTrack(1, 0L, durationUs)),
            EditState(visibleTrackIds = setOf(1), regions = listOf(plain, scaled)),
            durationUs = durationUs,
        )

        val drawn = timeline.at(1_000_000L)
        assertEquals(listOf(1, 2), drawn.map { it.sourceId })
        assertEquals(drawn[0].halfW, drawn[1].halfW, 0f)
        assertEquals(drawn[0].halfH, drawn[1].halfH, 0f)

        val all = timeline.at(1_000_000L, includeHidden = true)
        assertEquals(listOf(true, false, false), all.map { it.hidden })
        assertEquals(listOf(MaskBatch.KIND_FACE, MaskBatch.KIND_REGION, MaskBatch.KIND_REGION), all.map { it.kind })
    }

    @Test
    fun anAreaAskedToUseTheEyeBandIsDrawnAsARectangle() {
        val area = ManualRegion(1, MaskAppearance(shape = MaskShape.EYES), 0L, durationUs, listOf(RegionKeyframe(0L, 0.5f, 0.5f, 0.2f, 0.2f)))

        assertEquals(MaskShape.RECT, areaTimeline(area).at(0L).single().shape)
    }

    @Test
    fun anAreaWithoutKeyframesIsIgnored() {
        val broken = ManualRegion(1, MaskAppearance(), 0L, durationUs, emptyList())
        val timeline = areaTimeline(broken)

        assertTrue(timeline.at(1_000_000L).isEmpty())
        assertTrue(timeline.isEmpty)
    }

    @Test
    fun areasOverlapInTimeIndependently() {
        val early = ManualRegion(1, MaskAppearance(), 0L, 4_000_000L, listOf(RegionKeyframe(0L, 0.2f, 0.2f, 0.1f, 0.1f)))
        val late = ManualRegion(2, MaskAppearance(), 3_000_000L, 9_000_000L, listOf(RegionKeyframe(0L, 0.8f, 0.8f, 0.1f, 0.1f)))
        val timeline = areaTimeline(early, late)

        assertEquals(listOf(1), timeline.at(1_000_000L).map { it.sourceId })
        assertEquals(listOf(1, 2), timeline.at(3_500_000L).map { it.sourceId })
        assertEquals(listOf(2), timeline.at(5_000_000L).map { it.sourceId })
        assertTrue(timeline.at(9_500_000L).isEmpty())
    }
}
