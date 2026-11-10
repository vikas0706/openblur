package io.github.vikas0706.openblur.vision

import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Fills a [1, 3, height, width] float tensor, planes in B, G, R order with raw 0..255 values, from RGBA bytes.
 * Only the content rectangle is read: the model was trained with zero padding, so whatever the caller left
 * in the padding of its buffer must not reach the tensor.
 */
internal class BgrPlaneWriter(private val width: Int, height: Int, private val target: FloatBuffer) {
    private val plane = width * height

    private val rowBytes = ByteArray(width * 4)
    private val rowB = FloatArray(width)
    private val rowG = FloatArray(width)
    private val rowR = FloatArray(width)
    private val zeros = FloatArray(width)

    // The rectangle of the tensor that may hold pixels from an earlier call.
    private var filledWidth = 0
    private var filledHeight = 0

    /** Reads from [rgba]'s position without moving it. Rows of [rgba] are [width] pixels apart. */
    fun write(rgba: ByteBuffer, contentWidth: Int, contentHeight: Int) {
        val base = rgba.position()
        val stride = width * 4
        val rowLength = contentWidth * 4
        val bytes = rowBytes
        val b = rowB
        val g = rowG
        val r = rowR
        try {
            // On Android each single get or put on a direct buffer is a chain of checked calls, while a bulk
            // copy is one native call, so every row moves as one bulk read and three bulk writes.
            for (y in 0 until contentHeight) {
                OrtRuntime.seek(rgba, base + y * stride)
                rgba.get(bytes, 0, rowLength)
                var i = 0
                for (x in 0 until contentWidth) {
                    r[x] = (bytes[i].toInt() and 0xFF).toFloat()
                    g[x] = (bytes[i + 1].toInt() and 0xFF).toFloat()
                    b[x] = (bytes[i + 2].toInt() and 0xFF).toFloat()
                    i += 4
                }
                val row = y * width
                putRow(row, b, contentWidth)
                putRow(plane + row, g, contentWidth)
                putRow(2 * plane + row, r, contentWidth)
            }
        } finally {
            OrtRuntime.seek(rgba, base)
        }
        clearStale(contentWidth, contentHeight)
    }

    private fun putRow(offset: Int, values: FloatArray, count: Int) {
        OrtRuntime.seek(target, offset)
        target.put(values, 0, count)
    }

    /** Zeroes what an earlier, larger content rectangle left outside the current one. */
    private fun clearStale(contentWidth: Int, contentHeight: Int) {
        if (filledWidth > contentWidth) {
            for (y in 0 until minOf(filledHeight, contentHeight)) {
                clearSpan(y * width + contentWidth, filledWidth - contentWidth)
            }
        }
        for (y in contentHeight until filledHeight) {
            clearSpan(y * width, filledWidth)
        }
        filledWidth = contentWidth
        filledHeight = contentHeight
    }

    private fun clearSpan(offset: Int, count: Int) {
        putRow(offset, zeros, count)
        putRow(plane + offset, zeros, count)
        putRow(2 * plane + offset, zeros, count)
    }
}
