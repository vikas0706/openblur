package io.github.vikas0706.openblur.analysis

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.GlUtil
import androidx.media3.transformer.ExportException
import io.github.vikas0706.openblur.api.AnalysisException.Kind
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanErrorsTest {
    private fun kind(code: Int, playback: Int? = null, stage: FailureStage? = null, vararg causes: Throwable?) =
        ScanErrors.kindOf(code, playback, stage, *causes)

    @Test
    fun ioCodesMeanTheSourceIsUnreadable() {
        for (code in 2000..2008) assertEquals("code $code", Kind.SOURCE_UNREADABLE, kind(code))
    }

    @Test
    fun decoderAndFrameProcessingCodesMeanTheDecoderFailed() {
        assertEquals(Kind.DECODER_FAILED, kind(ExportException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(Kind.DECODER_FAILED, kind(ExportException.ERROR_CODE_DECODING_FAILED))
        assertEquals(Kind.DECODER_FAILED, kind(ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(Kind.DECODER_FAILED, kind(ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED))
    }

    @Test
    fun codesTheScanCannotProduceAreUnknown() {
        val codes = listOf(
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED,
            ExportException.ERROR_CODE_MUXING_FAILED,
            ExportException.ERROR_CODE_MUXING_TIMEOUT,
            ExportException.ERROR_CODE_MUXING_APPEND,
            12345,
        )
        for (code in codes) assertEquals("code $code", Kind.UNKNOWN, kind(code))
    }

    @Test
    fun unspecifiedCodeFallsBackToThePlayerErrorCode() {
        val unspecified = ExportException.ERROR_CODE_UNSPECIFIED
        assertEquals(
            Kind.SOURCE_UNREADABLE,
            kind(unspecified, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
        )
        assertEquals(
            Kind.SOURCE_UNREADABLE,
            kind(unspecified, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED),
        )
        assertEquals(Kind.SOURCE_UNREADABLE, kind(unspecified, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals(
            Kind.DECODER_FAILED,
            kind(unspecified, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES),
        )
        assertEquals(Kind.DECODER_FAILED, kind(unspecified, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED))
        assertEquals(Kind.UNKNOWN, kind(unspecified, PlaybackException.ERROR_CODE_TIMEOUT))
    }

    @Test
    fun unspecifiedCodeWithoutPlayerErrorLooksAtTheCauses() {
        val unspecified = ExportException.ERROR_CODE_UNSPECIFIED
        val wrappedIo = RuntimeException("outer", FileNotFoundException("gone"))
        assertEquals(Kind.SOURCE_UNREADABLE, kind(unspecified, null, null, wrappedIo))
        assertEquals(Kind.SOURCE_UNREADABLE, kind(unspecified, null, null, null, SecurityException("no grant")))
        assertEquals(Kind.UNKNOWN, kind(unspecified, null, null, IllegalStateException("bug")))
        assertEquals(Kind.UNKNOWN, kind(ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK))
    }

    @Test
    fun analyzerFailuresAreModelFailuresWhateverTheExportCode() {
        val code = ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
        assertEquals(Kind.MODEL_FAILED, kind(code, null, FailureStage.MODEL, RuntimeException("ORT_FAIL")))
        assertEquals(Kind.MODEL_FAILED, kind(code, null, FailureStage.MODEL, UnsatisfiedLinkError("no libonnxruntime")))
        assertEquals(Kind.DECODER_FAILED, kind(code, null, FailureStage.GRAPHICS, GlFailure("bad", intArrayOf(0x0502))))
    }

    @Test
    fun outOfMemoryWinsOverEverythingElse() {
        val deep = RuntimeException("a", RuntimeException("b", OutOfMemoryError("heap")))
        assertEquals(Kind.OUT_OF_MEMORY, kind(ExportException.ERROR_CODE_IO_FILE_NOT_FOUND, null, null, deep))
        assertEquals(
            Kind.OUT_OF_MEMORY,
            kind(ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED, null, FailureStage.MODEL, null, deep),
        )
        assertEquals(Kind.OUT_OF_MEMORY, ScanErrors.kindOf(FailureStage.MODEL, deep))
    }

    @Test
    fun graphicsMemoryExhaustionCountsAsOutOfMemory() {
        val ours = GlFailure("target", intArrayOf(0x0505))
        val media3 = GlUtil.GlException("glError: out of memory", listOf(0x0505))
        assertTrue(ScanErrors.isOutOfMemory(ours))
        assertTrue(ScanErrors.isOutOfMemory(RuntimeException(media3)))
        assertFalse(ScanErrors.isOutOfMemory(GlFailure("target", intArrayOf(0x0502))))
        assertFalse(ScanErrors.isOutOfMemory(GlUtil.GlException("glError: invalid operation", listOf(0x0502))))
        assertFalse(ScanErrors.isOutOfMemory(null))
        assertEquals(
            Kind.OUT_OF_MEMORY,
            kind(ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED, null, FailureStage.GRAPHICS, ours),
        )
    }

    @Test
    fun failuresOutsideMedia3AreClassifiedByStage() {
        assertEquals(Kind.MODEL_FAILED, ScanErrors.kindOf(FailureStage.MODEL, IllegalStateException("x")))
        assertEquals(Kind.DECODER_FAILED, ScanErrors.kindOf(FailureStage.GRAPHICS, GlFailure("x")))
        assertEquals(Kind.UNKNOWN, ScanErrors.kindOf(null, IllegalStateException("x")))
    }

    @Test
    fun causeCyclesDoNotHang() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(ScanErrors.isOutOfMemory(a))
        assertEquals(Kind.UNKNOWN, kind(ExportException.ERROR_CODE_UNSPECIFIED, null, null, a))
    }

    @Test
    fun fromThrowableKeepsTheCauseAndNamesTheStep() {
        val cause = IllegalStateException("tensor shape")
        val error = ScanErrors.fromThrowable(FailureStage.MODEL, "Could not finish the scan", cause)
        assertEquals(Kind.MODEL_FAILED, error.kind)
        assertSame(cause, error.cause)
        assertEquals("Could not finish the scan: tensor shape", error.message)
    }

    @Test
    fun fromExportMapsARealExportException() {
        val notFound = ExportException.createForAssetLoader(
            IOException("open failed"),
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
        )
        val unreadable = ScanErrors.fromExport(notFound, failure = null)
        assertEquals(Kind.SOURCE_UNREADABLE, unreadable.kind)
        assertSame(notFound, unreadable.cause)
        assertTrue(unreadable.message!!.contains("ERROR_CODE_IO_FILE_NOT_FOUND"))
        assertTrue(unreadable.message!!.contains("open failed"))

        val modelCause = IllegalArgumentException("bad input name")
        val frameProcessing = ExportException.createForAssetLoader(
            RuntimeException(modelCause),
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        )
        val model = ScanErrors.fromExport(frameProcessing, ScanFailure(FailureStage.MODEL, modelCause))
        assertEquals(Kind.MODEL_FAILED, model.kind)
        assertTrue(model.message!!.contains("bad input name"))
    }
}
