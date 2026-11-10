package io.github.vikas0706.openblur.export

import androidx.media3.transformer.ExportException
import io.github.vikas0706.openblur.api.ExportFailure
import java.io.IOException

/** What an error means for the user, and whether a different encode plan could avoid it. */
internal data class FailureVerdict(val failure: ExportFailure, val tryNextPlan: Boolean)

/** Turns Media3 export error codes into [ExportFailure] values and bug report text. */
internal object FailureMapping {
    fun classify(errorCode: Int, cause: Throwable?): FailureVerdict {
        // A full disk surfaces as a muxer or IO error, so it is checked before the code.
        if (isOutOfSpace(cause)) return FailureVerdict(ExportFailure.OUT_OF_SPACE, tryNextPlan = false)
        return when (errorCode) {
            in ExportException.ERROR_CODE_IO_UNSPECIFIED..ExportException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
                FailureVerdict(ExportFailure.SOURCE_UNREADABLE, tryNextPlan = false)
            // Another plan changes the encoder and the HDR mode, and either can be what kept the decoder from starting.
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> FailureVerdict(ExportFailure.DECODER_FAILED, tryNextPlan = true)
            // A sample the decoder rejects mid-stream is rejected again under every plan.
            ExportException.ERROR_CODE_DECODING_FAILED -> FailureVerdict(ExportFailure.DECODER_FAILED, tryNextPlan = false)
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            -> FailureVerdict(ExportFailure.ENCODER_FAILED, tryNextPlan = true)
            // The muxer stops receiving samples when the encoder stalls.
            ExportException.ERROR_CODE_MUXING_TIMEOUT -> FailureVerdict(ExportFailure.ENCODER_FAILED, tryNextPlan = true)
            ExportException.ERROR_CODE_MUXING_FAILED,
            ExportException.ERROR_CODE_MUXING_APPEND,
            -> if (hasIoCause(cause)) {
                FailureVerdict(ExportFailure.SAVE_FAILED, tryNextPlan = false)
            } else {
                // Without an IO error the muxer refused the track format, which another codec can fix.
                FailureVerdict(ExportFailure.ENCODER_FAILED, tryNextPlan = true)
            }
            // The audio is the same under every plan.
            ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED -> FailureVerdict(ExportFailure.UNKNOWN, tryNextPlan = false)
            // GL failures often come from the HDR path, which a tone-mapping plan avoids.
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> FailureVerdict(ExportFailure.UNKNOWN, tryNextPlan = true)
            else -> FailureVerdict(ExportFailure.UNKNOWN, tryNextPlan = true)
        }
    }

    fun isOutOfSpace(error: Throwable?): Boolean = causes(error).any { cause ->
        val message = cause.message ?: return@any false
        message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true)
    }

    /** One line for a bug report: code name and number, codec, message, then the chain of causes. */
    fun describe(codeName: String, errorCode: Int, codecName: String?, message: String?, cause: Throwable?): String {
        val text = StringBuilder()
        text.append(codeName).append(" (").append(errorCode).append(')')
        text.append("; codec=").append(codecName ?: "none")
        if (!message.isNullOrBlank()) text.append("; ").append(message)
        for (link in causes(cause)) {
            text.append("; caused by ").append(link.javaClass.simpleName)
            link.message?.takeIf { it.isNotBlank() }?.let { text.append(": ").append(it) }
        }
        return text.toString()
    }

    private fun hasIoCause(error: Throwable?): Boolean = causes(error).any { it is IOException }

    private fun causes(error: Throwable?): List<Throwable> {
        val chain = ArrayList<Throwable>()
        var current = error
        // Bounded because a cause chain can loop back on itself.
        while (current != null && chain.size < 12 && chain.none { it === current }) {
            chain.add(current)
            current = current.cause
        }
        return chain
    }
}
