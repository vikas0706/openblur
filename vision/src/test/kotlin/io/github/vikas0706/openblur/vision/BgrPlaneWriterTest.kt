package io.github.vikas0706.openblur.vision

import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BgrPlaneWriterTest {
    /** A buffer of [lead] junk bytes followed by a random [width] x [height] RGBA frame, positioned at the frame. */
    private fun randomFrame(width: Int, height: Int, seed: Long, direct: Boolean, lead: Int): ByteBuffer {
        val bytes = ByteArray(lead + width * height * 4)
        Random(seed).nextBytes(bytes)
        val buffer = if (direct) ByteBuffer.allocateDirect(bytes.size) else ByteBuffer.allocate(bytes.size)
        buffer.put(bytes)
        buffer.position(lead)
        return buffer
    }

    /** The tensor the writer must produce, computed one value at a time. */
    private fun expectedTensor(
        rgba: ByteBuffer,
        width: Int,
        height: Int,
        contentWidth: Int,
        contentHeight: Int,
    ): FloatArray {
        val plane = width * height
        val tensor = FloatArray(3 * plane)
        for (y in 0 until contentHeight) {
            for (x in 0 until contentWidth) {
                val at = rgba.position() + (y * width + x) * 4
                tensor[y * width + x] = (rgba.get(at + 2).toInt() and 0xFF).toFloat()
                tensor[plane + y * width + x] = (rgba.get(at + 1).toInt() and 0xFF).toFloat()
                tensor[2 * plane + y * width + x] = (rgba.get(at).toInt() and 0xFF).toFloat()
            }
        }
        return tensor
    }

    private fun contents(buffer: FloatBuffer): FloatArray = FloatArray(buffer.capacity()) { buffer.get(it) }

    @Test
    fun writesTheContentAsBgrPlanesAndKeepsThePaddingZero() {
        val width = 96
        val height = 64
        for (direct in booleanArrayOf(true, false)) {
            for ((contentWidth, contentHeight) in listOf(96 to 64, 70 to 41, 1 to 1, 96 to 1, 1 to 64)) {
                val rgba = randomFrame(width, height, seed = 11, direct = direct, lead = 13)
                val tensor = OrtRuntime.directFloats(3 * width * height)

                BgrPlaneWriter(width, height, tensor).write(rgba, contentWidth, contentHeight)

                assertArrayEquals(
                    "direct=$direct content=${contentWidth}x$contentHeight",
                    expectedTensor(rgba, width, height, contentWidth, contentHeight),
                    contents(tensor),
                    0f,
                )
                assertEquals("the source position must not move", 13, rgba.position())
            }
        }
    }

    @Test
    fun clearsWhatALargerEarlierFrameLeftBehind() {
        val width = 64
        val height = 64
        val tensor = OrtRuntime.directFloats(3 * width * height)
        val writer = BgrPlaneWriter(width, height, tensor)
        val sizes = listOf(64 to 64, 40 to 30, 20 to 50, 50 to 20, 64 to 10, 10 to 64, 33 to 33)
        for ((step, size) in sizes.withIndex()) {
            val rgba = randomFrame(width, height, seed = 100L + step, direct = true, lead = 0)

            writer.write(rgba, size.first, size.second)

            assertArrayEquals(
                "step $step, content ${size.first}x${size.second}",
                expectedTensor(rgba, width, height, size.first, size.second),
                contents(tensor),
                0f,
            )
        }
    }
}
