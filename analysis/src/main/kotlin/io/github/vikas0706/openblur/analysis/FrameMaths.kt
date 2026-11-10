package io.github.vikas0706.openblur.analysis

import kotlin.math.ceil
import kotlin.math.max

/**
 * Column-major 3x3 matrices from a viewport position (x right, y up, 0..1) to a frame texture coordinate.
 * Frame textures have the picture's bottom row at v = 0; render targets hold the output's top row at the bottom.
 */
internal object FrameMaths {
    const val MATRIX_SIZE = 9

    const val MAX_TAPS = 4

    /**
     * [corners] are the normalized, y-down frame positions of the output's top-left, top-right and bottom-left
     * corners, so output pixel (i, j) samples where its centre (i + 0.5, j + 0.5) lands.
     */
    fun cropTexMatrix(corners: FloatArray, out: FloatArray) {
        require(corners.size >= 6) { "corners needs 6 values, got ${corners.size}" }
        require(out.size >= MATRIX_SIZE) { "matrix needs $MATRIX_SIZE values, got ${out.size}" }
        val tlx = corners[0]
        val tly = corners[1]
        out[0] = corners[2] - tlx
        out[1] = -(corners[3] - tly)
        out[2] = 0f
        out[3] = corners[4] - tlx
        out[4] = -(corners[5] - tly)
        out[5] = 0f
        out[6] = tlx
        out[7] = 1f - tly
        out[8] = 1f
    }

    fun fullFrameTexMatrix(out: FloatArray) {
        require(out.size >= MATRIX_SIZE) { "matrix needs $MATRIX_SIZE values, got ${out.size}" }
        out[0] = 1f
        out[1] = 0f
        out[2] = 0f
        out[3] = 0f
        out[4] = -1f
        out[5] = 0f
        out[6] = 0f
        out[7] = 1f
        out[8] = 1f
    }

    /** The vertex shader's computation, for tests: [matrix] times ([viewX], [viewY], 1), written to [out] as u, v. */
    fun texCoord(matrix: FloatArray, viewX: Float, viewY: Float, out: FloatArray) {
        out[0] = matrix[0] * viewX + matrix[3] * viewY + matrix[6]
        out[1] = matrix[1] * viewX + matrix[4] * viewY + matrix[7]
    }

    /**
     * Bilinear taps per axis when shrinking the frame for the detector. A tap reads two texels per axis, so
     * this count covers every texel under an output pixel up to a shrink factor of 2 * [MAX_TAPS].
     */
    fun tapsPerAxis(frameWidth: Int, frameHeight: Int, contentWidth: Int, contentHeight: Int): Int {
        require(frameWidth > 0 && frameHeight > 0 && contentWidth > 0 && contentHeight > 0) {
            "sizes must be positive: frame ${frameWidth}x$frameHeight, content ${contentWidth}x$contentHeight"
        }
        val shrink = max(frameWidth.toDouble() / contentWidth, frameHeight.toDouble() / contentHeight)
        return ceil(shrink / 2.0 - 1e-6).toInt().coerceIn(1, MAX_TAPS)
    }
}
