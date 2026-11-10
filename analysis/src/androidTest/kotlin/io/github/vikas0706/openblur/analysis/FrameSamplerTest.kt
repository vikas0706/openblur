package io.github.vikas0706.openblur.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders through real GL into an off-screen context and compares with [ReferencePicture], which computes the
 * same pictures on the CPU straight from the FrameCropper and FaceDetector contracts.
 */
@RunWith(AndroidJUnit4::class)
class FrameSamplerTest {
    private lateinit var gl: TestGlContext
    private lateinit var sampler: FrameSampler

    @Before
    fun setUp() {
        gl = TestGlContext()
        sampler = FrameSampler()
    }

    @After
    fun tearDown() {
        sampler.release()
        gl.close()
    }

    private fun letterbox(
        picture: ReferencePicture,
        inputWidth: Int,
        inputHeight: Int,
        contentWidth: Int,
        contentHeight: Int,
    ): ByteArray {
        val buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 4).order(ByteOrder.nativeOrder())
        // Stale content and a moved position: the sampler must overwrite all of it and rewind.
        while (buffer.hasRemaining()) buffer.put(0x55)
        sampler.readLetterbox(
            gl.frameTexture(picture), picture.width, picture.height,
            inputWidth, inputHeight, contentWidth, contentHeight, buffer,
        )
        assertEquals(0, buffer.position())
        assertEquals(buffer.capacity(), buffer.limit())
        return ByteArray(buffer.capacity()).also { buffer.get(it) }
    }

    private fun crop(picture: ReferencePicture, corners: FloatArray, outWidth: Int, outHeight: Int): ByteArray {
        val out = ByteArray(outWidth * outHeight * 3)
        sampler.crop(gl.frameTexture(picture), corners, outWidth, outHeight, out)
        return out
    }

    private fun rgba(buffer: ByteArray, width: Int, x: Int, y: Int): List<Int> =
        List(4) { buffer[(y * width + x) * 4 + it].toInt() and 0xFF }

    private fun rgb(buffer: ByteArray, width: Int, x: Int, y: Int): List<Int> =
        List(3) { buffer[(y * width + x) * 3 + it].toInt() and 0xFF }

    private fun assertClose(expected: ByteArray, actual: ByteArray, tolerance: Int) {
        val worst = ReferencePicture.maxDifference(expected, actual)
        assertTrue("largest difference is $worst levels, allowed $tolerance", worst <= tolerance)
    }

    @Test
    fun letterboxPutsThePictureTopLeftWithOpaqueBlackPadding() {
        val picture = ReferencePicture.ramp(64, 48)
        val out = letterbox(picture, inputWidth = 48, inputHeight = 40, contentWidth = 32, contentHeight = 24)

        assertClose(picture.letterbox(48, 40, 32, 24), out, tolerance = 2)
        // Row 0 is the top of the picture and column 0 its left: red grows with x, green with y.
        val topLeft = rgba(out, 48, 0, 0)
        val topRight = rgba(out, 48, 31, 0)
        val bottomLeft = rgba(out, 48, 0, 23)
        assertTrue("red must grow to the right: $topLeft, $topRight", topRight[0] - topLeft[0] > 100)
        assertTrue("green must grow downwards: $topLeft, $bottomLeft", bottomLeft[1] - topLeft[1] > 100)
        assertEquals(listOf(0, 0, 0, 255), rgba(out, 48, 32, 0))
        assertEquals(listOf(0, 0, 0, 255), rgba(out, 48, 0, 24))
        assertEquals(listOf(0, 0, 0, 255), rgba(out, 48, 47, 39))
        assertEquals(255, topLeft[3])
    }

    @Test
    fun letterboxAtFrameSizeIsAPixelExactCopy() {
        val picture = ReferencePicture.noise(32, 24)
        val out = letterbox(picture, inputWidth = 32, inputHeight = 32, contentWidth = 32, contentHeight = 24)
        assertClose(picture.letterbox(32, 32, 32, 24), out, tolerance = 1)
    }

    @Test
    fun letterboxShrinkBySixKeepsALinearPictureExact() {
        val picture = ReferencePicture.ramp(120, 60)
        val out = letterbox(picture, inputWidth = 32, inputHeight = 32, contentWidth = 20, contentHeight = 10)
        assertClose(picture.letterbox(32, 32, 20, 10), out, tolerance = 2)
    }

    @Test
    fun letterboxShrinkAveragesEverySourcePixel() {
        // Columns repeat dark, dark, bright. A single bilinear tap per output pixel lands between a bright
        // and a dark column and returns about 127; averaging all six columns under the pixel returns 85.
        val width = 120
        val height = 12
        val rgb = ByteArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val level = if (x % 3 == 2) 255.toByte() else 0.toByte()
                for (c in 0 until 3) rgb[(y * width + x) * 3 + c] = level
            }
        }
        val out = letterbox(ReferencePicture(width, height, rgb), 32, 32, contentWidth = 20, contentHeight = 2)
        for (y in 0 until 2) {
            for (x in 0 until 20) {
                val pixel = rgba(out, 32, x, y)
                assertTrue("pixel $x,$y is $pixel, expected about 85", pixel.take(3).all { abs(it - 85) <= 4 })
            }
        }
    }

    @Test
    fun sameSizeCropOfTheWholeFrameIsAPixelExactCopy() {
        val picture = ReferencePicture.noise(32, 24)
        val out = crop(picture, ReferencePicture.FULL_FRAME, 32, 24)
        assertClose(picture.rgb, out, tolerance = 1)
        assertEquals("row 0 must be the top row", List(3) { picture.channel(0, 0, it) }, rgb(out, 32, 0, 0))
    }

    @Test
    fun axisAlignedCropMatchesTheContract() {
        val picture = ReferencePicture.ramp(64, 48)
        val sameSize = floatArrayOf(0.5f, 0.25f, 1f, 0.25f, 0.5f, 0.75f)
        val out = crop(picture, sameSize, 32, 24)
        assertClose(picture.crop(sameSize, 32, 24), out, tolerance = 1)
        assertEquals(List(3) { picture.channel(32, 12, it) }, rgb(out, 32, 0, 0))
        assertEquals(List(3) { picture.channel(63, 35, it) }, rgb(out, 32, 31, 23))

        val shrunk = floatArrayOf(0.25f, 0.5f, 0.75f, 0.5f, 0.25f, 1f)
        assertClose(picture.crop(shrunk, 8, 6), crop(picture, shrunk, 8, 6), tolerance = 2)
    }

    @Test
    fun enlargingCropInterpolatesBetweenPixels() {
        val picture = ReferencePicture.noise(16, 12)
        val corners = floatArrayOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f, 0.75f)
        // GPUs interpolate with limited sub-pixel precision, which on noise costs a few levels.
        assertClose(picture.crop(corners, 32, 24), crop(picture, corners, 32, 24), tolerance = 4)
    }

    @Test
    fun rotatedCropsMatchTheContract() {
        val picture = ReferencePicture.ramp(64, 48)
        val quarterTurn = floatArrayOf(0.75f, 0.25f, 0.75f, 0.75f, 0.25f, 0.25f)
        val turned = crop(picture, quarterTurn, 24, 32)
        assertClose(picture.crop(quarterTurn, 24, 32), turned, tolerance = 1)
        assertEquals(List(3) { picture.channel(47, 12, it) }, rgb(turned, 24, 0, 0))
        assertEquals(List(3) { picture.channel(47, 35, it) }, rgb(turned, 24, 23, 0))
        assertEquals(List(3) { picture.channel(16, 12, it) }, rgb(turned, 24, 0, 31))

        val angle = Math.toRadians(30.0)
        val ux = cos(angle) * 24
        val uy = sin(angle) * 24
        val tlx = 32 - (ux - uy) / 2
        val tly = 24 - (uy + ux) / 2
        val tilted = floatArrayOf(
            (tlx / 64).toFloat(), (tly / 48).toFloat(),
            ((tlx + ux) / 64).toFloat(), ((tly + uy) / 48).toFloat(),
            ((tlx - uy) / 64).toFloat(), ((tly + ux) / 48).toFloat(),
        )
        assertClose(picture.crop(tilted, 24, 24), crop(picture, tilted, 24, 24), tolerance = 2)
    }

    @Test
    fun cropBeyondTheFrameRepeatsTheEdgePixels() {
        val picture = ReferencePicture.ramp(64, 48)
        val topLeft = floatArrayOf(-0.125f, -0.125f, 0.375f, -0.125f, -0.125f, 0.375f)
        val out = crop(picture, topLeft, 32, 24)
        assertClose(picture.crop(topLeft, 32, 24), out, tolerance = 1)
        assertEquals(List(3) { picture.channel(0, 0, it) }, rgb(out, 32, 0, 0))
        assertEquals(List(3) { picture.channel(0, 0, it) }, rgb(out, 32, 7, 5))
        assertEquals(List(3) { picture.channel(23, 17, it) }, rgb(out, 32, 31, 23))

        val noise = ReferencePicture.noise(16, 12)
        val bottomRight = floatArrayOf(0.75f, 0.75f, 1.25f, 0.75f, 0.75f, 1.25f)
        assertClose(noise.crop(bottomRight, 8, 6), crop(noise, bottomRight, 8, 6), tolerance = 1)
    }

    @Test
    fun cropSizesCanChangeFromCallToCall() {
        val picture = ReferencePicture.ramp(64, 48)
        val corners = floatArrayOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f, 0.75f)
        val texture = gl.frameTexture(picture)
        for ((width, height) in listOf(8 to 6, 32 to 24, 4 to 4, 32 to 24, 8 to 6, 112 to 112)) {
            val out = ByteArray(width * height * 3)
            sampler.crop(texture, corners, width, height, out)
            assertClose(picture.crop(corners, width, height), out, tolerance = 2)
        }
    }

    @Test
    fun cropWritesOnlyTheBytesItOwns() {
        val picture = ReferencePicture.noise(16, 12)
        val out = ByteArray(8 * 6 * 3 + 5) { 0x33 }
        sampler.crop(gl.frameTexture(picture), ReferencePicture.FULL_FRAME, 8, 6, out)
        assertTrue(out.takeLast(5).all { it == 0x33.toByte() })
    }

    @Test
    fun letterboxAndCropShareOneSamplerWithoutDisturbingEachOther() {
        val picture = ReferencePicture.ramp(64, 48)
        val corners = floatArrayOf(0.5f, 0.25f, 1f, 0.25f, 0.5f, 0.75f)
        repeat(3) {
            assertClose(picture.letterbox(48, 40, 32, 24), letterbox(picture, 48, 40, 32, 24), tolerance = 2)
            assertClose(picture.crop(corners, 32, 24), crop(picture, corners, 32, 24), tolerance = 1)
        }
    }

    @Test
    fun samplerWorksAgainAfterRelease() {
        val picture = ReferencePicture.noise(16, 12)
        assertClose(picture.rgb, crop(picture, ReferencePicture.FULL_FRAME, 16, 12), tolerance = 1)
        sampler.release()
        assertClose(picture.rgb, crop(picture, ReferencePicture.FULL_FRAME, 16, 12), tolerance = 1)
    }

    @Test
    fun badArgumentsAreRejected() {
        val texture = gl.frameTexture(ReferencePicture.noise(16, 12))
        val full = ReferencePicture.FULL_FRAME
        assertThrows(IllegalArgumentException::class.java) { sampler.crop(texture, full, 8, 6, ByteArray(8 * 6 * 3 - 1)) }
        assertThrows(IllegalArgumentException::class.java) { sampler.crop(texture, full, 0, 6, ByteArray(64)) }
        assertThrows(IllegalArgumentException::class.java) { sampler.crop(texture, FloatArray(5), 2, 2, ByteArray(12)) }
        assertThrows(IllegalArgumentException::class.java) {
            sampler.crop(texture, floatArrayOf(0f, 0f, Float.NaN, 0f, 0f, 1f), 2, 2, ByteArray(12))
        }
        val direct = ByteBuffer.allocateDirect(32 * 32 * 4)
        assertThrows(IllegalArgumentException::class.java) {
            sampler.readLetterbox(texture, 16, 12, 32, 32, 33, 24, direct)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sampler.readLetterbox(texture, 16, 12, 32, 32, 32, 24, ByteBuffer.allocate(32 * 32 * 4))
        }
        assertThrows(IllegalArgumentException::class.java) {
            sampler.readLetterbox(texture, 16, 12, 32, 32, 32, 24, ByteBuffer.allocateDirect(100))
        }
    }
}
