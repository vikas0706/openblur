@file:OptIn(UnstableApi::class)

package io.github.vikas0706.openblur.render

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import io.github.vikas0706.openblur.api.MaskEffectController
import io.github.vikas0706.openblur.api.MaskEffectFactory
import io.github.vikas0706.openblur.core.MaskTimeline

/**
 * Builds the OpenGL mask effect used by both the ExoPlayer preview and the Transformer export.
 * The shaders are compiled from strings in this module, so [context] is not read and not kept.
 */
class GlMaskEffectFactory(@Suppress("UNUSED_PARAMETER") context: Context) : MaskEffectFactory {
    override fun create(): MaskEffectController = GlMaskEffectController()
}

private class GlMaskEffectController : MaskEffectController, MaskSource {
    @Volatile
    override var maskTimeline: MaskTimeline? = null
        private set

    @Volatile
    override var timeOffsetUs: Long = 0L
        private set

    // One instance for the controller's lifetime: ExoPlayer rebuilds its whole pipeline when the effect
    // list stops being equal to the last one, and this object compares by identity.
    override val effect: Effect = GlEffect { _, useHdr -> MaskShaderProgram(useHdr, this) }

    override fun setTimeline(timeline: MaskTimeline?) {
        maskTimeline = timeline
    }

    override fun setTimeOffsetUs(offsetUs: Long) {
        timeOffsetUs = offsetUs
    }
}
