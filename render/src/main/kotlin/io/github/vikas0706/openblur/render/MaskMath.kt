package io.github.vikas0706.openblur.render

import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.MaskStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns one mask of a [MaskBatch] into the numbers the mask shaders take as uniforms. No GL here, so the
 * geometry can be tested on the JVM. A record is [STRIDE] floats laid out by the offset constants below.
 */
internal object MaskMath {
    /** Halving a 32768 pixel side reaches one texel in 15 steps, which is past any texture a phone allocates. */
    const val MAX_LEVELS = 15

    const val STRIDE = 32

    /** Clip-space quad: left, bottom, right, top. */
    const val QUAD = 0

    /** Mask centre in texture coordinates. */
    const val CENTRE = 4

    /** Two rows of the matrix that takes a texture-space offset from the centre to unit-shape coordinates. */
    const val TO_UNIT = 6

    /** Half width and half height in units of the smaller of the two, then soft edge and corner radius in the same unit. */
    const val SHAPE = 10

    /** 1 for an ellipse, 0 for a rounded rectangle. */
    const val OVAL = 14

    /** One of the STYLE_ constants. */
    const val STYLE = 15

    /** Pyramid levels to blend and the weight of the higher one. Both levels are 0 for [STYLE_SOLID]. */
    const val LEVEL_LO = 16
    const val LEVEL_HI = 17
    const val LEVEL_MIX = 18

    /** Mosaic: blocks per half width, blocks per half height, grid phase. */
    const val GRID = 19

    /** Mosaic: texture-space step of one block along the mask's x axis, then along its y axis. */
    const val BLOCK_STEP = 22

    /** Solid fill in the pipeline's working colour space. */
    const val COLOR = 26

    const val STYLE_BLUR = 0
    const val STYLE_MOSAIC = 1
    const val STYLE_SOLID = 2

    const val BLUR_SIGMA_AT_MIN_STRENGTH = 0.12f
    const val BLUR_SIGMA_AT_MAX_STRENGTH = 0.45f
    const val MOSAIC_BLOCKS_AT_MIN_STRENGTH = 12
    const val MOSAIC_BLOCKS_AT_MAX_STRENGTH = 5
    const val OVAL_SOFT_EDGE = 0.06f
    const val RECT_SOFT_EDGE = 0.02f
    const val RECT_CORNER_RADIUS = 0.15f

    /** Variance, in texels squared, that one [1 3 3 1] / 8 downsampling step adds along each axis. */
    private const val DOWNSAMPLE_VARIANCE = 0.75f

    /** Variance of the cubic B-spline the blur upsamples with, in texels squared of the sampled level. */
    const val BSPLINE_VARIANCE = 1f / 3f

    /** Mean variance of a bilinear tap over all sub-texel positions, in texels squared of the sampled level. */
    const val BILINEAR_VARIANCE = 1f / 6f

    /**
     * Where an SDR white fill sits in the HDR working space, whose 1.0 is 1000 nits for PQ and peak signal for HLG.
     * 0.265 is the HLG reference white (75 percent signal, 203 nits); on PQ it comes out at 265 nits.
     */
    const val HDR_WHITE_LEVEL = 0.265f

    // Texture row 0 is the bottom of the picture and mask coordinates have y down, so v = 1 - y.
    private const val V_AT_FRAME_TOP = 1f
    private const val V_PER_FRAME_Y = -1f

    private val STYLE_MOSAIC_ORDINAL = MaskStyle.MOSAIC.ordinal
    private val STYLE_SOLID_ORDINAL = MaskStyle.SOLID.ordinal
    private val SHAPE_OVAL_ORDINAL = MaskShape.OVAL.ordinal

    fun textureV(frameY: Float): Float = V_AT_FRAME_TOP + V_PER_FRAME_Y * frameY

    /**
     * Fills index 0 with the frame size and index k with the size of pyramid level k, each level half the one
     * before it, rounded up. Returns the number of levels, at least 1. The arrays need [MAX_LEVELS] + 1 entries.
     */
    fun pyramidSizes(frameWidth: Int, frameHeight: Int, outWidths: IntArray, outHeights: IntArray): Int {
        outWidths[0] = frameWidth
        outHeights[0] = frameHeight
        var level = 0
        while (level < MAX_LEVELS) {
            val w = (outWidths[level] + 1) / 2
            val h = (outHeights[level] + 1) / 2
            level++
            outWidths[level] = w
            outHeights[level] = h
            if (w == 1 && h == 1) break
        }
        return level
    }

    /**
     * Variance, in frame pixels squared, of the kernel a sample of pyramid level [level] stands for when it is
     * read through a filter of [reconstructionVariance] texels squared. The kernel is close to a Gaussian.
     */
    fun levelVariance(level: Int, reconstructionVariance: Float): Float {
        val scale = (1L shl (2 * level)).toFloat()
        return (DOWNSAMPLE_VARIANCE / 3f + reconstructionVariance) * scale - DOWNSAMPLE_VARIANCE / 3f
    }

    fun blurSigmaPx(smallerDimensionPx: Float, strength: Float): Float {
        val s = unitStrength(strength)
        return (BLUR_SIGMA_AT_MIN_STRENGTH + (BLUR_SIGMA_AT_MAX_STRENGTH - BLUR_SIGMA_AT_MIN_STRENGTH) * s) * smallerDimensionPx
    }

    fun mosaicBlocks(strength: Float): Int {
        val s = unitStrength(strength)
        val blocks = MOSAIC_BLOCKS_AT_MIN_STRENGTH + (MOSAIC_BLOCKS_AT_MAX_STRENGTH - MOSAIC_BLOCKS_AT_MIN_STRENGTH) * s
        return blocks.roundToInt()
    }

    /**
     * Picks the two neighbouring levels whose blend has [variancePx] and writes them at [LEVEL_LO], [LEVEL_HI]
     * and [LEVEL_MIX] of the record at [offset]. Blending by variance keeps the result continuous in mask size.
     * A request finer than level 1 gets level 1 and one coarser than the last level gets the last level.
     */
    fun chooseLevels(variancePx: Float, reconstructionVariance: Float, levelCount: Int, out: FloatArray, offset: Int) {
        var lo = 1
        while (lo < levelCount - 1 && levelVariance(lo + 1, reconstructionVariance) <= variancePx) lo++
        var mix = 0f
        if (lo < levelCount) {
            val below = levelVariance(lo, reconstructionVariance)
            val above = levelVariance(lo + 1, reconstructionVariance)
            val wanted = (variancePx - below) / (above - below)
            if (wanted > 0f) mix = min(wanted, 1f)
        }
        // A level that gets no weight is not sampled, so the pyramid never has to build it.
        val hi = if (mix > 0f) lo + 1 else lo
        if (mix >= 1f) {
            lo = hi
            mix = 0f
        }
        out[offset + LEVEL_LO] = lo.toFloat()
        out[offset + LEVEL_HI] = hi.toFloat()
        out[offset + LEVEL_MIX] = mix
    }

    /**
     * Writes the fill colour for [argb], ignoring its alpha. SDR frames hold gamma-encoded RGB, so the bytes
     * pass through. HDR frames hold linear BT.2020, so the colour is linearized, moved to BT.2020 primaries
     * and scaled to [HDR_WHITE_LEVEL].
     */
    fun writeSolidColor(argb: Int, hdr: Boolean, out: FloatArray, offset: Int) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        if (!hdr) {
            out[offset] = r
            out[offset + 1] = g
            out[offset + 2] = b
            return
        }
        val lr = srgbToLinear(r)
        val lg = srgbToLinear(g)
        val lb = srgbToLinear(b)
        out[offset] = (0.6274f * lr + 0.3293f * lg + 0.0433f * lb) * HDR_WHITE_LEVEL
        out[offset + 1] = (0.0691f * lr + 0.9195f * lg + 0.0114f * lb) * HDR_WHITE_LEVEL
        out[offset + 2] = (0.0164f * lr + 0.0880f * lg + 0.8956f * lb) * HDR_WHITE_LEVEL
    }

    /**
     * Writes the draw record for mask [index] of [batch] into [out] at [offset].
     * Returns false when there is nothing to draw: the mask is hidden, has no area, is not finite,
     * or its bounding box misses the frame.
     */
    fun writeRecord(
        batch: MaskBatch,
        index: Int,
        frameWidth: Int,
        frameHeight: Int,
        levelCount: Int,
        hdr: Boolean,
        out: FloatArray,
        offset: Int,
    ): Boolean {
        if (batch.hidden[index]) return false
        val g = index * MaskBatch.GEOMETRY_STRIDE
        val cx = batch.geometry[g]
        val cy = batch.geometry[g + 1]
        val halfW = batch.geometry[g + 2]
        val halfH = batch.geometry[g + 3]
        val rotation = batch.geometry[g + 4]
        if (!cx.isFinite() || !cy.isFinite() || !halfW.isFinite() || !halfH.isFinite() || !rotation.isFinite()) return false
        if (halfW <= 0f || halfH <= 0f) return false

        val aspect = frameWidth.toFloat() / frameHeight.toFloat()
        val c = cos(rotation)
        val s = sin(rotation)
        val oval = batch.shape[index] == SHAPE_OVAL_ORDINAL

        // Half extents of the rotated shape's bounding box, in frame heights.
        val extentX: Float
        val extentY: Float
        if (oval) {
            extentX = sqrt(halfW * halfW * c * c + halfH * halfH * s * s)
            extentY = sqrt(halfW * halfW * s * s + halfH * halfH * c * c)
        } else {
            extentX = halfW * abs(c) + halfH * abs(s)
            extentY = halfW * abs(s) + halfH * abs(c)
        }
        val left = max(cx - extentX / aspect, 0f)
        val right = min(cx + extentX / aspect, 1f)
        val top = max(cy - extentY, 0f)
        val bottom = min(cy + extentY, 1f)
        if (!(left < right && top < bottom)) return false

        out[offset + QUAD] = left * 2f - 1f
        out[offset + QUAD + 1] = textureV(bottom) * 2f - 1f
        out[offset + QUAD + 2] = right * 2f - 1f
        out[offset + QUAD + 3] = textureV(top) * 2f - 1f
        out[offset + CENTRE] = cx
        out[offset + CENTRE + 1] = textureV(cy)

        // Texture-space vectors from the centre to the middle of the mask's right edge and of its bottom edge.
        val axisXu = halfW * c / aspect
        val axisXv = V_PER_FRAME_Y * halfW * s
        val axisYu = -halfH * s / aspect
        val axisYv = V_PER_FRAME_Y * halfH * c
        val det = axisXu * axisYv - axisYu * axisXv
        out[offset + TO_UNIT] = axisYv / det
        out[offset + TO_UNIT + 1] = -axisYu / det
        out[offset + TO_UNIT + 2] = -axisXv / det
        out[offset + TO_UNIT + 3] = axisXu / det

        val smallerHalf = min(halfW, halfH)
        val halfX = halfW / smallerHalf
        val halfY = halfH / smallerHalf
        out[offset + SHAPE] = halfX
        out[offset + SHAPE + 1] = halfY
        out[offset + SHAPE + 2] = if (oval) OVAL_SOFT_EDGE else RECT_SOFT_EDGE
        out[offset + SHAPE + 3] = if (oval) 0f else RECT_CORNER_RADIUS
        out[offset + OVAL] = if (oval) 1f else 0f

        val styleOrdinal = batch.style[index]
        val style = when (styleOrdinal) {
            STYLE_MOSAIC_ORDINAL -> STYLE_MOSAIC
            STYLE_SOLID_ORDINAL -> STYLE_SOLID
            else -> STYLE_BLUR
        }
        out[offset + STYLE] = style.toFloat()

        val smallerDimensionPx = 2f * smallerHalf * frameHeight
        val strength = batch.strength[index]
        for (i in GRID until STRIDE) out[offset + i] = 0f
        when (style) {
            STYLE_BLUR -> {
                val sigma = blurSigmaPx(smallerDimensionPx, strength)
                chooseLevels(sigma * sigma, BSPLINE_VARIANCE, levelCount, out, offset)
            }
            STYLE_MOSAIC -> {
                val blocks = mosaicBlocks(strength)
                val blockPx = smallerDimensionPx / blocks
                // A box of width w has variance w * w / 12, which is what one tap has to average over.
                chooseLevels(blockPx * blockPx / 12f, BILINEAR_VARIANCE, levelCount, out, offset)
                val blocksPerHalfX = halfX * blocks / 2f
                val blocksPerHalfY = halfY * blocks / 2f
                out[offset + GRID] = blocksPerHalfX
                out[offset + GRID + 1] = blocksPerHalfY
                // An even count puts a grid line through the centre and an odd count a block, so the blocks
                // end exactly on the mask's edges across its smaller dimension.
                out[offset + GRID + 2] = if (blocks % 2 == 0) 0f else 0.5f
                out[offset + BLOCK_STEP] = axisXu / blocksPerHalfX
                out[offset + BLOCK_STEP + 1] = axisXv / blocksPerHalfX
                out[offset + BLOCK_STEP + 2] = axisYu / blocksPerHalfY
                out[offset + BLOCK_STEP + 3] = axisYv / blocksPerHalfY
            }
            else -> {
                out[offset + LEVEL_LO] = 0f
                out[offset + LEVEL_HI] = 0f
                out[offset + LEVEL_MIX] = 0f
                writeSolidColor(batch.color[index], hdr, out, offset + COLOR)
            }
        }
        return true
    }

    // A strength that is not a number gets the strongest setting, which is the safe side for a privacy mask.
    private fun unitStrength(strength: Float): Float = if (strength.isNaN()) 1f else strength.coerceIn(0f, 1f)

    private fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
}
