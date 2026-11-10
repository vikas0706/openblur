package io.github.vikas0706.openblur.render

import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.MaskStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskMathTest {

    private class Mask(
        val cx: Float,
        val cy: Float,
        val halfW: Float,
        val halfH: Float,
        val rotation: Float = 0f,
        val style: MaskStyle = MaskStyle.BLUR,
        val shape: MaskShape = MaskShape.OVAL,
        val strength: Float = 0.7f,
        val color: Int = 0xFF000000.toInt(),
        val hidden: Boolean = false,
    )

    private fun record(
        mask: Mask,
        width: Int = 1920,
        height: Int = 1080,
        levelCount: Int = levelCountFor(width, height),
        hdr: Boolean = false,
    ): FloatArray? {
        val batch = MaskBatch()
        batch.add(
            mask.cx, mask.cy, mask.halfW, mask.halfH, mask.rotation, mask.style, mask.shape, mask.strength,
            mask.color, MaskBatch.KIND_FACE, 1, mask.hidden,
        )
        val out = FloatArray(MaskMath.STRIDE)
        return if (MaskMath.writeRecord(batch, 0, width, height, levelCount, hdr, out, 0)) out else null
    }

    private fun levelCountFor(width: Int, height: Int): Int =
        MaskMath.pyramidSizes(width, height, IntArray(MaskMath.MAX_LEVELS + 1), IntArray(MaskMath.MAX_LEVELS + 1))

    /** The contract's own definition of unit-shape coordinates, written without reference to the code under test. */
    private fun contractUnit(mask: Mask, width: Int, height: Int, x: Float, yDown: Float): FloatArray {
        val dx = (x - mask.cx) * width / height
        val dy = yDown - mask.cy
        val c = cos(-mask.rotation)
        val s = sin(-mask.rotation)
        val qx = dx * c - dy * s
        val qy = dx * s + dy * c
        return floatArrayOf(qx / mask.halfW, qy / mask.halfH)
    }

    /** Frame position, y down, of a point given in the mask's unit-shape coordinates. Inverse of [contractUnit]. */
    private fun framePoint(mask: Mask, width: Int, height: Int, unitX: Float, unitY: Float): FloatArray {
        val qx = unitX * mask.halfW
        val qy = unitY * mask.halfH
        val c = cos(mask.rotation)
        val s = sin(mask.rotation)
        val dx = qx * c - qy * s
        val dy = qx * s + qy * c
        return floatArrayOf(mask.cx + dx * height / width, mask.cy + dy)
    }

    /** What the mask vertex shader computes for a frame point: texture v is 1 - y, then the record's matrix. */
    private fun recordUnit(r: FloatArray, x: Float, yDown: Float): FloatArray {
        val du = x - r[MaskMath.CENTRE]
        val dv = (1f - yDown) - r[MaskMath.CENTRE + 1]
        return floatArrayOf(
            r[MaskMath.TO_UNIT] * du + r[MaskMath.TO_UNIT + 1] * dv,
            r[MaskMath.TO_UNIT + 2] * du + r[MaskMath.TO_UNIT + 3] * dv,
        )
    }

    /** Mirrors the coverage function in [MaskShaders] so its formulas can be checked without a GPU. */
    private fun coverage(r: FloatArray, unitX: Float, unitY: Float): Float = smoothstep(r[MaskMath.SHAPE + 2], depth(r, unitX, unitY))

    private fun depth(r: FloatArray, unitX: Float, unitY: Float): Float {
        val halfX = r[MaskMath.SHAPE]
        val halfY = r[MaskMath.SHAPE + 1]
        val radius = r[MaskMath.SHAPE + 3]
        if (r[MaskMath.OVAL] > 0.5f) {
            val f = hypot(unitX, unitY)
            if (f < 0.5f) return Float.MAX_VALUE
            return (1f - f) * f / hypot(unitX / halfX, unitY / halfY)
        }
        val px = (abs(unitX) - 1f) * halfX + radius
        val py = (abs(unitY) - 1f) * halfY + radius
        return radius - hypot(max(px, 0f), max(py, 0f)) - min(max(px, py), 0f)
    }

    private fun smoothstep(edge: Float, x: Float): Float {
        val t = (x / edge).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    @Test
    fun textureRowsRunBottomToTop() {
        assertEquals(1f, MaskMath.textureV(0f), 0f)
        assertEquals(0f, MaskMath.textureV(1f), 0f)
        assertEquals(0.75f, MaskMath.textureV(0.25f), 0f)
    }

    @Test
    fun quadOfAMaskInTheUpperHalfHasPositiveClipY() {
        val r = record(Mask(cx = 0.5f, cy = 0.25f, halfW = 0.2f, halfH = 0.1f))!!
        // 0.2 frame heights is 0.1125 frame widths at 16:9, so x spans 0.3875..0.6125 and y spans 0.15..0.35.
        assertEquals(-0.225f, r[MaskMath.QUAD], 1e-5f)
        assertEquals(0.3f, r[MaskMath.QUAD + 1], 1e-5f)
        assertEquals(0.225f, r[MaskMath.QUAD + 2], 1e-5f)
        assertEquals(0.7f, r[MaskMath.QUAD + 3], 1e-5f)
        assertEquals(0.5f, r[MaskMath.CENTRE], 0f)
        assertEquals(0.75f, r[MaskMath.CENTRE + 1], 0f)
    }

    @Test
    fun quadIsClippedToTheFrame() {
        val r = record(Mask(cx = 0.02f, cy = 0.98f, halfW = 0.2f, halfH = 0.1f, shape = MaskShape.RECT))!!
        assertEquals(-1f, r[MaskMath.QUAD], 0f)
        assertEquals(-1f, r[MaskMath.QUAD + 1], 0f)
        assertTrue(r[MaskMath.QUAD + 2] > -1f && r[MaskMath.QUAD + 2] < 1f)
        assertTrue(r[MaskMath.QUAD + 3] > -1f && r[MaskMath.QUAD + 3] < 1f)

        val covering = record(Mask(cx = 0.5f, cy = 0.5f, halfW = 3f, halfH = 3f))!!
        assertEquals(-1f, covering[MaskMath.QUAD], 0f)
        assertEquals(-1f, covering[MaskMath.QUAD + 1], 0f)
        assertEquals(1f, covering[MaskMath.QUAD + 2], 0f)
        assertEquals(1f, covering[MaskMath.QUAD + 3], 0f)
    }

    @Test
    fun masksWithNothingToDrawAreSkipped() {
        assertFalse(record(Mask(1.5f, 0.5f, 0.1f, 0.1f)) != null)
        assertFalse(record(Mask(0.5f, -0.4f, 0.1f, 0.1f)) != null)
        assertFalse(record(Mask(0.5f, 0.5f, 0f, 0.1f)) != null)
        assertFalse(record(Mask(0.5f, 0.5f, 0.1f, -0.1f)) != null)
        assertFalse(record(Mask(Float.NaN, 0.5f, 0.1f, 0.1f)) != null)
        assertFalse(record(Mask(0.5f, 0.5f, 0.1f, Float.NaN)) != null)
        assertFalse(record(Mask(0.5f, 0.5f, 0.1f, 0.1f, rotation = Float.POSITIVE_INFINITY)) != null)
        assertFalse(record(Mask(0.5f, 0.5f, 0.1f, 0.1f, hidden = true)) != null)
        assertTrue(record(Mask(0.5f, 0.5f, 0.1f, 0.1f)) != null)
    }

    @Test
    fun unitCoordinatesFollowTheContractForEveryRotationAndAspect() {
        val frames = arrayOf(intArrayOf(1920, 1080), intArrayOf(1080, 1920), intArrayOf(640, 640), intArrayOf(3840, 2160))
        val rotations = floatArrayOf(0f, 0.3f, -0.7f, (PI / 2).toFloat(), 2.5f, -3.1f)
        for (frame in frames) {
            for (rotation in rotations) {
                for (shape in MaskShape.entries) {
                    val mask = Mask(cx = 0.43f, cy = 0.31f, halfW = 0.22f, halfH = 0.07f, rotation = rotation, shape = shape)
                    val r = record(mask, frame[0], frame[1])!!
                    for (ix in 0..8) {
                        for (iy in 0..8) {
                            val x = ix / 8f
                            val y = iy / 8f
                            val expected = contractUnit(mask, frame[0], frame[1], x, y)
                            val actual = recordUnit(r, x, y)
                            val tolerance = 1e-3f * (1f + abs(expected[0]) + abs(expected[1]))
                            assertEquals("x of ($x, $y) rot $rotation", expected[0], actual[0], tolerance)
                            assertEquals("y of ($x, $y) rot $rotation", expected[1], actual[1], tolerance)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun aClockwiseTiltLowersTheRightEnd() {
        val mask = Mask(cx = 0.5f, cy = 0.5f, halfW = 0.4f, halfH = 0.05f, rotation = 0.35f, shape = MaskShape.EYES)
        val r = record(mask, 1280, 720)!!
        // 0.3 frame heights along the band's own x axis: right of the centre and, with y down, below it.
        val x = 0.5f + 0.3f * cos(0.35f) * 720f / 1280f
        val yBelow = 0.5f + 0.3f * sin(0.35f)
        val yAbove = 0.5f - 0.3f * sin(0.35f)
        val onBand = recordUnit(r, x, yBelow)
        val offBand = recordUnit(r, x, yAbove)
        assertEquals(0.75f, onBand[0], 1e-3f)
        assertEquals(0f, onBand[1], 1e-3f)
        assertEquals(1f, coverage(r, onBand[0], onBand[1]), 0f)
        assertTrue("mirrored point must be outside the band", abs(offBand[1]) > 1f)
        assertEquals(0f, coverage(r, offBand[0], offBand[1]), 0f)
    }

    @Test
    fun rectangleQuadIsTheTightBoxOfItsCorners() {
        val width = 1920
        val height = 1080
        for (rotation in floatArrayOf(0f, 0.4f, -1.1f, 1.9f)) {
            val mask = Mask(cx = 0.5f, cy = 0.5f, halfW = 0.25f, halfH = 0.08f, rotation = rotation, shape = MaskShape.RECT)
            val r = record(mask, width, height)!!
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (corner in arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, 1f))) {
                val p = framePoint(mask, width, height, corner[0], corner[1])
                val clipX = p[0] * 2f - 1f
                val clipY = (1f - p[1]) * 2f - 1f
                minX = min(minX, clipX)
                maxX = max(maxX, clipX)
                minY = min(minY, clipY)
                maxY = max(maxY, clipY)
            }
            assertEquals(minX, r[MaskMath.QUAD], 1e-4f)
            assertEquals(minY, r[MaskMath.QUAD + 1], 1e-4f)
            assertEquals(maxX, r[MaskMath.QUAD + 2], 1e-4f)
            assertEquals(maxY, r[MaskMath.QUAD + 3], 1e-4f)
        }
    }

    @Test
    fun ovalQuadIsTheTightBoxOfItsOutline() {
        val width = 1080
        val height = 1920
        for (rotation in floatArrayOf(0f, 0.6f, -0.9f, 2.2f)) {
            val mask = Mask(cx = 0.5f, cy = 0.5f, halfW = 0.12f, halfH = 0.2f, rotation = rotation, shape = MaskShape.OVAL)
            val r = record(mask, width, height)!!
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (step in 0 until 3600) {
                val angle = step * (2 * PI / 3600).toFloat()
                val p = framePoint(mask, width, height, cos(angle), sin(angle))
                val clipX = p[0] * 2f - 1f
                val clipY = (1f - p[1]) * 2f - 1f
                minX = min(minX, clipX)
                maxX = max(maxX, clipX)
                minY = min(minY, clipY)
                maxY = max(maxY, clipY)
            }
            assertEquals(minX, r[MaskMath.QUAD], 1e-4f)
            assertEquals(minY, r[MaskMath.QUAD + 1], 1e-4f)
            assertEquals(maxX, r[MaskMath.QUAD + 2], 1e-4f)
            assertEquals(maxY, r[MaskMath.QUAD + 3], 1e-4f)
        }
    }

    @Test
    fun shapeRecordCarriesEdgeAndCornerPerShape() {
        val oval = record(Mask(0.5f, 0.5f, 0.2f, 0.1f, shape = MaskShape.OVAL))!!
        assertEquals(2f, oval[MaskMath.SHAPE], 1e-6f)
        assertEquals(1f, oval[MaskMath.SHAPE + 1], 1e-6f)
        assertEquals(0.06f, oval[MaskMath.SHAPE + 2], 0f)
        assertEquals(0f, oval[MaskMath.SHAPE + 3], 0f)
        assertEquals(1f, oval[MaskMath.OVAL], 0f)

        for (shape in arrayOf(MaskShape.RECT, MaskShape.EYES)) {
            val rect = record(Mask(0.5f, 0.5f, 0.05f, 0.15f, shape = shape))!!
            assertEquals(1f, rect[MaskMath.SHAPE], 1e-6f)
            assertEquals(3f, rect[MaskMath.SHAPE + 1], 1e-5f)
            assertEquals(0.02f, rect[MaskMath.SHAPE + 2], 0f)
            assertEquals(0.15f, rect[MaskMath.SHAPE + 3], 0f)
            assertEquals(0f, rect[MaskMath.OVAL], 0f)
        }
    }

    @Test
    fun ovalCoverageIsZeroAtTheOutlineAndFullPastTheSoftEdge() {
        val r = record(Mask(0.5f, 0.5f, 0.3f, 0.15f, shape = MaskShape.OVAL))!!
        assertEquals(1f, coverage(r, 0f, 0f), 0f)
        for (step in 0 until 360) {
            val angle = step * (PI / 180).toFloat()
            val ux = cos(angle)
            val uy = sin(angle)
            assertEquals("outline at $step", 0f, coverage(r, ux, uy), 1e-4f)
            assertEquals("outside at $step", 0f, coverage(r, ux * 1.05f, uy * 1.05f), 0f)
            var last = 0f
            for (k in 0..100) {
                val scale = 1f - k / 100f
                val value = coverage(r, ux * scale, uy * scale)
                assertTrue("coverage must not drop on the way in", value >= last - 1e-6f)
                last = value
            }
            assertEquals(1f, last, 0f)
        }
    }

    @Test
    fun ovalDepthEstimateMatchesTheTrueDistanceInsideTheSoftEdge() {
        val r = record(Mask(0.5f, 0.5f, 0.3f, 0.1f, shape = MaskShape.OVAL))!!
        val halfX = r[MaskMath.SHAPE]
        val halfY = r[MaskMath.SHAPE + 1]
        val edge = r[MaskMath.SHAPE + 2]
        // Outline in units of the smaller half size, sampled finely enough for a brute-force nearest point.
        val outline = Array(20000) { i ->
            val angle = i * (2 * PI / 20000)
            floatArrayOf((halfX * cos(angle)).toFloat(), (halfY * sin(angle)).toFloat())
        }
        for (step in 0 until 72) {
            val angle = step * (2 * PI / 72)
            for (fraction in floatArrayOf(0.25f, 0.5f, 1f)) {
                // Walk inward along the normal by a fraction of the soft edge.
                val ox = (halfX * cos(angle)).toFloat()
                val oy = (halfY * sin(angle)).toFloat()
                val nx = (cos(angle) / halfX).toFloat()
                val ny = (sin(angle) / halfY).toFloat()
                val normal = hypot(nx, ny)
                val px = ox - nx / normal * edge * fraction
                val py = oy - ny / normal * edge * fraction
                var trueDepth = Float.MAX_VALUE
                for (point in outline) trueDepth = min(trueDepth, hypot(point[0] - px, point[1] - py))
                val estimate = depth(r, px / halfX, py / halfY)
                assertEquals("angle $step fraction $fraction", trueDepth, estimate, 0.12f * edge)
            }
        }
    }

    @Test
    fun rectangleCoverageHasRoundedCornersAndAThinEdge() {
        val mask = Mask(0.5f, 0.5f, 0.3f, 0.1f, shape = MaskShape.RECT)
        val r = record(mask)!!
        val halfX = r[MaskMath.SHAPE]
        val edge = r[MaskMath.SHAPE + 2]
        val radius = r[MaskMath.SHAPE + 3]
        assertEquals(1f, coverage(r, 0f, 0f), 0f)
        assertEquals(0f, coverage(r, 1f, 0f), 1e-5f)
        assertEquals(0f, coverage(r, 0f, 1f), 1e-5f)
        assertEquals(0f, coverage(r, 0f, -1.01f), 0f)
        // The square corner itself is cut away by the rounding.
        assertEquals(0f, coverage(r, 1f, 1f), 0f)
        assertEquals(0f, coverage(r, -0.999f, 0.999f), 0f)
        // Straight edges: full effect once the point is one soft edge inside, in units of the smaller half.
        assertEquals(1f, coverage(r, 0f, 1f - edge), 1e-4f)
        assertEquals(1f, coverage(r, 1f - edge / halfX, 0f), 1e-4f)
        assertTrue(coverage(r, 0f, 1f - edge / 2f) in 0.4f..0.6f)
        // The middle of the corner arc lies on the outline.
        val arcX = (halfX - radius + radius * cos(PI / 4).toFloat()) / halfX
        val arcY = 1f - radius + radius * sin(PI / 4).toFloat()
        assertEquals(0f, depth(r, arcX, arcY), 1e-4f)
        assertEquals(edge / 2f, depth(r, arcX - edge / 2f * cos(PI / 4).toFloat() / halfX, arcY - edge / 2f * sin(PI / 4).toFloat()), 1e-4f)
    }

    @Test
    fun blurSigmaFollowsMaskSizeAndStrength() {
        assertEquals(12f, MaskMath.blurSigmaPx(100f, 0f), 1e-4f)
        assertEquals(45f, MaskMath.blurSigmaPx(100f, 1f), 1e-4f)
        assertEquals(28.5f, MaskMath.blurSigmaPx(100f, 0.5f), 1e-4f)
        assertEquals(240f, MaskMath.blurSigmaPx(2000f, 0f), 1e-3f)
        assertEquals(12f, MaskMath.blurSigmaPx(100f, -3f), 1e-4f)
        assertEquals(45f, MaskMath.blurSigmaPx(100f, 7f), 1e-4f)
        assertEquals(45f, MaskMath.blurSigmaPx(100f, Float.NaN), 1e-4f)
    }

    @Test
    fun levelBlendReproducesTheRequestedVariance() {
        val levelCount = levelCountFor(3840, 2160)
        val out = FloatArray(MaskMath.STRIDE)
        for (reconstruction in floatArrayOf(MaskMath.BSPLINE_VARIANCE, MaskMath.BILINEAR_VARIANCE)) {
            var sigma = 2f
            while (sigma < 900f) {
                MaskMath.chooseLevels(sigma * sigma, reconstruction, levelCount, out, 0)
                val lo = out[MaskMath.LEVEL_LO].toInt()
                val hi = out[MaskMath.LEVEL_HI].toInt()
                val mix = out[MaskMath.LEVEL_MIX]
                assertTrue(lo >= 1 && hi <= levelCount && (hi == lo || hi == lo + 1))
                assertTrue(mix >= 0f && mix < 1f)
                val blended = (1f - mix) * MaskMath.levelVariance(lo, reconstruction) + mix * MaskMath.levelVariance(hi, reconstruction)
                assertEquals("sigma $sigma", sigma * sigma, blended, sigma * sigma * 1e-3f)
                sigma *= 1.07f
            }
        }
    }

    @Test
    fun levelChoiceNeverJumpsAsAMaskGrows() {
        val levelCount = levelCountFor(1920, 1080)
        val out = FloatArray(MaskMath.STRIDE)
        var previous = 0f
        var sigma = 0.5f
        while (sigma < 2000f) {
            MaskMath.chooseLevels(sigma * sigma, MaskMath.BSPLINE_VARIANCE, levelCount, out, 0)
            val position = out[MaskMath.LEVEL_LO] + out[MaskMath.LEVEL_MIX]
            assertTrue("level position must not go down", position >= previous - 1e-6f)
            // A 0.2 percent step in sigma moves the blend weight by about 0.5 percent, never by a level.
            assertTrue("jump at sigma $sigma: $previous to $position", previous == 0f || position - previous < 0.02f)
            previous = position
            sigma *= 1.002f
        }
        assertEquals(levelCount.toFloat(), previous, 0f)
    }

    @Test
    fun levelChoiceClampsToThePyramid() {
        val out = FloatArray(MaskMath.STRIDE)
        MaskMath.chooseLevels(0.01f, MaskMath.BSPLINE_VARIANCE, 11, out, 0)
        assertEquals(1f, out[MaskMath.LEVEL_LO], 0f)
        assertEquals(1f, out[MaskMath.LEVEL_HI], 0f)
        assertEquals(0f, out[MaskMath.LEVEL_MIX], 0f)

        MaskMath.chooseLevels(1e12f, MaskMath.BSPLINE_VARIANCE, 11, out, 0)
        assertEquals(11f, out[MaskMath.LEVEL_LO], 0f)
        assertEquals(11f, out[MaskMath.LEVEL_HI], 0f)
        assertEquals(0f, out[MaskMath.LEVEL_MIX], 0f)

        MaskMath.chooseLevels(Float.POSITIVE_INFINITY, MaskMath.BSPLINE_VARIANCE, 11, out, 0)
        assertEquals(11f, out[MaskMath.LEVEL_LO], 0f)
        assertEquals(0f, out[MaskMath.LEVEL_MIX], 0f)

        for (variance in floatArrayOf(0f, 5f, 1e9f)) {
            MaskMath.chooseLevels(variance, MaskMath.BSPLINE_VARIANCE, 1, out, 0)
            assertEquals(1f, out[MaskMath.LEVEL_LO], 0f)
            assertEquals(1f, out[MaskMath.LEVEL_HI], 0f)
            assertEquals(0f, out[MaskMath.LEVEL_MIX], 0f)
        }
    }

    @Test
    fun levelVarianceMatchesTheFilterChain() {
        // One [1 3 3 1] / 8 step has variance 0.75 source texels; each later step adds 0.75 at four times the scale.
        assertEquals(0.75f + 4f / 3f, MaskMath.levelVariance(1, MaskMath.BSPLINE_VARIANCE), 1e-5f)
        assertEquals(0.75f + 3f + 16f / 3f, MaskMath.levelVariance(2, MaskMath.BSPLINE_VARIANCE), 1e-4f)
        assertEquals(0.75f + 3f + 12f + 64f / 6f, MaskMath.levelVariance(3, MaskMath.BILINEAR_VARIANCE), 1e-4f)
        assertTrue(MaskMath.levelVariance(MaskMath.MAX_LEVELS, MaskMath.BSPLINE_VARIANCE) > 6e8f)
    }

    @Test
    fun blurRecordPicksLevelsFromTheMaskSize() {
        val mask = Mask(0.5f, 0.5f, halfW = 0.1f, halfH = 0.15f, style = MaskStyle.BLUR, strength = 0.7f)
        val r = record(mask, 1920, 1080)!!
        // Smaller full dimension 216 px, sigma (0.12 + 0.33 * 0.7) * 216 = 75.8 px, between levels 6 and 7.
        assertEquals(MaskMath.STYLE_BLUR.toFloat(), r[MaskMath.STYLE], 0f)
        assertEquals(6f, r[MaskMath.LEVEL_LO], 0f)
        assertEquals(7f, r[MaskMath.LEVEL_HI], 0f)
        val sigma = 75.816f
        val below = MaskMath.levelVariance(6, MaskMath.BSPLINE_VARIANCE)
        val above = MaskMath.levelVariance(7, MaskMath.BSPLINE_VARIANCE)
        assertEquals((sigma * sigma - below) / (above - below), r[MaskMath.LEVEL_MIX], 1e-3f)

        val small = record(Mask(0.5f, 0.5f, 0.01f, 0.015f, strength = 0.7f), 1920, 1080)!!
        val large = record(Mask(0.5f, 0.5f, 0.4f, 0.6f, strength = 0.7f), 1920, 1080)!!
        assertTrue(small[MaskMath.LEVEL_LO] < r[MaskMath.LEVEL_LO])
        assertTrue(large[MaskMath.LEVEL_LO] > r[MaskMath.LEVEL_LO])
    }

    @Test
    fun unknownStyleFallsBackToBlur() {
        val batch = MaskBatch()
        batch.add(0.5f, 0.5f, 0.1f, 0.1f, 0f, MaskStyle.SOLID, MaskShape.RECT, 0.5f, 0, MaskBatch.KIND_FACE, 1, false)
        batch.style[0] = 99
        val out = FloatArray(MaskMath.STRIDE)
        assertTrue(MaskMath.writeRecord(batch, 0, 1280, 720, 10, false, out, 0))
        assertEquals(MaskMath.STYLE_BLUR.toFloat(), out[MaskMath.STYLE], 0f)
        assertTrue(out[MaskMath.LEVEL_LO] >= 1f)
    }

    @Test
    fun mosaicBlockCountRunsFromTwelveToFive() {
        assertEquals(12, MaskMath.mosaicBlocks(0f))
        assertEquals(5, MaskMath.mosaicBlocks(1f))
        assertEquals(12, MaskMath.mosaicBlocks(-1f))
        assertEquals(5, MaskMath.mosaicBlocks(Float.NaN))
        var last = 12
        for (i in 0..100) {
            val blocks = MaskMath.mosaicBlocks(i / 100f)
            assertTrue(blocks <= last)
            last = blocks
        }
    }

    @Test
    fun mosaicGridEndsOnTheMaskEdgesForEvenAndOddCounts() {
        for (strength in floatArrayOf(0f, 0.3f, 0.6f, 1f)) {
            val blocks = MaskMath.mosaicBlocks(strength)
            val r = record(Mask(0.5f, 0.5f, 0.2f, 0.1f, style = MaskStyle.MOSAIC, shape = MaskShape.RECT, strength = strength))!!
            assertEquals(MaskMath.STYLE_MOSAIC.toFloat(), r[MaskMath.STYLE], 0f)
            // The smaller dimension is the height, so its half holds half the block count.
            assertEquals(blocks / 2f, r[MaskMath.GRID + 1], 1e-5f)
            assertEquals(blocks.toFloat(), r[MaskMath.GRID], 1e-5f)
            val edge = 1f * r[MaskMath.GRID + 1] + r[MaskMath.GRID + 2]
            assertEquals("a grid line must sit on the mask edge", edge.roundToInt().toFloat(), edge, 1e-5f)
        }
    }

    @Test
    fun mosaicBlockCentresMapBackOntoThemselves() {
        for (rotation in floatArrayOf(0f, 0.5f, -1.2f)) {
            for (strength in floatArrayOf(0f, 1f)) {
                val mask = Mask(0.4f, 0.6f, 0.2f, 0.12f, rotation, MaskStyle.MOSAIC, MaskShape.EYES, strength)
                val r = record(mask, 1280, 720)!!
                for (ux in floatArrayOf(-0.83f, -0.2f, 0.01f, 0.57f)) {
                    for (uy in floatArrayOf(-0.9f, -0.3f, 0.26f, 0.71f)) {
                        // The mosaic fragment shader's steps: grid cell, its centre, then the texture position.
                        val phase = r[MaskMath.GRID + 2]
                        val bx = floor(ux * r[MaskMath.GRID] + phase) + 0.5f - phase
                        val by = floor(uy * r[MaskMath.GRID + 1] + phase) + 0.5f - phase
                        val u = r[MaskMath.CENTRE] + bx * r[MaskMath.BLOCK_STEP] + by * r[MaskMath.BLOCK_STEP + 2]
                        val v = r[MaskMath.CENTRE + 1] + bx * r[MaskMath.BLOCK_STEP + 1] + by * r[MaskMath.BLOCK_STEP + 3]
                        val back = recordUnit(r, u, 1f - v)
                        assertEquals(bx / r[MaskMath.GRID], back[0], 1e-3f)
                        assertEquals(by / r[MaskMath.GRID + 1], back[1], 1e-3f)
                        // The centre is within half a block of the fragment it was computed for.
                        assertTrue(abs(back[0] - ux) <= 0.5f / r[MaskMath.GRID] + 1e-3f)
                        assertTrue(abs(back[1] - uy) <= 0.5f / r[MaskMath.GRID + 1] + 1e-3f)
                    }
                }
            }
        }
    }

    @Test
    fun mosaicSamplesALevelWhoseFootprintIsOneBlock() {
        val mask = Mask(0.5f, 0.5f, 0.1f, 0.15f, style = MaskStyle.MOSAIC, shape = MaskShape.RECT, strength = 0f)
        val r = record(mask, 1920, 1080)!!
        // 216 px across 12 blocks is 18 px per block; a box that wide has variance 27 px squared.
        val lo = r[MaskMath.LEVEL_LO].toInt()
        val hi = r[MaskMath.LEVEL_HI].toInt()
        val mix = r[MaskMath.LEVEL_MIX]
        val blended = (1f - mix) * MaskMath.levelVariance(lo, MaskMath.BILINEAR_VARIANCE) +
            mix * MaskMath.levelVariance(hi, MaskMath.BILINEAR_VARIANCE)
        assertEquals(27f, blended, 0.05f)
        assertEquals(3, lo)
    }

    @Test
    fun solidColourPassesThroughForSdr() {
        val r = record(Mask(0.5f, 0.5f, 0.1f, 0.1f, style = MaskStyle.SOLID, color = 0x003366CC))!!
        assertEquals(MaskMath.STYLE_SOLID.toFloat(), r[MaskMath.STYLE], 0f)
        assertEquals(0.2f, r[MaskMath.COLOR], 1e-6f)
        assertEquals(0.4f, r[MaskMath.COLOR + 1], 1e-6f)
        assertEquals(0.8f, r[MaskMath.COLOR + 2], 1e-6f)
        assertEquals(0f, r[MaskMath.LEVEL_HI], 0f)
    }

    @Test
    fun solidColourIsLinearBt2020ForHdr() {
        val out = FloatArray(3)
        MaskMath.writeSolidColor(0xFF000000.toInt(), true, out, 0)
        assertEquals(0f, out[0] + out[1] + out[2], 0f)

        MaskMath.writeSolidColor(0xFFFFFFFF.toInt(), true, out, 0)
        for (channel in out) assertEquals(MaskMath.HDR_WHITE_LEVEL, channel, 1e-4f)

        // Mid grey 128 is 0.2158 in linear light.
        MaskMath.writeSolidColor(0xFF808080.toInt(), true, out, 0)
        for (channel in out) assertEquals(0.2158f * MaskMath.HDR_WHITE_LEVEL, channel, 1e-4f)

        // sRGB red sits inside BT.2020, so it picks up a little green and blue.
        MaskMath.writeSolidColor(0xFFFF0000.toInt(), true, out, 0)
        assertEquals(0.6274f * MaskMath.HDR_WHITE_LEVEL, out[0], 1e-5f)
        assertEquals(0.0691f * MaskMath.HDR_WHITE_LEVEL, out[1], 1e-5f)
        assertEquals(0.0164f * MaskMath.HDR_WHITE_LEVEL, out[2], 1e-5f)

        val r = record(Mask(0.5f, 0.5f, 0.1f, 0.1f, style = MaskStyle.SOLID, color = 0xFFFFFFFF.toInt()), hdr = true)!!
        assertEquals(MaskMath.HDR_WHITE_LEVEL, r[MaskMath.COLOR + 1], 1e-4f)
    }

    @Test
    fun pyramidHalvesDownToOneTexel() {
        val widths = IntArray(MaskMath.MAX_LEVELS + 1)
        val heights = IntArray(MaskMath.MAX_LEVELS + 1)
        assertEquals(12, MaskMath.pyramidSizes(3840, 2160, widths, heights))
        assertEquals(listOf(3840, 1920, 960, 480, 240, 120, 60, 30, 15, 8, 4, 2, 1), widths.take(13))
        assertEquals(listOf(2160, 1080, 540, 270, 135, 68, 34, 17, 9, 5, 3, 2, 1), heights.take(13))

        assertEquals(11, MaskMath.pyramidSizes(1080, 1920, widths, heights))
        assertEquals(1, widths[11])
        assertEquals(1, heights[11])

        assertEquals(1, MaskMath.pyramidSizes(1, 1, widths, heights))
        assertEquals(1, widths[1])
        assertEquals(1, heights[1])

        assertEquals(1, MaskMath.pyramidSizes(2, 1, widths, heights))
        assertEquals(MaskMath.MAX_LEVELS, MaskMath.pyramidSizes(32768, 16, widths, heights))
        assertEquals(1, widths[MaskMath.MAX_LEVELS])
    }

    @Test
    fun recordsAreWrittenAtTheGivenOffset() {
        val batch = MaskBatch()
        batch.add(0.5f, 0.5f, 0.1f, 0.1f, 0f, MaskStyle.SOLID, MaskShape.RECT, 0.5f, 0xFFFF0000.toInt(), MaskBatch.KIND_REGION, 7, false)
        batch.add(0.25f, 0.5f, 0.1f, 0.1f, 0f, MaskStyle.MOSAIC, MaskShape.OVAL, 0.5f, 0, MaskBatch.KIND_FACE, 8, false)
        val out = FloatArray(MaskMath.STRIDE * 3) { -7f }
        assertTrue(MaskMath.writeRecord(batch, 1, 1280, 720, 11, false, out, MaskMath.STRIDE))
        assertTrue(out.take(MaskMath.STRIDE).all { it == -7f })
        assertTrue(out.drop(MaskMath.STRIDE * 2).all { it == -7f })
        assertEquals(0.25f, out[MaskMath.STRIDE + MaskMath.CENTRE], 0f)
        assertEquals(MaskMath.STYLE_MOSAIC.toFloat(), out[MaskMath.STRIDE + MaskMath.STYLE], 0f)
        // A solid colour left behind by an earlier mask in the same slot must not survive.
        assertEquals(0f, out[MaskMath.STRIDE + MaskMath.COLOR], 0f)
    }
}
