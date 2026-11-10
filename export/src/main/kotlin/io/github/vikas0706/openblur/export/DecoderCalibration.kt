package io.github.vikas0706.openblur.export

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Checks whether the hardware decoder returns the pixels that are in the file.
 *
 * Some phones post-process decoded video (for example Dolby SDR up-mapping on recent Motorola devices),
 * which would change every frame of an export. A tiny bundled clip is decoded by the hardware decoder and by
 * the platform software decoder, which is exact by specification, and the results are compared.
 */
internal class DecoderCalibration(context: Context) {

    enum class Verdict { HARDWARE_EXACT, HARDWARE_WITH_FILTER_OFF, SOFTWARE_ONLY, UNKNOWN }

    class Result(val verdict: Verdict, val hardwareName: String?, val softwareName: String?)

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("decoder_calibration", Context.MODE_PRIVATE)
    private val memory = ConcurrentHashMap<String, Result>()

    /** Blocking, up to a few seconds the first time per codec type on a device; cached after that. */
    fun resultFor(mimeType: String): Result {
        val clip = CLIPS[mimeType] ?: return Result(Verdict.UNKNOWN, null, null)
        memory[mimeType]?.let { return it }
        val decoders = try {
            MediaCodecUtil.getDecoderInfos(mimeType, false, false)
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            emptyList()
        }
        val hardware = decoders.firstOrNull { it.hardwareAccelerated && !it.softwareOnly }
        val software = decoders.firstOrNull { !it.hardwareAccelerated || it.softwareOnly }
        val key = "${Build.FINGERPRINT}|$mimeType|${hardware?.name}|${software?.name}|$VERSION"
        prefs.getString(key, null)?.let { stored ->
            val verdict = runCatching { Verdict.valueOf(stored) }.getOrNull()
            if (verdict != null) return Result(verdict, hardware?.name, software?.name).also { memory[mimeType] = it }
        }
        val verdict = measure(clip, hardware, software)
        Log.i(TAG, "$mimeType: hardware ${hardware?.name}, software ${software?.name}: $verdict")
        if (verdict != Verdict.UNKNOWN) prefs.edit().putString(key, verdict.name).apply()
        return Result(verdict, hardware?.name, software?.name).also { memory[mimeType] = it }
    }

    private fun measure(clip: String, hardware: MediaCodecInfo?, software: MediaCodecInfo?): Verdict {
        if (hardware == null) return Verdict.HARDWARE_EXACT
        if (software == null) return Verdict.UNKNOWN
        val reference = sample(clip, software.name, null) ?: return Verdict.UNKNOWN
        val plain = sample(clip, hardware.name, null) ?: return Verdict.UNKNOWN
        if (matches(plain, reference)) return Verdict.HARDWARE_EXACT
        val filtered = sample(clip, hardware.name, DOLBY_FILTER_KEY)
        if (filtered != null && matches(filtered, reference)) return Verdict.HARDWARE_WITH_FILTER_OFF
        return Verdict.SOFTWARE_ONLY
    }

    private fun matches(a: FloatArray, b: FloatArray): Boolean = a.indices.all { abs(a[it] - b[it]) <= TOLERANCE }

    /**
     * The matrix the GPU uses to turn an untagged stream from [codecName] into RGB, as a `C.COLOR_SPACE_*` value.
     * Measured on a bundled untagged clip of the same class of size; null when it cannot be told apart.
     */
    fun untaggedColorSpace(
        mimeType: String,
        codecName: String,
        filterOff: Boolean,
        width: Int,
        height: Int,
        colorKeys: Map<String, Int>,
    ): Int? {
        val hd = width.coerceAtLeast(height) > 1024 || width.coerceAtMost(height) > 576
        val clip = UNTAGGED_CLIPS[mimeType]?.let { if (hd) it.first else it.second } ?: return null
        val key = "${Build.FINGERPRINT}|untagged|$clip|$codecName|$filterOff|${colorKeys.toSortedMap()}|$VERSION"
        if (prefs.contains(key)) return prefs.getInt(key, 0).takeIf { it != 0 }
        val rgb = sample(clip, codecName, if (filterOff) DOLBY_FILTER_KEY else null, readYuv = false, colorKeys) ?: return null
        val p = (PATCHES - 1) * 3
        fun distance(expected: IntArray) =
            abs(rgb[p] - expected[0]) + abs(rgb[p + 1] - expected[1]) + abs(rgb[p + 2] - expected[2])
        val d601 = distance(RGB_BT601)
        val d709 = distance(RGB_BT709)
        val space = when {
            d601 <= 6f && d601 < d709 -> androidx.media3.common.C.COLOR_SPACE_BT601
            d709 <= 6f && d709 < d601 -> androidx.media3.common.C.COLOR_SPACE_BT709
            else -> null
        }
        Log.i(TAG, "Untagged $clip via $codecName: rgb ${rgb[p]},${rgb[p + 1]},${rgb[p + 2]} -> $space")
        prefs.edit().putInt(key, space ?: 0).apply()
        return space
    }

    /** Decodes the clip's first frame with [codecName] and returns Y, U, V (or R, G, B) per patch, or null on failure. */
    private fun sample(
        clip: String,
        codecName: String,
        vendorKey: String?,
        readYuv: Boolean = true,
        colorKeys: Map<String, Int>? = null,
    ): FloatArray? {
        val thread = HandlerThread("OpenBlurCalibration").apply { start() }
        val done = CountDownLatch(1)
        var result: FloatArray? = null
        Handler(thread.looper).post {
            // The result is stored before the latch opens; a finally on the try expression would open it first.
            try {
                result = decodeOnThisThread(clip, codecName, vendorKey, readYuv, colorKeys)
            } catch (e: Exception) {
                Log.w(TAG, "Calibration with $codecName failed", e)
            } finally {
                done.countDown()
            }
        }
        val finished = done.await(SAMPLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread.quitSafely()
        return if (finished) result else null
    }

    private fun decodeOnThisThread(
        clip: String,
        codecName: String,
        vendorKey: String?,
        readYuv: Boolean,
        colorKeys: Map<String, Int>?,
    ): FloatArray? {
        val egl = Egl()
        val frameThread = HandlerThread("OpenBlurCalibrationFrames").apply { start() }
        var codec: MediaCodec? = null
        var extractor: MediaExtractor? = null
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        try {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            val st = SurfaceTexture(tex[0]).also { texture = it }
            val frameReady = CountDownLatch(1)
            st.setOnFrameAvailableListener({ frameReady.countDown() }, Handler(frameThread.looper))
            surface = Surface(st)

            val ex = MediaExtractor().also { extractor = it }
            appContext.assets.openFd(clip).use { ex.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            ex.selectTrack(0)
            val format = ex.getTrackFormat(0)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (vendorKey != null) format.setInteger(vendorKey, DOLBY_FILTER_BYPASS)
            if (colorKeys != null) {
                // Decoders fill unstated colour aspects differently depending on which ones are present.
                for (k in COLOR_KEYS) format.removeKey(k)
                for ((k, v) in colorKeys) format.setInteger(k, v)
            }
            val c = MediaCodec.createByCodecName(codecName).also { codec = it }
            c.configure(format, surface, null, 0)
            c.start()
            if (!feedUntilRendered(c, ex)) return null
            if (!frameReady.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            st.updateTexImage()
            val matrix = FloatArray(16).also { st.getTransformMatrix(it) }
            return egl.readPatches(tex[0], matrix, width, height, readYuv)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            runCatching { surface?.release() }
            runCatching { texture?.release() }
            frameThread.quitSafely()
            egl.release()
        }
    }

    private fun feedUntilRendered(codec: MediaCodec, extractor: MediaExtractor): Boolean {
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + FRAME_TIMEOUT_MS * 1_000_000
        var inputDone = false
        while (System.nanoTime() < deadline) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(5_000)
                if (index >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, 5_000)
            if (out >= 0) {
                val hasFrame = info.size > 0 || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0
                codec.releaseOutputBuffer(out, hasFrame)
                if (hasFrame) return true
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return false
            }
        }
        return false
    }

    /** A pbuffer context with one program that reads the external texture as raw YUV when the GPU allows it. */
    private class Egl {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: android.opengl.EGLContext
        private val pbuffer: android.opengl.EGLSurface

        init {
            EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, count, 0,
            )
            check(count[0] > 0) { "No EGL config" }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "No EGL context" }
            pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (pbuffer == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
                // The caller only releases a fully built Egl, so a half-built one frees itself here.
                release()
                throw IllegalStateException("No current EGL pbuffer")
            }
        }

        fun readPatches(texture: Int, matrix: FloatArray, width: Int, height: Int, readYuv: Boolean): FloatArray? {
            val yuv = readYuv && GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains("GL_EXT_YUV_target") == true
            val program = link(VERTEX, if (yuv) FRAGMENT_YUV else FRAGMENT_RGB) ?: return null
            val target = IntArray(1)
            val fbo = IntArray(1)
            try {
                GLES20.glGenTextures(1, target, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target[0])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glGenFramebuffers(1, fbo, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, target[0], 0)
                GLES20.glViewport(0, 0, width, height)
                GLES20.glUseProgram(program)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, matrix, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uS"), 0)
                val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
                quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
                val position = GLES20.glGetAttribLocation(program, "aPos")
                GLES20.glEnableVertexAttribArray(position)
                GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return null

                val block = 8
                val pixels = ByteBuffer.allocateDirect(block * block * 4).order(ByteOrder.nativeOrder())
                val out = FloatArray(PATCHES * 3)
                for (p in 0 until PATCHES) {
                    val x = ((p + 0.5f) * width / PATCHES).toInt() - block / 2
                    val y = height / 2 - block / 2
                    pixels.clear()
                    GLES20.glReadPixels(x, y, block, block, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                    for (c in 0 until 3) {
                        var sum = 0
                        for (i in 0 until block * block) sum += pixels.get(i * 4 + c).toInt() and 0xFF
                        out[p * 3 + c] = sum.toFloat() / (block * block)
                    }
                }
                return out
            } finally {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glDeleteFramebuffers(1, fbo, 0)
                GLES20.glDeleteTextures(1, target, 0)
                GLES20.glDeleteProgram(program)
            }
        }

        private fun link(vertex: String, fragment: String): Int? {
            fun compile(type: Int, source: String): Int? {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) {
                    Log.w(TAG, "Shader: ${GLES20.glGetShaderInfoLog(shader)}")
                    GLES20.glDeleteShader(shader)
                    return null
                }
                return shader
            }
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex) ?: return null
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment) ?: return null
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                GLES20.glDeleteProgram(program)
                return null
            }
            return program
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
        }
    }

    companion object {
        private const val TAG = "OpenBlurCalibration"

        /** Bump when the clips or the comparison change, so stored verdicts are measured again. */
        private const val VERSION = 2
        private const val PATCHES = 6
        private const val TOLERANCE = 3f
        private const val SAMPLE_TIMEOUT_MS = 4_000L
        private const val FRAME_TIMEOUT_MS = 3_000L
        private const val EGL_OPENGL_ES3_BIT = 0x40

        /** Dolby's decoder filter on Qualcomm devices; mode 2 passes frames through unchanged. */
        const val DOLBY_FILTER_KEY = "vendor.dolby-filter-init-param.filterMode"
        const val DOLBY_FILTER_BYPASS = 2


        val COLOR_KEYS = listOf(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER)

        private val UNTAGGED_CLIPS = mapOf(
            MimeTypes.VIDEO_H264 to ("calibration/avc_untagged_hd.mp4" to "calibration/avc_untagged_sd.mp4"),
            MimeTypes.VIDEO_H265 to ("calibration/hevc_untagged_hd.mp4" to "calibration/hevc_untagged_sd.mp4"),
        )

        // The clips' coloured patch is Y 100, Cb 90, Cr 170 in limited range; these are its RGB values per matrix.
        private val RGB_BT601 = intArrayOf(165, 79, 21)
        private val RGB_BT709 = intArrayOf(173, 84, 18)

        private val CLIPS = mapOf(
            MimeTypes.VIDEO_H264 to "calibration/avc.mp4",
            MimeTypes.VIDEO_H265 to "calibration/hevc.mp4",
        )

        private const val VERTEX = """#version 300 es
in vec4 aPos;
uniform mat4 uTex;
out vec2 vUv;
void main() {
  gl_Position = aPos;
  vUv = (uTex * vec4(aPos.xy * 0.5 + 0.5, 0.0, 1.0)).xy;
}"""

        private const val FRAGMENT_YUV = """#version 300 es
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uS;
in vec2 vUv;
out vec4 o;
void main() { o = vec4(texture(uS, vUv).xyz, 1.0); }"""

        private const val FRAGMENT_RGB = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uS;
in vec2 vUv;
out vec4 o;
void main() { o = texture(uS, vUv); }"""
    }
}
