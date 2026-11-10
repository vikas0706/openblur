@file:OptIn(UnstableApi::class)

package io.github.vikas0706.openblur.render

import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskTimeline
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What a shader program reads once per frame. Both values are written from other threads. */
internal interface MaskSource {
    val maskTimeline: MaskTimeline?
    val timeOffsetUs: Long
}

/**
 * Copies each frame unchanged, then draws every active mask as its own blended quad on top of the copy.
 * Blur and mosaic read a pyramid of the frame that is built only on frames that have such a mask.
 * Everything runs on Media3's GL thread; drawing a frame allocates nothing.
 */
internal class MaskShaderProgram(
    private val useHdr: Boolean,
    private val source: MaskSource,
) : BaseGlShaderProgram(useHdr, 1) {

    private val batch = MaskBatch()
    private var records = FloatArray(INITIAL_MASK_CAPACITY * MaskMath.STRIDE)
    private val scratch = IntArray(8)

    private var frameWidth = 0
    private var frameHeight = 0

    /** Index 0 is the frame itself and has no texture of its own; levels 1..levelCount are the pyramid. */
    private val levelWidth = IntArray(MaskMath.MAX_LEVELS + 1)
    private val levelHeight = IntArray(MaskMath.MAX_LEVELS + 1)
    private val levelTexture = IntArray(MaskMath.MAX_LEVELS + 1)
    private val levelFramebuffer = IntArray(MaskMath.MAX_LEVELS + 1)
    private var levelCount = 0

    private var copy: LinkedProgram? = null
    private var downsample: LinkedProgram? = null
    private var blur: LinkedProgram? = null
    private var mosaic: LinkedProgram? = null
    private var solid: LinkedProgram? = null
    private var cornerBuffer = 0

    private var downsampleTap = -1
    private val quadUniform = IntArray(STYLE_COUNT)
    private val centreUniform = IntArray(STYLE_COUNT)
    private val toUnitUniform = IntArray(STYLE_COUNT)
    private val shapeUniform = IntArray(STYLE_COUNT)
    private val ovalUniform = IntArray(STYLE_COUNT)
    private val levelMixUniform = IntArray(STYLE_COUNT)
    private var blurLoSize = -1
    private var blurHiSize = -1
    private var mosaicGrid = -1
    private var mosaicGridOrigin = -1
    private var mosaicBlockStep = -1
    private var solidColor = -1

    private var currentProgram = 0
    private var enabledAttribute = -1
    private var released = false

    init {
        try {
            copy = LinkedProgram("copy", MaskShaders.COPY_VERTEX, MaskShaders.COPY_FRAGMENT).also {
                it.bindSampler("uFrame", 0)
            }
            downsample = LinkedProgram("downsample", MaskShaders.DOWNSAMPLE_VERTEX, MaskShaders.DOWNSAMPLE_FRAGMENT).also {
                it.bindSampler("uSource", 0)
                downsampleTap = it.uniform("uTap")
            }
            blur = LinkedProgram("blur", MaskShaders.MASK_VERTEX, MaskShaders.BLUR_FRAGMENT).also {
                readMaskUniforms(it, MaskMath.STYLE_BLUR)
                it.bindSampler("uLo", 0)
                it.bindSampler("uHi", 1)
                blurLoSize = it.uniform("uLoSize")
                blurHiSize = it.uniform("uHiSize")
            }
            mosaic = LinkedProgram("mosaic", MaskShaders.MASK_VERTEX, MaskShaders.MOSAIC_FRAGMENT).also {
                readMaskUniforms(it, MaskMath.STYLE_MOSAIC)
                it.bindSampler("uLo", 0)
                it.bindSampler("uHi", 1)
                mosaicGrid = it.uniform("uGrid")
                mosaicGridOrigin = it.uniform("uGridOrigin")
                mosaicBlockStep = it.uniform("uBlockStep")
            }
            solid = LinkedProgram("solid", MaskShaders.MASK_VERTEX, MaskShaders.SOLID_FRAGMENT).also {
                readMaskUniforms(it, MaskMath.STYLE_SOLID)
                solidColor = it.uniform("uColor")
            }
            cornerBuffer = createCornerBuffer()
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            releaseQuietly()
            throw VideoFrameProcessingException(e)
        } catch (e: VideoFrameProcessingException) {
            releaseQuietly()
            throw e
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (inputWidth <= 0 || inputHeight <= 0) {
            throw VideoFrameProcessingException("Mask effect got an empty frame size: $inputWidth x $inputHeight")
        }
        if (inputWidth != frameWidth || inputHeight != frameHeight || levelCount == 0) {
            try {
                deletePyramid()
                createPyramid(inputWidth, inputHeight)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
            frameWidth = inputWidth
            frameHeight = inputHeight
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val copyProgram = copy
        if (copyProgram == null || levelCount == 0) {
            throw VideoFrameProcessingException("Mask effect drew a frame before configure or after release", presentationTimeUs)
        }
        val maskCount = prepareMasks(presentationTimeUs)
        val topLevel = if (maskCount > 0) highestLevelUsed(maskCount) else 0

        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, scratch, 0)
        val outputFramebuffer = scratch[0]
        GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, scratch, 0)
        val activeTexture = scratch[0]
        // Media3 leaves all of these off, apart from dither. Any of them left on by another effect would
        // drop or alter fragments here.
        var switchedOff = 0
        for (i in CAPABILITIES_KEPT_OFF.indices) {
            if (GLES20.glIsEnabled(CAPABILITIES_KEPT_OFF[i])) {
                GLES20.glDisable(CAPABILITIES_KEPT_OFF[i])
                switchedOff = switchedOff or (1 shl i)
            }
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
        GLES20.glGetTexParameteriv(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, scratch, 0)
        val inputMinFilter = scratch[0]
        GLES20.glGetTexParameteriv(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, scratch, 0)
        val inputMagFilter = scratch[0]

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, cornerBuffer)
        currentProgram = 0
        enabledAttribute = -1

        // Nearest sampling at texel centres returns each input texel as it is, with no filter weights involved.
        setInputFilter(GLES20.GL_NEAREST)
        use(copyProgram)
        GLES20.glViewport(0, 0, frameWidth, frameHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        if (maskCount > 0) {
            if (topLevel > 0) {
                buildPyramid(inputTexId, topLevel)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
                GLES20.glViewport(0, 0, frameWidth, frameHeight)
            }
            drawMasks(maskCount)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
        }

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, inputMinFilter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, inputMagFilter)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        if (enabledAttribute >= 0) GLES20.glDisableVertexAttribArray(enabledAttribute)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        for (i in CAPABILITIES_KEPT_OFF.indices) {
            if (switchedOff and (1 shl i) != 0) GLES20.glEnable(CAPABILITIES_KEPT_OFF[i])
        }
        GLES20.glActiveTexture(activeTexture)

        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                // Drain the queue so the next stage does not report this frame's errors as its own.
            }
            throw VideoFrameProcessingException("GL error 0x${Integer.toHexString(error)} while drawing masks", presentationTimeUs)
        }
    }

    override fun release() {
        try {
            releaseGlObjects()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        } finally {
            super.release()
        }
    }

    /** Samples the timeline and fills [records]. Returns how many masks have something to draw. */
    private fun prepareMasks(presentationTimeUs: Long): Int {
        val timeline = source.maskTimeline ?: return 0
        val offsetUs = source.timeOffsetUs
        try {
            if (timeline.isEmpty) return 0
            timeline.sample(presentationTimeUs + offsetUs, batch, includeHidden = false)
        } catch (e: RuntimeException) {
            // Failing the frame stops an export; drawing it without its masks would publish the faces.
            throw VideoFrameProcessingException("Mask timeline failed at ${presentationTimeUs + offsetUs} us", e, presentationTimeUs)
        }
        val sampled = batch.count
        if (sampled == 0) return 0
        if (records.size < sampled * MaskMath.STRIDE) {
            records = FloatArray(maxOf(sampled, records.size / MaskMath.STRIDE * 2) * MaskMath.STRIDE)
        }
        var drawn = 0
        for (i in 0 until sampled) {
            if (MaskMath.writeRecord(batch, i, frameWidth, frameHeight, levelCount, useHdr, records, drawn * MaskMath.STRIDE)) {
                drawn++
            }
        }
        return drawn
    }

    private fun highestLevelUsed(maskCount: Int): Int {
        var top = 0
        for (i in 0 until maskCount) {
            val level = records[i * MaskMath.STRIDE + MaskMath.LEVEL_HI].toInt()
            if (level > top) top = level
        }
        return top
    }

    private fun buildPyramid(inputTexId: Int, topLevel: Int) {
        val program = downsample ?: return
        setInputFilter(GLES20.GL_LINEAR)
        use(program)
        for (level in 1..topLevel) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, levelFramebuffer[level])
            GLES20.glViewport(0, 0, levelWidth[level], levelHeight[level])
            // The quad overwrites every texel; clearing first tells a tiled GPU not to load the old contents.
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (level == 1) inputTexId else levelTexture[level - 1])
            GLES20.glUniform2f(downsampleTap, TAP_OFFSET / levelWidth[level - 1], TAP_OFFSET / levelHeight[level - 1])
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    private fun drawMasks(maskCount: Int) {
        val blendWasOn = GLES20.glIsEnabled(GLES20.GL_BLEND)
        GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_RGB, scratch, 0)
        GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_RGB, scratch, 1)
        GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_ALPHA, scratch, 2)
        GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_ALPHA, scratch, 3)
        GLES20.glGetIntegerv(GLES20.GL_BLEND_EQUATION_RGB, scratch, 4)
        GLES20.glGetIntegerv(GLES20.GL_BLEND_EQUATION_ALPHA, scratch, 5)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        // The frame's own alpha stays as copied; only colour is mixed.
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ZERO, GLES20.GL_ONE)

        val r = records
        for (i in 0 until maskCount) {
            val o = i * MaskMath.STRIDE
            val style = r[o + MaskMath.STYLE].toInt()
            val program = when (style) {
                MaskMath.STYLE_MOSAIC -> mosaic
                MaskMath.STYLE_SOLID -> solid
                else -> blur
            } ?: continue
            use(program)
            GLES20.glUniform4f(quadUniform[style], r[o + MaskMath.QUAD], r[o + MaskMath.QUAD + 1], r[o + MaskMath.QUAD + 2], r[o + MaskMath.QUAD + 3])
            GLES20.glUniform2f(centreUniform[style], r[o + MaskMath.CENTRE], r[o + MaskMath.CENTRE + 1])
            GLES20.glUniform4f(toUnitUniform[style], r[o + MaskMath.TO_UNIT], r[o + MaskMath.TO_UNIT + 1], r[o + MaskMath.TO_UNIT + 2], r[o + MaskMath.TO_UNIT + 3])
            GLES20.glUniform4f(shapeUniform[style], r[o + MaskMath.SHAPE], r[o + MaskMath.SHAPE + 1], r[o + MaskMath.SHAPE + 2], r[o + MaskMath.SHAPE + 3])
            GLES20.glUniform1f(ovalUniform[style], r[o + MaskMath.OVAL])
            if (style == MaskMath.STYLE_SOLID) {
                GLES20.glUniform3f(solidColor, r[o + MaskMath.COLOR], r[o + MaskMath.COLOR + 1], r[o + MaskMath.COLOR + 2])
            } else {
                val lo = r[o + MaskMath.LEVEL_LO].toInt()
                val hi = r[o + MaskMath.LEVEL_HI].toInt()
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, levelTexture[hi])
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, levelTexture[lo])
                GLES20.glUniform1f(levelMixUniform[style], r[o + MaskMath.LEVEL_MIX])
                if (style == MaskMath.STYLE_MOSAIC) {
                    GLES20.glUniform3f(mosaicGrid, r[o + MaskMath.GRID], r[o + MaskMath.GRID + 1], r[o + MaskMath.GRID + 2])
                    GLES20.glUniform2f(mosaicGridOrigin, r[o + MaskMath.CENTRE], r[o + MaskMath.CENTRE + 1])
                    GLES20.glUniform4f(mosaicBlockStep, r[o + MaskMath.BLOCK_STEP], r[o + MaskMath.BLOCK_STEP + 1], r[o + MaskMath.BLOCK_STEP + 2], r[o + MaskMath.BLOCK_STEP + 3])
                } else {
                    GLES20.glUniform4f(blurLoSize, levelWidth[lo].toFloat(), levelHeight[lo].toFloat(), 1f / levelWidth[lo], 1f / levelHeight[lo])
                    GLES20.glUniform4f(blurHiSize, levelWidth[hi].toFloat(), levelHeight[hi].toFloat(), 1f / levelWidth[hi], 1f / levelHeight[hi])
                }
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES20.glBlendFuncSeparate(scratch[0], scratch[1], scratch[2], scratch[3])
        GLES20.glBlendEquationSeparate(scratch[4], scratch[5])
        if (!blendWasOn) GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Makes [program] current and points its corner attribute at the shared quad, which must be bound. */
    private fun use(program: LinkedProgram) {
        if (currentProgram == program.id) return
        GLES20.glUseProgram(program.id)
        currentProgram = program.id
        if (enabledAttribute != program.cornerAttribute) {
            if (enabledAttribute >= 0) GLES20.glDisableVertexAttribArray(enabledAttribute)
            GLES20.glEnableVertexAttribArray(program.cornerAttribute)
            GLES20.glVertexAttribPointer(program.cornerAttribute, 2, GLES20.GL_FLOAT, false, 0, 0)
            enabledAttribute = program.cornerAttribute
        }
    }

    /** Applies to the texture bound on unit 0, which is the input frame whenever this is called. */
    private fun setInputFilter(filter: Int) {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
    }

    private fun readMaskUniforms(program: LinkedProgram, style: Int) {
        quadUniform[style] = program.uniform("uQuad")
        centreUniform[style] = program.uniform("uCentre")
        toUnitUniform[style] = program.uniform("uToUnit")
        shapeUniform[style] = program.uniform("uShape")
        ovalUniform[style] = program.uniform("uOval")
        levelMixUniform[style] = program.uniform("uLevelMix")
    }

    private fun createCornerBuffer(): Int {
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val data = ByteBuffer.allocateDirect(corners.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.put(corners).position(0)
        val id = IntArray(1)
        GLES20.glGenBuffers(1, id, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, corners.size * Float.SIZE_BYTES, data, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return id[0]
    }

    private fun createPyramid(width: Int, height: Int) {
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, scratch, 0)
        val boundFramebuffer = scratch[0]
        GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, scratch, 0)
        val boundTexture = scratch[0]
        val count = MaskMath.pyramidSizes(width, height, levelWidth, levelHeight)
        try {
            for (level in 1..count) {
                // Levels match the frame's precision: RGBA16F for HDR, RGBA8 otherwise. Linear, clamped.
                levelTexture[level] = GlUtil.createTexture(levelWidth[level], levelHeight[level], useHdr)
                levelFramebuffer[level] = GlUtil.createFboForTexture(levelTexture[level])
                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    throw GlUtil.GlException(
                        "Pyramid level $level (${levelWidth[level]} x ${levelHeight[level]}, hdr=$useHdr) " +
                            "is not renderable: status 0x${Integer.toHexString(status)}",
                    )
                }
            }
            levelCount = count
        } catch (e: GlUtil.GlException) {
            deletePyramid()
            throw e
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, boundFramebuffer)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, boundTexture)
        }
    }

    private fun deletePyramid() {
        for (level in 1..MaskMath.MAX_LEVELS) {
            if (levelFramebuffer[level] != 0) {
                GLES20.glDeleteFramebuffers(1, levelFramebuffer, level)
                levelFramebuffer[level] = 0
            }
            if (levelTexture[level] != 0) {
                GLES20.glDeleteTextures(1, levelTexture, level)
                levelTexture[level] = 0
            }
        }
        levelCount = 0
        GlUtil.checkGlError()
    }

    private fun releaseGlObjects() {
        if (released) return
        released = true
        deletePyramid()
        if (cornerBuffer != 0) {
            scratch[0] = cornerBuffer
            GLES20.glDeleteBuffers(1, scratch, 0)
            cornerBuffer = 0
        }
        copy?.delete()
        downsample?.delete()
        blur?.delete()
        mosaic?.delete()
        solid?.delete()
        copy = null
        downsample = null
        blur = null
        mosaic = null
        solid = null
    }

    private fun releaseQuietly() {
        try {
            releaseGlObjects()
        } catch (e: GlUtil.GlException) {
            // The build failure that led here is the error worth reporting.
        }
    }

    private companion object {
        const val INITIAL_MASK_CAPACITY = 32
        const val STYLE_COUNT = 3

        /** Distance of each downsampling tap from the output texel's centre, in source texels. */
        const val TAP_OFFSET = 0.75f

        val CAPABILITIES_KEPT_OFF = intArrayOf(
            GLES20.GL_SCISSOR_TEST,
            GLES20.GL_DEPTH_TEST,
            GLES20.GL_STENCIL_TEST,
            GLES20.GL_CULL_FACE,
            GLES20.GL_DITHER,
        )
    }
}
