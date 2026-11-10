@file:OptIn(UnstableApi::class)

package io.github.vikas0706.openblur.analysis

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import io.github.vikas0706.openblur.api.AnalysisException
import io.github.vikas0706.openblur.api.AnalysisException.Kind
import java.io.IOException

/** Which part of the scan's own per-frame work threw. */
internal enum class FailureStage { MODEL, GRAPHICS }

internal class ScanFailure(val stage: FailureStage, val cause: Throwable)

/** A GL call of the scan's own rendering failed. [codes] holds the glGetError values, when there were any. */
internal class GlFailure(message: String, val codes: IntArray = IntArray(0)) : RuntimeException(message)

internal object ScanErrors {
    private const val GL_OUT_OF_MEMORY = 0x0505
    private const val MAX_CAUSE_DEPTH = 32

    /**
     * [playbackErrorCode] is the code of a PlaybackException found among the causes: Media3 reports player
     * errors it has no export code for as ERROR_CODE_UNSPECIFIED, which would otherwise hide a damaged file.
     */
    fun kindOf(
        exportErrorCode: Int,
        playbackErrorCode: Int?,
        stage: FailureStage?,
        vararg causes: Throwable?,
    ): Kind {
        if (causes.any(::isOutOfMemory)) return Kind.OUT_OF_MEMORY
        when (stage) {
            FailureStage.MODEL -> return Kind.MODEL_FAILED
            FailureStage.GRAPHICS -> return Kind.DECODER_FAILED
            null -> Unit
        }
        return when (exportErrorCode) {
            in 2000..2999 -> Kind.SOURCE_UNREADABLE
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
            -> Kind.DECODER_FAILED
            ExportException.ERROR_CODE_UNSPECIFIED,
            ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK,
            -> kindOfPlayback(playbackErrorCode) ?: kindOfCauses(causes)
            else -> Kind.UNKNOWN
        }
    }

    /** For failures that never went through Media3, such as the analyzer's finish step. */
    fun kindOf(stage: FailureStage?, cause: Throwable): Kind = when {
        isOutOfMemory(cause) -> Kind.OUT_OF_MEMORY
        stage == FailureStage.MODEL -> Kind.MODEL_FAILED
        stage == FailureStage.GRAPHICS -> Kind.DECODER_FAILED
        else -> Kind.UNKNOWN
    }

    fun fromExport(error: ExportException, failure: ScanFailure?): AnalysisException {
        val playback = causeChain(error).firstNotNullOfOrNull { it as? PlaybackException }
        val kind = kindOf(error.errorCode, playback?.errorCode, failure?.stage, failure?.cause, error)
        val detail = (failure?.cause ?: rootCause(error)).let { it.message ?: it.javaClass.name }
        return AnalysisException(kind, "Scan failed, ${error.errorCodeName}: $detail", error)
    }

    fun fromThrowable(stage: FailureStage?, what: String, cause: Throwable): AnalysisException =
        AnalysisException(kindOf(stage, cause), "$what: ${cause.message ?: cause.javaClass.name}", cause)

    fun isOutOfMemory(error: Throwable?): Boolean = causeChain(error).any {
        it is OutOfMemoryError ||
            (it is GlFailure && GL_OUT_OF_MEMORY in it.codes) ||
            (it is GlUtil.GlException && GL_OUT_OF_MEMORY in it.errorCodes)
    }

    private fun kindOfPlayback(code: Int?): Kind? = when (code) {
        null -> null
        in 2000..3999 -> Kind.SOURCE_UNREADABLE
        in 4000..4999 -> Kind.DECODER_FAILED
        else -> null
    }

    private fun kindOfCauses(causes: Array<out Throwable?>): Kind {
        val unreadable = causes.any { cause -> causeChain(cause).any { it is IOException || it is SecurityException } }
        return if (unreadable) Kind.SOURCE_UNREADABLE else Kind.UNKNOWN
    }

    private fun rootCause(error: Throwable): Throwable = causeChain(error).last()

    /** Bounded so a cause cycle cannot spin forever. */
    private fun causeChain(error: Throwable?): List<Throwable> {
        val chain = ArrayList<Throwable>()
        var current = error
        while (current != null && chain.size < MAX_CAUSE_DEPTH && chain.none { it === current }) {
            chain.add(current)
            current = current.cause
        }
        return chain
    }
}
