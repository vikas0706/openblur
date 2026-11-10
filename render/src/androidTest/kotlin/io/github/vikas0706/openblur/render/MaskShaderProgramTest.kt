@file:OptIn(UnstableApi::class)

package io.github.vikas0706.openblur.render

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.test.core.app.ApplicationProvider
import io.github.vikas0706.openblur.api.MaskEffectController
import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.MaskStyle
import io.github.vikas0706.openblur.core.MaskTimeline
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Drives the mask shader program directly on an off-screen EGL context, with no video involved.
 * Each test runs under an ES 2.0 and an ES 3.0 context, the two kinds Media3 creates.
 * Pixel rows are in GL order: row 0 is the bottom of the picture.
 */
@RunWith(Parameterized::class)
class MaskShaderProgramTest(private val glVersion: Int) {

    private class Mask(
        val cx: Float,
        val cy: Float,
        val halfW: Float,
        val halfH: Float,
        val rotation: Float = 0f,
        val style: MaskStyle = MaskStyle.SOLID,
        val shape: MaskShape = MaskShape.RECT,
        val strength: Float = 0.7f,
        val color: Int = 0xFF000000.toInt(),
    )

    private class FakeTimeline(private val masks: List<Mask>, private val fromUs: Long = 0L) : MaskTimeline {
        override val durationUs: Long = 10_000_000L
        override val isEmpty: Boolean get() = masks.isEmpty()
        var lastTimeUs = Long.MIN_VALUE

        override fun sample(timeUs: Long, out: MaskBatch, includeHidden: Boolean) {
            lastTimeUs = timeUs
            out.clear()
            if (timeUs < fromUs) return
            for ((i, m) in masks.withIndex()) {
                out.add(m.cx, m.cy, m.halfW, m.halfH, m.rotation, m.style, m.shape, m.strength, m.color, MaskBatch.KIND_FACE, i, false)
            }
        }
    }

    private lateinit var context: Context
    private lateinit var display: EGLDisplay
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private lateinit var controller: MaskEffectController
    private var program: BaseGlShaderProgram? = null
    private var inputTexture = 0
    private var outputTexture = 0
    private var outputFramebuffer = 0
    private lateinit var pattern: ByteArray

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        display = GlUtil.getDefaultEglDisplay()
        try {
            eglContext = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, glVersion, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        } catch (e: GlUtil.GlException) {
            assumeTrue("No ES $glVersion context on this device: ${e.message}", false)
        }
        eglSurface = GlUtil.createFocusedPlaceholderEglSurface(eglContext, display)
        controller = GlMaskEffectFactory(context).create()
        pattern = testPattern(WIDTH, HEIGHT)
        inputTexture = uploadTexture(WIDTH, HEIGHT, pattern)
        outputTexture = GlUtil.createTexture(WIDTH, HEIGHT, false)
        outputFramebuffer = GlUtil.createFboForTexture(outputTexture)
        program = (controller.effect as GlEffect).toGlShaderProgram(context, false) as BaseGlShaderProgram
        program!!.configure(WIDTH, HEIGHT)
    }

    @After
    fun tearDown() {
        if (eglContext == EGL14.EGL_NO_CONTEXT) return
        program?.release()
        GlUtil.destroyEglSurface(display, eglSurface)
        GlUtil.destroyEglContext(display, eglContext)
    }

    @Test
    fun noMasks_outputEqualsInputExactly() {
        controller.setTimeline(null)
        assertArrayEqualsExactly(pattern, draw())
        controller.setTimeline(FakeTimeline(emptyList()))
        assertArrayEqualsExactly(pattern, draw())
        // A timeline with masks, sampled before any of them starts.
        controller.setTimeline(FakeTimeline(listOf(Mask(0.5f, 0.5f, 0.2f, 0.2f)), fromUs = 5_000_000L))
        assertArrayEqualsExactly(pattern, draw(timeUs = 1_000_000L))
    }

    @Test
    fun timeOffsetIsAddedBeforeTheLookup() {
        val timeline = FakeTimeline(listOf(Mask(0.5f, 0.5f, 0.2f, 0.2f, color = 0xFF00FF00.toInt())), fromUs = 5_000_000L)
        controller.setTimeline(timeline)
        controller.setTimeOffsetUs(4_500_000L)
        val out = draw(timeUs = 1_000_000L)
        assertEquals(5_500_000L, timeline.lastTimeUs)
        assertPixel(out, WIDTH / 2, HEIGHT / 2, 0, 255, 0, 0)
    }

    @Test
    fun ovalBlur_outsideIsUntouchedAndInsideLosesFineDetail() {
        for (strength in floatArrayOf(0f, 0.7f, 1f)) {
            val mask = Mask(0.5f, 0.5f, 0.3f, 0.35f, style = MaskStyle.BLUR, shape = MaskShape.OVAL, strength = strength)
            controller.setTimeline(FakeTimeline(listOf(mask)))
            val out = draw()
            var outsideChanged = 0
            var detailIn = 0L
            var detailOut = 0L
            var inside = 0
            for (row in 1 until HEIGHT - 1) {
                for (x in 1 until WIDTH - 1) {
                    val f = unitRadius(mask, x, row)
                    if (f > 1.02f && !samePixel(pattern, out, x, row)) outsideChanged++
                    if (f < 0.8f) {
                        inside++
                        detailIn += secondDifference(pattern, x, row)
                        detailOut += secondDifference(out, x, row)
                    }
                }
            }
            assertEquals("pixels changed outside the oval at strength $strength", 0, outsideChanged)
            assertTrue(inside > 5000)
            // Measured 70x at strength 0 and 100x at 0.7 on desktop GL; 20x leaves room for driver rounding.
            assertTrue(
                "second-difference energy must drop at least 20x at strength $strength: $detailIn to $detailOut",
                detailOut * 20 <= detailIn,
            )
        }
    }

    @Test
    fun mosaic_blocksAreFlatAndCloseToTheBlockMean() {
        // 96 px tall at strength 0: 12 blocks of 8 px, with grid lines through the centre pixel corner (128, 96).
        val mask = Mask(0.5f, 0.5f, 0.375f, 0.25f, style = MaskStyle.MOSAIC, shape = MaskShape.RECT, strength = 0f)
        controller.setTimeline(FakeTimeline(listOf(mask)))
        val out = draw()
        val seen = HashSet<Int>()
        for (by in -5 until 5) {
            for (bx in -8 until 8) {
                val x0 = 128 + bx * 8
                val row0 = 96 + by * 8
                val mean = IntArray(3)
                for (row in row0 until row0 + 8) {
                    for (x in x0 until x0 + 8) {
                        assertTrue("block ($bx, $by) is not one colour at ($x, $row)", samePixel(out, x0, row0, out, x, row))
                        for (c in 0 until 3) mean[c] += channel(pattern, x, row, c)
                    }
                }
                for (c in 0 until 3) {
                    // One wide tap is a Gaussian-weighted mean, not a box; it was within 9 levels on desktop GL.
                    assertEquals("block ($bx, $by) channel $c", mean[c] / 64f, channel(out, x0, row0, c).toFloat(), 14f)
                }
                seen.add(rgb(out, x0, row0))
            }
        }
        assertTrue("blocks should differ from each other, saw ${seen.size} colours", seen.size > 100)
        for (row in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                if (abs(x + 0.5f - 128f) > 73f || abs(row + 0.5f - 96f) > 49f) {
                    assertTrue("changed outside the mosaic at ($x, $row)", samePixel(pattern, out, x, row))
                }
            }
        }
    }

    @Test
    fun solid_fillsWithTheColour() {
        controller.setTimeline(FakeTimeline(listOf(Mask(0.5f, 0.5f, 0.25f, 0.2f, color = 0xFF3366CC.toInt()))))
        val out = draw()
        for (row in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val dx = abs(x + 0.5f - 128f)
                val dy = abs(row + 0.5f - 96f)
                if (dx < 45f && dy < 35f) assertPixel(out, x, row, 0x33, 0x66, 0xCC, 1)
                if (dx > 49f || dy > 39.4f) assertTrue("changed outside at ($x, $row)", samePixel(pattern, out, x, row))
            }
        }
        assertEquals("frame alpha must survive", 255, channel(out, 128, 96, 3))
    }

    @Test
    fun maskNearTheTopInYDownCoordinatesChangesTheTopOfThePicture() {
        controller.setTimeline(FakeTimeline(listOf(Mask(0.5f, 0.2f, 0.15f, 0.1f, shape = MaskShape.OVAL, color = 0xFFFFFFFF.toInt()))))
        val out = draw()
        assertPixel(out, WIDTH / 2, rowOf(0.2f), 255, 255, 255, 0)
        assertTrue(rowOf(0.2f) > HEIGHT / 2)
        assertTrue("the mirrored spot near the bottom must be untouched", samePixel(pattern, out, WIDTH / 2, rowOf(0.8f)))
    }

    @Test
    fun tiltedEyesBand_rightEndIsLower() {
        val angle = 0.35f
        controller.setTimeline(FakeTimeline(listOf(Mask(0.5f, 0.5f, 0.45f, 0.06f, angle, shape = MaskShape.EYES, color = 0xFFFF0000.toInt()))))
        val out = draw()
        val dx = 0.3f * cos(angle) * HEIGHT / WIDTH
        val dy = 0.3f * sin(angle)
        assertPixel(out, columnOf(0.5f + dx), rowOf(0.5f + dy), 255, 0, 0, 0)
        assertPixel(out, columnOf(0.5f - dx), rowOf(0.5f - dy), 255, 0, 0, 0)
        assertTrue(samePixel(pattern, out, columnOf(0.5f + dx), rowOf(0.5f - dy)))
        assertTrue(samePixel(pattern, out, columnOf(0.5f - dx), rowOf(0.5f + dy)))
    }

    @Test
    fun overlappingMasks_bothApplyAndTheLaterOneIsOnTop() {
        val red = Mask(0.4f, 0.5f, 0.2f, 0.2f, color = 0xFFFF0000.toInt())
        val blue = Mask(0.6f, 0.5f, 0.2f, 0.2f, shape = MaskShape.OVAL, color = 0xFF0000FF.toInt())
        controller.setTimeline(FakeTimeline(listOf(red, blue)))
        var out = draw()
        assertPixel(out, columnOf(0.3f), rowOf(0.5f), 255, 0, 0, 0)
        assertPixel(out, columnOf(0.5f), rowOf(0.5f), 0, 0, 255, 0)
        assertPixel(out, columnOf(0.7f), rowOf(0.5f), 0, 0, 255, 0)
        assertTrue(samePixel(pattern, out, 5, 5))

        // A blur drawn over a solid reads the original frame, so its core matches the blur drawn alone.
        val blur = Mask(0.6f, 0.5f, 0.2f, 0.2f, style = MaskStyle.BLUR, shape = MaskShape.OVAL)
        controller.setTimeline(FakeTimeline(listOf(blur)))
        val alone = draw()
        controller.setTimeline(FakeTimeline(listOf(red, blur)))
        out = draw()
        assertPixel(out, columnOf(0.3f), rowOf(0.5f), 255, 0, 0, 0)
        for (row in 80 until 112) {
            for (x in 140 until 170) {
                for (c in 0 until 3) assertEquals(channel(alone, x, row, c).toFloat(), channel(out, x, row, c).toFloat(), 1f)
            }
        }
        assertFalse(samePixel(pattern, out, columnOf(0.6f), rowOf(0.5f)))
    }

    @Test
    fun fortyMasksAtOnce() {
        val masks = ArrayList<Mask>()
        for (j in 0 until 5) {
            for (i in 0 until 8) {
                val color = (0xFF shl 24) or ((20 + i * 30) shl 16) or ((20 + j * 50) shl 8) or (200 - i * 20)
                masks.add(Mask((i + 0.5f) / 8f, (j + 0.5f) / 5f, 0.06f, 0.06f, color = color))
            }
        }
        controller.setTimeline(FakeTimeline(masks))
        val out = draw()
        for (j in 0 until 5) {
            for (i in 0 until 8) {
                assertPixel(out, columnOf((i + 0.5f) / 8f), rowOf((j + 0.5f) / 5f), 20 + i * 30, 20 + j * 50, 200 - i * 20, 1)
                if (i < 7) {
                    val gapX = columnOf((i + 1) / 8f)
                    assertTrue("gap after mask ($i, $j) changed", samePixel(pattern, out, gapX, rowOf((j + 0.5f) / 5f)))
                }
            }
        }
    }

    @Test
    fun glStateIsRestored() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(0, 0, 4, 4)
        val masks = listOf(
            Mask(0.3f, 0.5f, 0.2f, 0.2f, style = MaskStyle.BLUR, shape = MaskShape.OVAL),
            Mask(0.7f, 0.5f, 0.2f, 0.2f, style = MaskStyle.MOSAIC),
            Mask(0.5f, 0.2f, 0.1f, 0.1f),
        )
        controller.setTimeline(FakeTimeline(masks))
        val out = draw(resetActiveTexture = false)
        val ints = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, ints, 0)
        assertEquals(GLES20.GL_TEXTURE3, ints[0])
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, ints, 0)
        assertEquals(outputFramebuffer, ints[0])
        assertFalse(GLES20.glIsEnabled(GLES20.GL_BLEND))
        assertTrue(GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST))
        GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_RGB, ints, 0)
        assertEquals(GLES20.GL_ONE, ints[0])
        GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_ALPHA, ints, 0)
        assertEquals(GLES20.GL_ONE, ints[0])
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, ints, 0)
        assertEquals(WIDTH, ints[2])
        assertEquals(HEIGHT, ints[3])
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        // The scissor left on by the caller must not have clipped the masks.
        assertFalse(samePixel(pattern, out, columnOf(0.3f), rowOf(0.5f)))
        assertPixel(out, columnOf(0.5f), rowOf(0.2f), 0, 0, 0, 0)
    }

    @Test
    fun newFrameSizeRebuildsThePyramid() {
        val small = testPattern(128, 96)
        val smallInput = uploadTexture(128, 96, small)
        val smallOutput = GlUtil.createTexture(128, 96, false)
        val smallFramebuffer = GlUtil.createFboForTexture(smallOutput)
        val mask = Mask(0.5f, 0.5f, 0.3f, 0.3f, style = MaskStyle.BLUR, shape = MaskShape.OVAL)
        controller.setTimeline(FakeTimeline(listOf(mask)))
        draw()
        program!!.configure(128, 96)
        val out = drawInto(smallFramebuffer, smallInput, 128, 96, 0L)
        var changedInside = 0
        for (row in 40 until 56) for (x in 56 until 72) if (!samePixel(small, out, x, row, 128)) changedInside++
        assertTrue(changedInside > 200)
        for (x in 0 until 128) assertTrue(samePixel(small, out, x, 2, 128))
        program!!.configure(WIDTH, HEIGHT)
        controller.setTimeline(null)
        assertArrayEqualsExactly(pattern, draw())
    }

    @Test
    fun hdr_copyIsExactAndSolidIsLinearBt2020() {
        assumeTrue("HDR frames need an ES 3.0 context", glVersion >= 3)
        val floats = FloatArray(WIDTH * HEIGHT * 4)
        // Multiples of 1/64 up to 8 are exact in half floats, and reach well above SDR white.
        for (i in 0 until WIDTH * HEIGHT) {
            floats[i * 4] = (pattern[i * 4].toInt() and 0xFF) * 2 / 64f
            floats[i * 4 + 1] = (pattern[i * 4 + 1].toInt() and 0xFF) / 64f
            floats[i * 4 + 2] = (pattern[i * 4 + 2].toInt() and 0xFF) / 256f
            floats[i * 4 + 3] = 1f
        }
        val data = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.put(floats).position(0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, WIDTH, HEIGHT, 0, GLES20.GL_RGBA, GLES20.GL_FLOAT, data)
        val hdrOutput = GlUtil.createTexture(WIDTH, HEIGHT, true)
        val hdrFramebuffer = GlUtil.createFboForTexture(hdrOutput)
        assumeTrue(
            "This GPU cannot render to RGBA16F",
            GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE && GLES20.glGetError() == GLES20.GL_NO_ERROR,
        )
        val hdrProgram = (controller.effect as GlEffect).toGlShaderProgram(context, true) as BaseGlShaderProgram
        try {
            hdrProgram.configure(WIDTH, HEIGHT)
            val read = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fun drawHdr(): FloatArray {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, hdrFramebuffer)
                GLES20.glViewport(0, 0, WIDTH, HEIGHT)
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                hdrProgram.drawFrame(ids[0], 0L)
                read.position(0)
                GLES20.glReadPixels(0, 0, WIDTH, HEIGHT, GLES20.GL_RGBA, GLES20.GL_FLOAT, read)
                assumeTrue("This GPU cannot read RGBA16F back as floats", GLES20.glGetError() == GLES20.GL_NO_ERROR)
                val result = FloatArray(floats.size)
                read.position(0)
                read.get(result)
                return result
            }

            controller.setTimeline(null)
            val copy = drawHdr()
            for (i in floats.indices) assertEquals("float $i", floats[i], copy[i], 0f)

            val white = Mask(0.3f, 0.5f, 0.15f, 0.15f, color = 0xFFFFFFFF.toInt())
            val blur = Mask(0.7f, 0.5f, 0.2f, 0.2f, style = MaskStyle.BLUR, shape = MaskShape.OVAL)
            controller.setTimeline(FakeTimeline(listOf(white, blur)))
            val out = drawHdr()
            val solidAt = (rowOf(0.5f) * WIDTH + columnOf(0.3f)) * 4
            for (c in 0 until 3) assertEquals(MaskMath.HDR_WHITE_LEVEL, out[solidAt + c], 0.002f)
            assertEquals(1f, out[solidAt + 3], 0f)
            val blurAt = (rowOf(0.5f) * WIDTH + columnOf(0.7f)) * 4
            assertTrue("blurred value must stay finite and above SDR white", out[blurAt] > 1f && out[blurAt] < 8f)
            var changedOutside = 0
            for (row in 0 until HEIGHT) {
                for (x in 0 until WIDTH) {
                    val inSolid = abs(x + 0.5f - 0.3f * WIDTH) < 30f && abs(row + 0.5f - 96f) < 30f
                    val inBlur = hypot(x + 0.5f - 0.7f * WIDTH, row + 0.5f - 96f) < 39.5f
                    val i = (row * WIDTH + x) * 4
                    if (!inSolid && !inBlur) {
                        for (c in 0 until 4) if (out[i + c] != floats[i + c]) changedOutside++
                    }
                    for (c in 0 until 4) assertFalse(out[i + c].isNaN())
                }
            }
            assertEquals(0, changedOutside)
        } finally {
            hdrProgram.release()
        }
    }

    private fun draw(timeUs: Long = 0L, resetActiveTexture: Boolean = true): ByteArray {
        if (resetActiveTexture) GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        return drawInto(outputFramebuffer, inputTexture, WIDTH, HEIGHT, timeUs)
    }

    /** Does what BaseGlShaderProgram.queueInputFrame does around drawFrame: focus the target and clear it. */
    private fun drawInto(framebuffer: Int, input: Int, width: Int, height: Int, timeUs: Long): ByteArray {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
        val scissor = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST)
        if (scissor) GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        program!!.drawFrame(input, timeUs)
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
        val out = ByteArray(width * height * 4)
        buffer.position(0)
        buffer.get(out)
        return out
    }

    private fun uploadTexture(width: Int, height: Int, rgba: ByteArray): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val data = ByteBuffer.allocateDirect(rgba.size)
        data.put(rgba).position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, data)
        GlUtil.checkGlError()
        return ids[0]
    }

    /** Gentle gradients, a 32 px checker in blue and per-pixel noise of plus or minus 24 levels on every channel. */
    private fun testPattern(width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 4)
        for (row in 0 until height) {
            for (x in 0 until width) {
                val i = (row * width + x) * 4
                out[i] = (64 + x * 127 / (width - 1) + noise(x, row, 0)).coerceIn(0, 255).toByte()
                out[i + 1] = (64 + row * 127 / (height - 1) + noise(x, row, 1)).coerceIn(0, 255).toByte()
                val checker = if (((x shr 5) + (row shr 5)) and 1 == 1) 160 else 96
                out[i + 2] = (checker + noise(x, row, 2)).coerceIn(0, 255).toByte()
                out[i + 3] = 255.toByte()
            }
        }
        return out
    }

    private fun noise(x: Int, row: Int, channel: Int): Int {
        val h = x * 31 + row * 17 + channel * 101
        return ((h * h * 7 + h * 13) shr 3) % 49 - 24
    }

    private fun columnOf(x: Float): Int = (x * WIDTH).toInt()

    /** GL row holding the picture line at [yDown], where 0 is the top of the picture. */
    private fun rowOf(yDown: Float): Int = ((1f - yDown) * HEIGHT).toInt()

    private fun unitRadius(mask: Mask, x: Int, row: Int): Float {
        val px = (x + 0.5f) / WIDTH
        val py = 1f - (row + 0.5f) / HEIGHT
        return hypot((px - mask.cx) * WIDTH / HEIGHT / mask.halfW, (py - mask.cy) / mask.halfH)
    }

    private fun channel(pixels: ByteArray, x: Int, row: Int, c: Int, width: Int = WIDTH): Int =
        pixels[(row * width + x) * 4 + c].toInt() and 0xFF

    private fun rgb(pixels: ByteArray, x: Int, row: Int): Int =
        (channel(pixels, x, row, 0) shl 16) or (channel(pixels, x, row, 1) shl 8) or channel(pixels, x, row, 2)

    private fun samePixel(a: ByteArray, b: ByteArray, x: Int, row: Int, width: Int = WIDTH): Boolean {
        val i = (row * width + x) * 4
        return a[i] == b[i] && a[i + 1] == b[i + 1] && a[i + 2] == b[i + 2] && a[i + 3] == b[i + 3]
    }

    private fun samePixel(a: ByteArray, ax: Int, aRow: Int, b: ByteArray, bx: Int, bRow: Int): Boolean {
        for (c in 0 until 4) if (channel(a, ax, aRow, c) != channel(b, bx, bRow, c)) return false
        return true
    }

    private fun secondDifference(pixels: ByteArray, x: Int, row: Int): Long {
        var sum = 0L
        for (c in 0 until 3) {
            val centre = channel(pixels, x, row, c)
            sum += abs(channel(pixels, x - 1, row, c) - 2 * centre + channel(pixels, x + 1, row, c))
            sum += abs(channel(pixels, x, row - 1, c) - 2 * centre + channel(pixels, x, row + 1, c))
        }
        return sum
    }

    private fun assertPixel(pixels: ByteArray, x: Int, row: Int, r: Int, g: Int, b: Int, tolerance: Int) {
        val message = "pixel ($x, $row) is ${channel(pixels, x, row, 0)}, ${channel(pixels, x, row, 1)}, ${channel(pixels, x, row, 2)}"
        assertTrue(message, abs(channel(pixels, x, row, 0) - r) <= tolerance)
        assertTrue(message, abs(channel(pixels, x, row, 1) - g) <= tolerance)
        assertTrue(message, abs(channel(pixels, x, row, 2) - b) <= tolerance)
        assertEquals(message, 255, channel(pixels, x, row, 3))
    }

    private fun assertArrayEqualsExactly(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                val pixel = i / 4
                throw AssertionError("byte ${i % 4} of pixel (${pixel % WIDTH}, ${pixel / WIDTH}) is ${actual[i]}, expected ${expected[i]}")
            }
        }
    }

    companion object {
        private const val WIDTH = 256
        private const val HEIGHT = 192

        @JvmStatic
        @Parameterized.Parameters(name = "ES {0}")
        fun glVersions(): List<Array<Any>> = listOf(arrayOf(2), arrayOf(3))
    }
}
