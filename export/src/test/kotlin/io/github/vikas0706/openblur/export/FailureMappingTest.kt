package io.github.vikas0706.openblur.export

import androidx.media3.transformer.ExportException
import io.github.vikas0706.openblur.api.ExportFailure
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureMappingTest {
    private fun verdict(failure: ExportFailure, tryNextPlan: Boolean) = FailureVerdict(failure, tryNextPlan)

    @Test
    fun encoderErrorsMoveOnToTheNextPlan() {
        val expected = verdict(ExportFailure.ENCODER_FAILED, tryNextPlan = true)
        assertEquals(expected, FailureMapping.classify(ExportException.ERROR_CODE_ENCODER_INIT_FAILED, null))
        assertEquals(expected, FailureMapping.classify(ExportException.ERROR_CODE_ENCODING_FAILED, null))
        assertEquals(expected, FailureMapping.classify(ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED, null))
        assertEquals(expected, FailureMapping.classify(ExportException.ERROR_CODE_MUXING_TIMEOUT, null))
    }

    @Test
    fun decoderThatCannotStartMovesOnButABadSampleStops() {
        assertEquals(
            verdict(ExportFailure.DECODER_FAILED, tryNextPlan = true),
            FailureMapping.classify(ExportException.ERROR_CODE_DECODER_INIT_FAILED, null),
        )
        assertEquals(
            verdict(ExportFailure.DECODER_FAILED, tryNextPlan = true),
            FailureMapping.classify(ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, null),
        )
        assertEquals(
            verdict(ExportFailure.DECODER_FAILED, tryNextPlan = false),
            FailureMapping.classify(ExportException.ERROR_CODE_DECODING_FAILED, null),
        )
    }

    @Test
    fun everyIoCodeMeansTheSourceCannotBeReadAndStops() {
        val codes = listOf(
            ExportException.ERROR_CODE_IO_UNSPECIFIED,
            ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            ExportException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            ExportException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
            ExportException.ERROR_CODE_IO_NO_PERMISSION,
            ExportException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            ExportException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        )
        val expected = verdict(ExportFailure.SOURCE_UNREADABLE, tryNextPlan = false)
        for (code in codes) {
            assertEquals("code $code", expected, FailureMapping.classify(code, null))
        }
    }

    @Test
    fun fullDiskIsRecognisedWhateverTheCode() {
        val full = IOException("write failed: ENOSPC (No space left on device)")
        val expected = verdict(ExportFailure.OUT_OF_SPACE, tryNextPlan = false)
        assertEquals(expected, FailureMapping.classify(ExportException.ERROR_CODE_MUXING_FAILED, full))
        assertEquals(expected, FailureMapping.classify(ExportException.ERROR_CODE_ENCODING_FAILED, RuntimeException("wrapper", full)))
        assertEquals(
            expected,
            FailureMapping.classify(ExportException.ERROR_CODE_UNSPECIFIED, IllegalStateException("No space left on device")),
        )
    }

    @Test
    fun muxerIoErrorIsASaveFailureAndStops() {
        val error = RuntimeException("Muxer error", IOException("EIO"))
        assertEquals(
            verdict(ExportFailure.SAVE_FAILED, tryNextPlan = false),
            FailureMapping.classify(ExportException.ERROR_CODE_MUXING_FAILED, error),
        )
    }

    @Test
    fun muxerRefusingAFormatMovesOnToTheNextPlan() {
        val error = RuntimeException("Muxer error", IllegalArgumentException("Unsupported track format"))
        assertEquals(
            verdict(ExportFailure.ENCODER_FAILED, tryNextPlan = true),
            FailureMapping.classify(ExportException.ERROR_CODE_MUXING_FAILED, error),
        )
    }

    @Test
    fun audioFailureStopsBecauseEveryPlanCopiesTheSameAudio() {
        assertEquals(
            verdict(ExportFailure.UNKNOWN, tryNextPlan = false),
            FailureMapping.classify(ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED, null),
        )
    }

    @Test
    fun frameProcessingAndUnknownCodesTryTheNextPlan() {
        val expected = verdict(ExportFailure.UNKNOWN, tryNextPlan = true)
        assertEquals(expected, FailureMapping.classify(ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED, null))
        assertEquals(expected, FailureMapping.classify(ExportException.ERROR_CODE_UNSPECIFIED, null))
        assertEquals(expected, FailureMapping.classify(ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK, null))
        assertEquals(expected, FailureMapping.classify(987_654, null))
    }

    @Test
    fun outOfSpaceCheckSurvivesACauseChainThatLoops() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)
        assertFalse(FailureMapping.isOutOfSpace(first))
        assertFalse(FailureMapping.isOutOfSpace(null))
        assertTrue(FailureMapping.isOutOfSpace(RuntimeException("outer", IOException("ENOSPC"))))
    }

    @Test
    fun detailCarriesCodeNameCodecMessageAndCauses() {
        val cause = IllegalStateException("configure failed", IllegalArgumentException("bad size"))
        val detail = FailureMapping.describe(
            codeName = ExportException.getErrorCodeName(ExportException.ERROR_CODE_ENCODER_INIT_FAILED),
            errorCode = ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            codecName = "c2.test.hevc.encoder",
            message = "Codec exception",
            cause = cause,
        )
        assertEquals(
            "ERROR_CODE_ENCODER_INIT_FAILED (4001); codec=c2.test.hevc.encoder; Codec exception; " +
                "caused by IllegalStateException: configure failed; caused by IllegalArgumentException: bad size",
            detail,
        )
    }

    @Test
    fun detailSaysWhenNoCodecWasInvolved() {
        assertEquals(
            "ERROR_CODE_IO_FILE_NOT_FOUND (2005); codec=none",
            FailureMapping.describe("ERROR_CODE_IO_FILE_NOT_FOUND", 2005, null, null, null),
        )
    }
}
