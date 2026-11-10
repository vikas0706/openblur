@file:OptIn(UnstableApi::class)

package io.github.vikas0706.openblur.analysis

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Feeds every frame of a Media3 pipeline to a [ScanSession]. The frames it passes on are blank. */
internal class ScanEffect(private val session: ScanSession) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        // The scan always asks for SDR output. Float HDR frames would need a tone map the detector was not tuned for.
        if (useHdr) throw VideoFrameProcessingException("The scan needs SDR frames but the pipeline is HDR")
        return ScanShaderProgram(session)
    }
}

private class ScanShaderProgram(private val session: ScanSession) :
    BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {

    private val sampler = FrameSampler()

    // Media3 runs GL tasks as futures, which swallow an Error; anything not rethrown as its exception type
    // would stall the pipeline instead of failing it.
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        try {
            session.configure(inputWidth, inputHeight)
        } catch (t: Throwable) {
            throw VideoFrameProcessingException(t)
        }
        return Size(OUTPUT_SIDE, OUTPUT_SIDE)
    }

    // The base class has already bound and cleared the output texture, and nothing reads it, so it stays blank.
    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            session.frame(sampler, inputTexId, presentationTimeUs)
        } catch (t: Throwable) {
            throw VideoFrameProcessingException(t, presentationTimeUs)
        }
    }

    override fun release() {
        try {
            sampler.release()
        } finally {
            super.release()
        }
    }

    private companion object {
        // The pipeline still renders this program's output into the discarding encoder's surface.
        const val OUTPUT_SIDE = 16
    }
}
