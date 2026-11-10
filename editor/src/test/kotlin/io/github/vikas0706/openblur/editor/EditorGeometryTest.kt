package io.github.vikas0706.openblur.editor

import io.github.vikas0706.openblur.core.EditOps
import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.MaskStyle
import io.github.vikas0706.openblur.core.RegionKeyframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorGeometryTest {
    private val eps = 1e-4f

    private fun MaskBatch.addMask(
        cx: Float,
        cy: Float,
        halfW: Float,
        halfH: Float,
        shape: MaskShape = MaskShape.OVAL,
        rotation: Float = 0f,
        kind: Int = MaskBatch.KIND_FACE,
        id: Int = 0,
    ) = add(cx, cy, halfW, halfH, rotation, MaskStyle.BLUR, shape, 0.7f, 0, kind, id, false)

    @Test
    fun wideVideoInTallContainerIsLetterboxed() {
        val r = fitVideoRect(1080f, 1920f, 1920, 1080)
        assertEquals(0f, r.left, eps)
        assertEquals(1080f, r.width, eps)
        assertEquals(607.5f, r.height, eps)
        assertEquals(656.25f, r.top, eps)
    }

    @Test
    fun tallVideoInWideContainerIsPillarboxed() {
        val r = fitVideoRect(2000f, 1000f, 1080, 1920)
        assertEquals(1000f, r.height, eps)
        assertEquals(562.5f, r.width, eps)
        assertEquals(718.75f, r.left, eps)
        assertEquals(0f, r.top, eps)
    }

    @Test
    fun sameAspectFillsTheContainer() {
        assertEquals(PxRect(0f, 0f, 800f, 450f), fitVideoRect(800f, 450f, 1920, 1080))
    }

    @Test
    fun emptyContainerOrVideoGivesAnEmptyRect() {
        assertTrue(fitVideoRect(0f, 100f, 1920, 1080).isEmpty)
        assertTrue(fitVideoRect(100f, 100f, 0, 1080).isEmpty)
    }

    @Test
    fun hitTestFindsTheMaskUnderThePoint() {
        val batch = MaskBatch()
        batch.addMask(0.25f, 0.5f, 0.1f, 0.1f, id = 1)
        batch.addMask(0.75f, 0.5f, 0.1f, 0.1f, id = 2)
        assertEquals(0, hitTestMask(batch, 0.25f, 0.5f, 16f / 9f))
        assertEquals(1, hitTestMask(batch, 0.76f, 0.45f, 16f / 9f))
        assertEquals(-1, hitTestMask(batch, 0.5f, 0.5f, 16f / 9f))
    }

    @Test
    fun halfSizesAreFractionsOfTheFrameHeight() {
        val batch = MaskBatch()
        batch.addMask(0.5f, 0.5f, 0.2f, 0.2f, shape = MaskShape.RECT)
        // In a 2:1 frame a half width of 0.2 heights is 0.1 of the width.
        assertEquals(0, hitTestMask(batch, 0.59f, 0.5f, 2f))
        assertEquals(-1, hitTestMask(batch, 0.61f, 0.5f, 2f))
        assertEquals(0, hitTestMask(batch, 0.5f, 0.69f, 2f))
        assertEquals(-1, hitTestMask(batch, 0.5f, 0.71f, 2f))
    }

    @Test
    fun ovalCornersAreOutsideButRectCornersAreInside() {
        val oval = MaskBatch().also { it.addMask(0.5f, 0.5f, 0.2f, 0.2f, shape = MaskShape.OVAL) }
        val rect = MaskBatch().also { it.addMask(0.5f, 0.5f, 0.2f, 0.2f, shape = MaskShape.RECT) }
        val eyes = MaskBatch().also { it.addMask(0.5f, 0.5f, 0.2f, 0.2f, shape = MaskShape.EYES) }
        assertEquals(-1, hitTestMask(oval, 0.68f, 0.68f, 1f))
        assertEquals(0, hitTestMask(rect, 0.68f, 0.68f, 1f))
        assertEquals(0, hitTestMask(eyes, 0.68f, 0.68f, 1f))
    }

    @Test
    fun rotationTurnsTheMaskClockwise() {
        val batch = MaskBatch()
        // A wide, flat rectangle turned a quarter turn becomes tall and narrow.
        batch.addMask(0.5f, 0.5f, 0.3f, 0.05f, shape = MaskShape.RECT, rotation = (Math.PI / 2).toFloat())
        assertEquals(0, hitTestMask(batch, 0.5f, 0.75f, 1f))
        assertEquals(-1, hitTestMask(batch, 0.75f, 0.5f, 1f))

        val tilted = MaskBatch()
        tilted.addMask(0.5f, 0.5f, 0.3f, 0.02f, shape = MaskShape.RECT, rotation = (Math.PI / 4).toFloat())
        // Clockwise on a y-down screen: the right end of the bar points down.
        assertEquals(0, hitTestMask(tilted, 0.65f, 0.65f, 1f))
        assertEquals(-1, hitTestMask(tilted, 0.65f, 0.35f, 1f))
    }

    @Test
    fun theSmallestOverlappingMaskWins() {
        val batch = MaskBatch()
        batch.addMask(0.5f, 0.5f, 0.4f, 0.4f, shape = MaskShape.RECT, kind = MaskBatch.KIND_REGION, id = 7)
        batch.addMask(0.5f, 0.5f, 0.1f, 0.1f, id = 3)
        batch.addMask(0.5f, 0.5f, 0.2f, 0.2f, id = 4)
        assertEquals(1, hitTestMask(batch, 0.5f, 0.5f, 1f))
        assertEquals(2, hitTestMask(batch, 0.5f, 0.65f, 1f))
        assertEquals(0, hitTestMask(batch, 0.5f, 0.85f, 1f))
    }

    @Test
    fun slopOnlyHelpsWhenNothingIsHitExactly() {
        val batch = MaskBatch()
        batch.addMask(0.2f, 0.5f, 0.01f, 0.01f, id = 1)
        batch.addMask(0.5f, 0.5f, 0.2f, 0.2f, id = 2)
        assertEquals(-1, hitTestMask(batch, 0.23f, 0.5f, 1f))
        assertEquals(0, hitTestMask(batch, 0.23f, 0.5f, 1f, slop = 0.03f))
        // Inside the large mask the nearby small one does not steal the tap.
        assertEquals(1, hitTestMask(batch, 0.31f, 0.5f, 1f, slop = 0.12f))
        assertEquals(-1, hitTestMask(MaskBatch(), 0.5f, 0.5f, 1f, slop = 0.1f))
    }

    @Test
    fun regionRectFollowsTheVideoRect() {
        val video = PxRect(100f, 50f, 900f, 500f)
        val r = regionRectPx(RegionKeyframe(0L, 0.5f, 0.5f, 0.25f, 0.2f), video)
        assertEquals(PxRect(400f, 230f, 600f, 320f), r)
    }

    @Test
    fun handlesSitOnTheCornersOfALargeArea() {
        val region = PxRect(200f, 200f, 600f, 500f)
        val c = handleCenters(region, PxRect(0f, 0f, 1000f, 1000f), touch = 96f)
        assertEquals(listOf(400f, 350f, 200f, 200f, 600f, 200f, 200f, 500f, 600f, 500f), c.toList())
    }

    @Test
    fun handlesOfATinyAreaDoNotOverlap() {
        val touch = 96f
        val region = PxRect(495f, 495f, 505f, 505f)
        val c = handleCenters(region, PxRect(0f, 0f, 1000f, 1000f), touch)
        for (a in RegionHandle.entries) for (b in RegionHandle.entries) {
            if (a.ordinal >= b.ordinal) continue
            val dx = kotlin.math.abs(c[a.ordinal * 2] - c[b.ordinal * 2])
            val dy = kotlin.math.abs(c[a.ordinal * 2 + 1] - c[b.ordinal * 2 + 1])
            assertTrue("$a and $b overlap", dx >= touch - eps || dy >= touch - eps)
        }
        assertEquals(RegionHandle.MOVE, hitHandle(c, 500f, 500f, touch))
        assertEquals(RegionHandle.TOP_LEFT, hitHandle(c, 404f, 404f, touch))
    }

    @Test
    fun handlesStayInsideTheSafeBounds() {
        val touch = 96f
        val bounds = PxRect(60f, 0f, 940f, 700f)
        val region = PxRect(-100f, 600f, 300f, 900f)
        val c = handleCenters(region, bounds, touch)
        for (handle in RegionHandle.entries) {
            val x = c[handle.ordinal * 2]
            val y = c[handle.ordinal * 2 + 1]
            assertTrue("$handle x=$x", x >= bounds.left + touch / 2 && x <= bounds.right - touch / 2)
            assertTrue("$handle y=$y", y >= bounds.top + touch / 2 && y <= bounds.bottom - touch / 2)
        }
    }

    @Test
    fun hitHandlePrefersCornersAndMissesOutside() {
        val c = handleCenters(PxRect(200f, 200f, 600f, 500f), PxRect(0f, 0f, 1000f, 1000f), 96f)
        assertEquals(RegionHandle.BOTTOM_RIGHT, hitHandle(c, 610f, 480f, 96f))
        assertEquals(RegionHandle.MOVE, hitHandle(c, 420f, 330f, 96f))
        assertNull(hitHandle(c, 400f, 220f, 96f))
        // Where a clamped corner and the move handle share ground, the corner wins.
        val crowded = handleCenters(PxRect(0f, 0f, 100f, 100f), PxRect(0f, 0f, 150f, 150f), 96f)
        assertEquals(RegionHandle.TOP_LEFT, hitHandle(crowded, 60f, 60f, 96f))
    }

    @Test
    fun movingKeepsTheSizeAndTheCentreInTheFrame() {
        val start = RegionKeyframe(5L, 0.5f, 0.5f, 0.3f, 0.2f)
        assertEquals(RegionKeyframe(5L, 0.6f, 0.4f, 0.3f, 0.2f), dragRegion(start, RegionHandle.MOVE, 0.1f, -0.1f))
        val far = dragRegion(start, RegionHandle.MOVE, 2f, -2f)
        assertEquals(1f, far.cx, 0f)
        assertEquals(0f, far.cy, 0f)
        assertEquals(0.3f, far.w, 0f)
    }

    @Test
    fun aCornerDragKeepsTheOppositeCornerInPlace() {
        val start = RegionKeyframe(0L, 0.5f, 0.5f, 0.2f, 0.2f)
        val grown = dragRegion(start, RegionHandle.BOTTOM_RIGHT, 0.1f, 0.2f)
        assertEquals(0.4f, grown.cx - grown.w / 2, eps)
        assertEquals(0.4f, grown.cy - grown.h / 2, eps)
        assertEquals(0.7f, grown.cx + grown.w / 2, eps)
        assertEquals(0.8f, grown.cy + grown.h / 2, eps)

        val fromTopLeft = dragRegion(start, RegionHandle.TOP_LEFT, -0.1f, 0.05f)
        assertEquals(0.3f, fromTopLeft.cx - fromTopLeft.w / 2, eps)
        assertEquals(0.45f, fromTopLeft.cy - fromTopLeft.h / 2, eps)
        assertEquals(0.6f, fromTopLeft.cx + fromTopLeft.w / 2, eps)
        assertEquals(0.6f, fromTopLeft.cy + fromTopLeft.h / 2, eps)
    }

    @Test
    fun aCornerDragStopsAtTheMinimumSizeAndTheFrameEdge() {
        val start = RegionKeyframe(0L, 0.5f, 0.5f, 0.2f, 0.2f)
        val crushed = dragRegion(start, RegionHandle.BOTTOM_RIGHT, -0.9f, -0.9f)
        assertEquals(EditOps.MIN_REGION_SIZE, crushed.w, eps)
        assertEquals(EditOps.MIN_REGION_SIZE, crushed.h, eps)
        assertEquals(0.4f, crushed.cx - crushed.w / 2, eps)

        val stretched = dragRegion(start, RegionHandle.TOP_RIGHT, 5f, -5f)
        assertEquals(1f, stretched.cx + stretched.w / 2, eps)
        assertEquals(0f, stretched.cy - stretched.h / 2, eps)
        assertEquals(0.6f, stretched.cy + stretched.h / 2, eps)
    }

    @Test
    fun safeBoundsRemoveTheGestureInsetsThatReachThePane() {
        // A pane at the left edge of a 1000 x 2000 window, starting 300 px down.
        val b = gestureSafeBounds(0f, 300f, 1000f, 800f, 1000f, 2000f, 60f, 100f, 60f, 120f)
        assertEquals(PxRect(60f, 0f, 940f, 800f), b)
        // A pane that ends at the bottom of the window loses the bottom inset too.
        val low = gestureSafeBounds(0f, 1200f, 1000f, 800f, 1000f, 2000f, 60f, 100f, 60f, 120f)
        assertEquals(PxRect(60f, 0f, 940f, 680f), low)
        val swallowed = gestureSafeBounds(0f, 0f, 50f, 50f, 1000f, 2000f, 60f, 0f, 0f, 0f)
        assertEquals(PxRect(0f, 0f, 50f, 50f), swallowed)
    }

    @Test
    fun previewIsDownscaledOnlyAboveTheLimit() {
        assertEquals(0, previewDownscaleHeight(1920, 1080))
        assertEquals(0, previewDownscaleHeight(1080, 1920))
        assertEquals(1080, previewDownscaleHeight(3840, 2160))
        assertEquals(1920, previewDownscaleHeight(2160, 3840))
        assertEquals(1080, previewDownscaleHeight(2560, 1440))
        assertEquals(0, previewDownscaleHeight(0, 0))
    }

    @Test
    fun tenthsFormatKeepsShortRangesApart() {
        assertEquals("0:00.0", formatTenthsUs(-5L))
        assertEquals("0:03.4", formatTenthsUs(3_450_000L))
        assertEquals("1:05.9", formatTenthsUs(65_999_999L))
        assertEquals("1:00:00.0", formatTenthsUs(3_600_000_000L))
    }
}
