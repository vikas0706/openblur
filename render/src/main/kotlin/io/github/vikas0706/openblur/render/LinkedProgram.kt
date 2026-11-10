@file:OptIn(UnstableApi::class)

package io.github.vikas0706.openblur.render

import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi

/**
 * A compiled and linked shader pair. Media3's [GlProgram] does the build, so a failure carries the driver's
 * log, and the delete, which it skips on the devices where deleting a program crashes. Uniforms are set with
 * raw GL calls instead of its setters, which check for errors and allocate on every bind.
 */
internal class LinkedProgram(name: String, vertexSource: String, fragmentSource: String) {
    private val program: GlProgram
    val id: Int
    val cornerAttribute: Int

    init {
        try {
            program = GlProgram(vertexSource, fragmentSource)
            // GlProgram keeps its id private; making it current is the one way to read it back.
            program.use()
            val current = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, current, 0)
            id = current[0]
            cornerAttribute = GLES20.glGetAttribLocation(id, CORNER_ATTRIBUTE)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException("Mask shader \"$name\" failed to build: ${e.message}", e)
        }
        if (id == 0 || cornerAttribute < 0) {
            throw VideoFrameProcessingException("Mask shader \"$name\" linked without a usable program or corner attribute")
        }
    }

    /** -1 when the shader does not use [name]; GL ignores uploads to that location. */
    fun uniform(name: String): Int = GLES20.glGetUniformLocation(id, name)

    /** Points sampler [name] at texture unit [unit]. The value is stored in the program, so once is enough. */
    fun bindSampler(name: String, unit: Int) {
        GLES20.glUseProgram(id)
        GLES20.glUniform1i(uniform(name), unit)
    }

    fun delete() {
        program.delete()
    }

    private companion object {
        const val CORNER_ATTRIBUTE = "aCorner"
    }
}
