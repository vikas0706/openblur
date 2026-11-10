package io.github.vikas0706.openblur.analysis

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Draws parts of a frame texture into small targets and reads them back, top row first. Needs the texture's
 * GL context current, and leaves the framebuffer, viewport, program and texture unit 0 bindings changed.
 */
internal class FrameSampler {
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD)
            position(0)
        }
    private val programs = arrayOfNulls<Program>(FrameMaths.MAX_TAPS + 1)
    private val matrix = FloatArray(FrameMaths.MATRIX_SIZE)
    private val detectorTarget = Target()
    private val cropTarget = Target()
    private var cropPixels: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var cropBytes = ByteArray(0)

    /**
     * Fills [out] with [inputWidth] x [inputHeight] RGBA pixels: the whole frame scaled into the top-left
     * [contentWidth] x [contentHeight] pixels, opaque black elsewhere. [out] must be direct; it is left cleared.
     */
    fun readLetterbox(
        frameTexture: Int,
        frameWidth: Int,
        frameHeight: Int,
        inputWidth: Int,
        inputHeight: Int,
        contentWidth: Int,
        contentHeight: Int,
        out: ByteBuffer,
    ) {
        require(contentWidth in 1..inputWidth && contentHeight in 1..inputHeight) {
            "Content ${contentWidth}x$contentHeight does not fit input ${inputWidth}x$inputHeight"
        }
        require(out.isDirect && out.capacity().toLong() >= inputWidth.toLong() * inputHeight * 4) {
            "Pixel buffer must be direct and hold ${inputWidth}x$inputHeight RGBA pixels"
        }
        ensureTarget(detectorTarget, inputWidth, inputHeight, growOnly = false)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, detectorTarget.framebuffer)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        FrameMaths.fullFrameTexMatrix(matrix)
        val taps = FrameMaths.tapsPerAxis(frameWidth, frameHeight, contentWidth, contentHeight)
        draw(
            program(taps),
            frameTexture,
            contentWidth,
            contentHeight,
            tapStepX = 1f / (contentWidth * taps),
            tapStepY = 1f / (contentHeight * taps),
        )
        out.clear()
        GLES20.glReadPixels(0, 0, inputWidth, inputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, out)
        checkGl("the detector frame")
    }

    /** Implements [io.github.vikas0706.openblur.core.FrameCropper.crop] for [frameTexture]. */
    fun crop(frameTexture: Int, corners: FloatArray, outWidth: Int, outHeight: Int, outRgb: ByteArray) {
        require(outWidth > 0 && outHeight > 0) { "Crop size must be positive, got ${outWidth}x$outHeight" }
        val pixelCount = outWidth.toLong() * outHeight
        require(pixelCount <= MAX_CROP_PIXELS) { "Crop of ${outWidth}x$outHeight is too large" }
        require(outRgb.size >= pixelCount * 3) { "Crop needs ${pixelCount * 3} bytes, got ${outRgb.size}" }
        require(corners.size >= 6 && (0 until 6).all { corners[it].isFinite() }) {
            "Crop corners must be six finite numbers"
        }
        FrameMaths.cropTexMatrix(corners, matrix)
        ensureTarget(cropTarget, outWidth, outHeight, growOnly = true)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, cropTarget.framebuffer)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        draw(program(1), frameTexture, outWidth, outHeight, tapStepX = 0f, tapStepY = 0f)

        // GL ES only guarantees RGBA readback, so the alpha bytes are dropped here.
        val byteCount = pixelCount.toInt() * 4
        if (cropPixels.capacity() < byteCount) {
            cropPixels = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
            cropBytes = ByteArray(byteCount)
        }
        cropPixels.clear()
        GLES20.glReadPixels(0, 0, outWidth, outHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, cropPixels)
        checkGl("a crop")
        cropPixels.position(0)
        cropPixels.get(cropBytes, 0, byteCount)
        var source = 0
        var target = 0
        while (source < byteCount) {
            outRgb[target] = cropBytes[source]
            outRgb[target + 1] = cropBytes[source + 1]
            outRgb[target + 2] = cropBytes[source + 2]
            source += 4
            target += 3
        }
    }

    fun release() {
        for (i in programs.indices) {
            programs[i]?.let { GLES20.glDeleteProgram(it.id) }
            programs[i] = null
        }
        deleteTarget(detectorTarget)
        deleteTarget(cropTarget)
    }

    private fun draw(
        program: Program,
        frameTexture: Int,
        width: Int,
        height: Int,
        tapStepX: Float,
        tapStepY: Float,
    ) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture)
        // The crop contract is bilinear with edge clamping; set here so it does not depend on who made the texture.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glUniform1i(program.frame, 0)
        GLES20.glUniformMatrix3fv(program.matrix, 1, false, matrix, 0)
        if (program.tapStep >= 0) GLES20.glUniform2f(program.tapStep, tapStepX, tapStepY)
        // A buffer left bound by another program would turn the client pointer below into an offset.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnableVertexAttribArray(program.position)
        GLES20.glVertexAttribPointer(program.position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(program.position)
    }

    private fun program(taps: Int): Program {
        programs[taps]?.let { return it }
        val id = link(VERTEX_SHADER, fragmentShader(taps))
        val created = Program(
            id = id,
            position = GLES20.glGetAttribLocation(id, "aPosition"),
            matrix = GLES20.glGetUniformLocation(id, "uTexMatrix"),
            frame = GLES20.glGetUniformLocation(id, "uFrame"),
            tapStep = GLES20.glGetUniformLocation(id, "uTapStep"),
        )
        if (created.position < 0 || created.matrix < 0 || created.frame < 0) {
            GLES20.glDeleteProgram(id)
            throw GlFailure("Sampling shader lost an input it needs")
        }
        programs[taps] = created
        return created
    }

    private fun link(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = try {
            compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        } catch (e: GlFailure) {
            GLES20.glDeleteShader(vertex)
            throw e
        }
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        // Deleting attached shaders only marks them; they go away with the program.
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (id == 0 || status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw GlFailure("Sampling shader did not link: $log", drainErrors())
        }
        return id
    }

    private fun compile(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (id == 0 || status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            throw GlFailure("Sampling shader did not compile: $log", drainErrors())
        }
        return id
    }

    private fun ensureTarget(target: Target, width: Int, height: Int, growOnly: Boolean) {
        val fits = if (growOnly) {
            target.width >= width && target.height >= height
        } else {
            target.width == width && target.height == height
        }
        if (target.framebuffer != 0 && fits) return
        val newWidth = if (growOnly) max(width, target.width) else width
        val newHeight = if (growOnly) max(height, target.height) else height
        deleteTarget(target)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        target.texture = ids[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target.texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, newWidth, newHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glGenFramebuffers(1, ids, 0)
        target.framebuffer = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, target.texture, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        val errors = drainErrors()
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE || errors.isNotEmpty()) {
            deleteTarget(target)
            throw GlFailure(
                "Cannot create a ${newWidth}x$newHeight render target (status 0x${Integer.toHexString(status)}" +
                    "${describe(errors)})",
                errors,
            )
        }
        target.width = newWidth
        target.height = newHeight
    }

    private fun deleteTarget(target: Target) {
        if (target.framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0)
        if (target.texture != 0) GLES20.glDeleteTextures(1, intArrayOf(target.texture), 0)
        target.framebuffer = 0
        target.texture = 0
        target.width = 0
        target.height = 0
    }

    private fun checkGl(what: String) {
        val errors = drainErrors()
        if (errors.isNotEmpty()) throw GlFailure("GL failed while drawing $what${describe(errors)}", errors)
    }

    private fun drainErrors(): IntArray {
        var error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) return NO_ERRORS
        val codes = ArrayList<Int>()
        // Bounded because a lost context can report an error on every call.
        while (error != GLES20.GL_NO_ERROR && codes.size < MAX_REPORTED_ERRORS) {
            codes.add(error)
            error = GLES20.glGetError()
        }
        return codes.toIntArray()
    }

    private fun describe(errors: IntArray): String =
        if (errors.isEmpty()) "" else errors.joinToString(prefix = ", GL errors ") { "0x" + Integer.toHexString(it) }

    private class Program(val id: Int, val position: Int, val matrix: Int, val frame: Int, val tapStep: Int)

    private class Target {
        var texture = 0
        var framebuffer = 0
        var width = 0
        var height = 0
    }

    private companion object {
        const val MAX_CROP_PIXELS = 4096L * 4096L
        const val MAX_REPORTED_ERRORS = 8
        val NO_ERRORS = IntArray(0)
        val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        const val VERTEX_SHADER = """
attribute vec2 aPosition;
uniform mat3 uTexMatrix;
varying vec2 vTex;
void main() {
  gl_Position = vec4(aPosition, 0.0, 1.0);
  vTex = (uTexMatrix * vec3(aPosition * 0.5 + 0.5, 1.0)).xy;
}
"""

        // mediump cannot address single texels of a large frame, so highp is used wherever the GPU has it.
        const val FRAGMENT_HEADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uFrame;
varying vec2 vTex;
"""

        const val FRAGMENT_SINGLE_TAP = FRAGMENT_HEADER + """
void main() {
  gl_FragColor = vec4(texture2D(uFrame, vTex).rgb, 1.0);
}
"""

        /** Averages taps x taps bilinear samples spread evenly over the output pixel's footprint. */
        fun fragmentShader(taps: Int): String {
            if (taps <= 1) return FRAGMENT_SINGLE_TAP
            return FRAGMENT_HEADER + """
uniform vec2 uTapStep;
const int TAPS = $taps;
void main() {
  vec3 sum = vec3(0.0);
  for (int j = 0; j < TAPS; j++) {
    for (int i = 0; i < TAPS; i++) {
      vec2 shift = (vec2(float(i), float(j)) - 0.5 * float(TAPS - 1)) * uTapStep;
      sum += texture2D(uFrame, vTex + shift).rgb;
    }
  }
  gl_FragColor = vec4(sum / float(TAPS * TAPS), 1.0);
}
"""
        }
    }
}
