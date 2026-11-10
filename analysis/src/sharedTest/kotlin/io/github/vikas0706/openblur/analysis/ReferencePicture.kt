package io.github.vikas0706.openblur.analysis

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A picture as packed RGB bytes with row 0 at the top, plus the sampling the FrameCropper contract describes,
 * written directly from that contract and without any of the GL conventions the production code works in.
 */
internal class ReferencePicture(val width: Int, val height: Int, val rgb: ByteArray) {
    init {
        require(rgb.size == width * height * 3)
    }

    fun channel(x: Int, y: Int, channel: Int): Int {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return rgb[(cy * width + cx) * 3 + channel].toInt() and 0xFF
    }

    /** Bilinear sample at ([px], [py]) in picture pixels, y down, pixel centres at .5, clamped at the edges. */
    fun sample(px: Double, py: Double, channel: Int): Double {
        val x = px - 0.5
        val y = py - 0.5
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val top = channel(x0, y0, channel) * (1 - fx) + channel(x0 + 1, y0, channel) * fx
        val bottom = channel(x0, y0 + 1, channel) * (1 - fx) + channel(x0 + 1, y0 + 1, channel) * fx
        return top * (1 - fy) + bottom * fy
    }

    /** What crop(corners, outWidth, outHeight, out) must produce for this picture. */
    fun crop(corners: FloatArray, outWidth: Int, outHeight: Int): ByteArray {
        val out = ByteArray(outWidth * outHeight * 3)
        for (j in 0 until outHeight) {
            for (i in 0 until outWidth) {
                val fx = (i + 0.5) / outWidth
                val fy = (j + 0.5) / outHeight
                val nx = corners[0] + (corners[2] - corners[0]) * fx + (corners[4] - corners[0]) * fy
                val ny = corners[1] + (corners[3] - corners[1]) * fx + (corners[5] - corners[1]) * fy
                for (c in 0 until 3) {
                    out[(j * outWidth + i) * 3 + c] = sample(nx * width, ny * height, c).roundToInt().toByte()
                }
            }
        }
        return out
    }

    /**
     * What the detector buffer must hold: the picture in the top-left content area, opaque black around it.
     * Exact for colour that is linear in x and y, where any symmetric downscale returns the centre value.
     */
    fun letterbox(inputWidth: Int, inputHeight: Int, contentWidth: Int, contentHeight: Int): ByteArray {
        val content = crop(FULL_FRAME, contentWidth, contentHeight)
        val out = ByteArray(inputWidth * inputHeight * 4)
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val target = (y * inputWidth + x) * 4
                if (x < contentWidth && y < contentHeight) {
                    val source = (y * contentWidth + x) * 3
                    out[target] = content[source]
                    out[target + 1] = content[source + 1]
                    out[target + 2] = content[source + 2]
                }
                out[target + 3] = 0xFF.toByte()
            }
        }
        return out
    }

    companion object {
        val FULL_FRAME = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)

        /** Colour linear in x and y, different per channel, so any flip, swap or half-pixel shift shows. */
        fun ramp(width: Int, height: Int): ReferencePicture {
            val rgb = ByteArray(width * height * 3)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val at = (y * width + x) * 3
                    rgb[at] = (16 + 2 * x).toByte()
                    rgb[at + 1] = (24 + 3 * y).toByte()
                    rgb[at + 2] = (250 - x - 2 * y).toByte()
                }
            }
            require(16 + 2 * (width - 1) <= 255 && 24 + 3 * (height - 1) <= 255 && 250 - (width - 1) - 2 * (height - 1) >= 0)
            return ReferencePicture(width, height, rgb)
        }

        /** Unrelated neighbouring pixels: a copy that is off by any fraction of a pixel blends them and shows. */
        fun noise(width: Int, height: Int, seed: Int = 7): ReferencePicture {
            val rgb = ByteArray(width * height * 3)
            var state = seed
            for (i in rgb.indices) {
                state = state * 1103515245 + 12345
                rgb[i] = (state ushr 16).toByte()
            }
            return ReferencePicture(width, height, rgb)
        }

        /** Largest per-byte difference between two buffers of equal size. */
        fun maxDifference(a: ByteArray, b: ByteArray): Int {
            require(a.size == b.size) { "Sizes differ: ${a.size} and ${b.size}" }
            var worst = 0
            for (i in a.indices) {
                val d = kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
                if (d > worst) worst = d
            }
            return worst
        }
    }
}
