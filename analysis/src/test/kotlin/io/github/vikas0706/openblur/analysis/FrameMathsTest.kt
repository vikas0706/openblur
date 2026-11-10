package io.github.vikas0706.openblur.analysis

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameMathsTest {
    private val matrix = FloatArray(FrameMaths.MATRIX_SIZE)
    private val uv = FloatArray(2)

    /**
     * What the GPU does with a [FrameMaths] matrix: the frame texture has the picture's bottom row at v = 0,
     * each fragment samples at its pixel centre, and reading back starts at the target's bottom row.
     */
    private fun render(picture: ReferencePicture, matrix: FloatArray, outWidth: Int, outHeight: Int): ByteArray {
        val out = ByteArray(outWidth * outHeight * 3)
        for (row in 0 until outHeight) {
            for (x in 0 until outWidth) {
                FrameMaths.texCoord(matrix, (x + 0.5f) / outWidth, (row + 0.5f) / outHeight, uv)
                val px = uv[0].toDouble() * picture.width
                val py = (1.0 - uv[1]) * picture.height
                for (c in 0 until 3) {
                    out[(row * outWidth + x) * 3 + c] = picture.sample(px, py, c).roundToInt().toByte()
                }
            }
        }
        return out
    }

    private fun renderCrop(picture: ReferencePicture, corners: FloatArray, outWidth: Int, outHeight: Int): ByteArray {
        FrameMaths.cropTexMatrix(corners, matrix)
        return render(picture, matrix, outWidth, outHeight)
    }

    private fun pixel(buffer: ByteArray, width: Int, x: Int, y: Int): List<Int> =
        List(3) { buffer[(y * width + x) * 3 + it].toInt() and 0xFF }

    // The default tolerance accepts either rounding of a value that falls exactly between two levels.
    private fun assertPixel(expected: List<Double>, actual: List<Int>, tolerance: Double = 0.51) {
        for (c in 0 until 3) assertEquals("channel $c of $actual", expected[c], actual[c].toDouble(), tolerance)
    }

    @Test
    fun fullFrameMatrixReadsThePictureTopFirst() {
        FrameMaths.fullFrameTexMatrix(matrix)
        FrameMaths.texCoord(matrix, 0.25f, 0f, uv)
        assertEquals(0.25f, uv[0], 0f)
        assertEquals("the target's bottom edge must sample the picture's top edge", 1f, uv[1], 0f)
        FrameMaths.texCoord(matrix, 1f, 1f, uv)
        assertEquals(1f, uv[0], 0f)
        assertEquals(0f, uv[1], 0f)
    }

    @Test
    fun fullFrameMatrixEqualsACropOfTheWholeFrame() {
        val viaCrop = FloatArray(FrameMaths.MATRIX_SIZE)
        FrameMaths.cropTexMatrix(ReferencePicture.FULL_FRAME, viaCrop)
        FrameMaths.fullFrameTexMatrix(matrix)
        assertArrayEquals(viaCrop, matrix, 0f)
    }

    @Test
    fun sameSizeCopyOfTheWholeFrameIsExact() {
        val picture = ReferencePicture.noise(16, 12)
        val out = renderCrop(picture, ReferencePicture.FULL_FRAME, 16, 12)
        assertArrayEquals("a half-pixel offset or a flip would blend or move pixels", picture.rgb, out)
        assertArrayEquals(picture.crop(ReferencePicture.FULL_FRAME, 16, 12), out)
    }

    @Test
    fun letterboxRowZeroIsThePictureTop() {
        val picture = ReferencePicture.ramp(64, 48)
        FrameMaths.fullFrameTexMatrix(matrix)
        val out = render(picture, matrix, 32, 24)
        // Each output pixel covers 2x2 source pixels, so its value is the mean of those four.
        assertPixel(listOf(17.0, 25.5, 248.5), pixel(out, 32, 0, 0))
        assertPixel(listOf(141.0, 163.5, 94.5), pixel(out, 32, 31, 23))
        val reference = picture.letterbox(40, 32, 32, 24)
        for (y in 0 until 24) {
            for (x in 0 until 32) {
                val expected = List(3) { (reference[(y * 40 + x) * 4 + it].toInt() and 0xFF).toDouble() }
                assertPixel(expected, pixel(out, 32, x, y), tolerance = 1.0)
            }
        }
    }

    @Test
    fun axisAlignedCropTakesTheNamedRectangle() {
        val picture = ReferencePicture.ramp(64, 48)
        // Right half of the middle band: x 32..64, y 12..36, at its own size.
        val corners = floatArrayOf(0.5f, 0.25f, 1f, 0.25f, 0.5f, 0.75f)
        val out = renderCrop(picture, corners, 32, 24)
        for (j in 0 until 24) {
            for (i in 0 until 32) {
                val expected = List(3) { picture.channel(32 + i, 12 + j, it) }
                assertEquals("pixel $i,$j", expected, pixel(out, 32, i, j))
            }
        }
        assertArrayEquals(picture.crop(corners, 32, 24), out)
    }

    @Test
    fun axisAlignedCropScalesAroundPixelCentres() {
        val picture = ReferencePicture.ramp(64, 48)
        val corners = floatArrayOf(0.25f, 0.5f, 0.75f, 0.5f, 0.25f, 1f)
        // Source pixels 16..48 by 24..48 into 8x6: each output pixel centre is the middle of a 4x4 block.
        val out = renderCrop(picture, corners, 8, 6)
        assertPixel(listOf(16 + 2 * 17.5, 24 + 3 * 25.5, 250 - 17.5 - 2 * 25.5), pixel(out, 8, 0, 0))
        assertPixel(listOf(16 + 2 * 45.5, 24 + 3 * 45.5, 250 - 45.5 - 2 * 45.5), pixel(out, 8, 7, 5))
        assertTrue(ReferencePicture.maxDifference(picture.crop(corners, 8, 6), out) <= 1)
    }

    @Test
    fun quarterTurnCropRotatesThePicture() {
        val picture = ReferencePicture.ramp(64, 48)
        // Output x runs down the frame and output y runs left: the picture turned a quarter turn.
        val corners = floatArrayOf(0.75f, 0.25f, 0.75f, 0.75f, 0.25f, 0.25f)
        val out = renderCrop(picture, corners, 24, 32)
        for (j in 0 until 32) {
            for (i in 0 until 24) {
                val expected = List(3) { picture.channel(47 - j, 12 + i, it) }
                assertEquals("pixel $i,$j", expected, pixel(out, 24, i, j))
            }
        }
        assertArrayEquals(picture.crop(corners, 24, 32), out)
    }

    @Test
    fun rotatedCropFollowsItsThreeCorners() {
        val picture = ReferencePicture.ramp(64, 48)
        // A 24 px square around the picture centre, turned by 30 degrees, given in pixels then normalized.
        val angle = Math.toRadians(30.0)
        val ux = cos(angle) * 24
        val uy = sin(angle) * 24
        val tlx = 32 - (ux - uy) / 2
        val tly = 24 - (uy + ux) / 2
        val corners = floatArrayOf(
            (tlx / 64).toFloat(), (tly / 48).toFloat(),
            ((tlx + ux) / 64).toFloat(), ((tly + uy) / 48).toFloat(),
            ((tlx - uy) / 64).toFloat(), ((tly + ux) / 48).toFloat(),
        )
        FrameMaths.cropTexMatrix(corners, matrix)

        FrameMaths.texCoord(matrix, 0f, 0f, uv)
        assertEquals(corners[0], uv[0], 1e-6f)
        assertEquals(1f - corners[1], uv[1], 1e-6f)
        FrameMaths.texCoord(matrix, 1f, 0f, uv)
        assertEquals(corners[2], uv[0], 1e-6f)
        assertEquals(1f - corners[3], uv[1], 1e-6f)
        FrameMaths.texCoord(matrix, 0f, 1f, uv)
        assertEquals(corners[4], uv[0], 1e-6f)
        assertEquals(1f - corners[5], uv[1], 1e-6f)
        // The middle of the output is the middle of the square, which is the picture centre.
        FrameMaths.texCoord(matrix, 0.5f, 0.5f, uv)
        assertEquals(0.5f, uv[0], 1e-6f)
        assertEquals(0.5f, uv[1], 1e-6f)

        val out = render(picture, matrix, 24, 24)
        assertTrue(ReferencePicture.maxDifference(picture.crop(corners, 24, 24), out) <= 1)
        // The ramp is linear, so the centre of the output must carry the colour at the picture centre.
        val centre = List(3) { c -> (0 until 4).sumOf { picture.channel(31 + it % 2, 23 + it / 2, c) } / 4.0 }
        val middle = List(3) { c ->
            listOf(pixel(out, 24, 11, 11), pixel(out, 24, 12, 11), pixel(out, 24, 11, 12), pixel(out, 24, 12, 12))
                .sumOf { it[c] } / 4.0
        }
        for (c in 0 until 3) assertEquals(centre[c], middle[c], 1.0)
    }

    @Test
    fun cropBeyondTheFrameRepeatsTheEdgePixels() {
        val picture = ReferencePicture.ramp(64, 48)
        // A frame-quarter-sized window whose top-left quarter hangs off the top-left corner.
        val corners = floatArrayOf(-0.125f, -0.125f, 0.375f, -0.125f, -0.125f, 0.375f)
        val out = renderCrop(picture, corners, 32, 24)
        for (j in 0 until 24) {
            for (i in 0 until 32) {
                val expected = List(3) { picture.channel(maxOf(i - 8, 0), maxOf(j - 6, 0), it) }
                assertEquals("pixel $i,$j", expected, pixel(out, 32, i, j))
            }
        }
        assertArrayEquals(picture.crop(corners, 32, 24), out)
    }

    @Test
    fun cropBeyondTheFarEdgesClampsToo() {
        val picture = ReferencePicture.noise(16, 12)
        val corners = floatArrayOf(0.75f, 0.75f, 1.25f, 0.75f, 0.75f, 1.25f)
        val out = renderCrop(picture, corners, 8, 6)
        for (j in 0 until 6) {
            for (i in 0 until 8) {
                val expected = List(3) { picture.channel(minOf(12 + i, 15), minOf(9 + j, 11), it) }
                assertEquals("pixel $i,$j", expected, pixel(out, 8, i, j))
            }
        }
    }

    @Test
    fun tapCountGrowsWithTheShrinkFactor() {
        assertEquals(1, FrameMaths.tapsPerAxis(640, 360, 640, 360))
        assertEquals(1, FrameMaths.tapsPerAxis(320, 180, 640, 360))
        assertEquals(1, FrameMaths.tapsPerAxis(1280, 720, 640, 360))
        assertEquals(2, FrameMaths.tapsPerAxis(1282, 720, 640, 360))
        assertEquals(2, FrameMaths.tapsPerAxis(1920, 1080, 640, 360))
        assertEquals(3, FrameMaths.tapsPerAxis(3840, 2160, 640, 360))
        assertEquals(3, FrameMaths.tapsPerAxis(2160, 3840, 360, 640))
        assertEquals(FrameMaths.MAX_TAPS, FrameMaths.tapsPerAxis(7680, 4320, 640, 360))
        // The larger of the two axis factors decides.
        assertEquals(2, FrameMaths.tapsPerAxis(1920, 1080, 640, 640))
    }

    @Test
    fun badArgumentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { FrameMaths.cropTexMatrix(FloatArray(5), matrix) }
        assertThrows(IllegalArgumentException::class.java) { FrameMaths.cropTexMatrix(FloatArray(6), FloatArray(8)) }
        assertThrows(IllegalArgumentException::class.java) { FrameMaths.tapsPerAxis(0, 10, 10, 10) }
    }
}
