package io.github.vikas0706.openblur.export

import android.content.Context
import android.media.MediaFormat
import android.media.metrics.LogSessionId
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultCodec
import androidx.media3.transformer.DefaultDecoderFactory

/**
 * Picks a video decoder that returns the file's own pixels, based on [DecoderCalibration].
 * Audio and HDR video go to Media3's default factory unchanged.
 */
internal class ExactDecoderFactory(
    context: Context,
    private val calibration: DecoderCalibration,
) : Codec.DecoderFactory {

    private val appContext = context.applicationContext
    private val default = DefaultDecoderFactory.Builder(appContext).build()
    private val softwareOnly = DefaultDecoderFactory.Builder(appContext)
        .setMediaCodecSelector { mime, secure, tunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            all.filter { !it.hardwareAccelerated || it.softwareOnly }.ifEmpty { all }
        }
        .build()

    /**
     * For a stream without a colour matrix tag: the matrix the GPU uses to turn its frames into RGB, as a
     * `C.COLOR_SPACE_*` value. The encoder must use the same one, or saturated colours shift. Null for tagged streams.
     */
    @Volatile
    var untaggedColorSpace: Int? = null
        private set

    /** What the last video decoder choice was, for the export report and logs. */
    @Volatile
    var lastChoice: DecoderCalibration.Verdict? = null
        private set

    override fun createForAudioDecoding(format: Format, logSessionId: LogSessionId?): Codec =
        default.createForAudioDecoding(format, logSessionId)

    override fun createForVideoDecoding(
        sourceFormat: Format,
        outputSurface: Surface,
        requestSdrToneMapping: Boolean,
        logSessionId: LogSessionId?,
    ): Codec {
        val format = sourceFormat
        val mime = format.sampleMimeType
        if (mime == null || ColorInfo.isTransferHdr(format.colorInfo)) {
            return default.createForVideoDecoding(format, outputSurface, requestSdrToneMapping, logSessionId)
        }
        val result = calibration.resultFor(mime)
        lastChoice = result.verdict
        val untagged = format.colorInfo?.colorSpace.let { it == null || it == Format.NO_VALUE }
        val filterOff = result.verdict == DecoderCalibration.Verdict.HARDWARE_WITH_FILTER_OFF
        var codec = if (filterOff) createWithFilterOff(format, outputSurface, result.hardwareName!!) else null
        // The filtered hardware output is what calibration rejected, so the software decoder is preferred over it.
        val useSoftware = (result.verdict == DecoderCalibration.Verdict.SOFTWARE_ONLY || (filterOff && codec == null)) &&
            softwareCanDecode(format)
        if (result.verdict == DecoderCalibration.Verdict.SOFTWARE_ONLY && !useSoftware) {
            Log.w(TAG, "No software decoder takes ${format.width}x${format.height}; using the hardware decoder")
        }
        if (codec == null) {
            codec = if (useSoftware) {
                softwareOnly.createForVideoDecoding(format, outputSurface, requestSdrToneMapping, logSessionId)
            } else {
                default.createForVideoDecoding(format, outputSurface, requestSdrToneMapping, logSessionId)
            }
        }
        if (untagged) {
            // Measured on the decoder that was actually created, since the GPU's matrix can differ per decoder.
            val decoderName = codec.name
            val measuredFilterOff = filterOff && !useSoftware && decoderName == result.hardwareName
            val media3Format = MediaFormatUtil.createMediaFormatFromFormat(format)
            val colorKeys = DecoderCalibration.COLOR_KEYS.filter { media3Format.containsKey(it) }.associateWith { media3Format.getInteger(it) }
            val measured = calibration.untaggedColorSpace(mime, decoderName, measuredFilterOff, format.width, format.height, colorKeys)
            // Without a measurement, keep Media3's own assumption (BT.709) rather than guess.
            untaggedColorSpace = measured
            Log.i(TAG, "Untagged stream converts to RGB as colour space $measured")
        }
        return codec
    }

    private fun softwareCanDecode(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false)
        val rate = if (format.frameRate > 0f) format.frameRate.toDouble() else 30.0
        return infos.any {
            (!it.hardwareAccelerated || it.softwareOnly) && it.isVideoSizeAndRateSupportedV21(format.width, format.height, rate)
        }
    }

    /** Mirrors Media3's SDR decoder configuration and adds the key that turns the vendor filter off. */
    private fun createWithFilterOff(format: Format, outputSurface: Surface, codecName: String): Codec? = try {
        val mediaFormat = MediaFormatUtil.createMediaFormatFromFormat(format)
        if (Build.VERSION.SDK_INT >= 29 && appContext.applicationInfo.targetSdkVersion >= 29) {
            mediaFormat.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0)
        }
        CodecSpecificDataUtil.getCodecProfileAndLevel(format)?.let {
            MediaFormatUtil.maybeSetInteger(mediaFormat, MediaFormat.KEY_PROFILE, it.first)
            MediaFormatUtil.maybeSetInteger(mediaFormat, MediaFormat.KEY_LEVEL, it.second)
        }
        if (Build.VERSION.SDK_INT >= 35) mediaFormat.setInteger(MediaFormat.KEY_IMPORTANCE, 0)
        mediaFormat.setInteger(DecoderCalibration.DOLBY_FILTER_KEY, DecoderCalibration.DOLBY_FILTER_BYPASS)
        DefaultCodec(appContext, format, mediaFormat, codecName, true, outputSurface)
    } catch (e: Exception) {
        Log.w(TAG, "Could not create $codecName with the filter off", e)
        null
    }

    private companion object {
        const val TAG = "OpenBlurDecoder"
    }
}
