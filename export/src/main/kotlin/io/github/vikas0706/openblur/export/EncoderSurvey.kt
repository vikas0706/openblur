package io.github.vikas0706.openblur.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.EncoderUtil
import io.github.vikas0706.openblur.core.EncoderCandidate
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.core.TransferKind
import java.util.Locale
import kotlin.math.floor

/** Reads what this device's video encoders say they can do with one particular source. */
internal object EncoderSurvey {
    private val encoders: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            // An alias repeats another entry under a second name.
            info.isEncoder && !(Build.VERSION.SDK_INT >= 29 && info.isAlias)
        }
    }

    /** One candidate per encoder and video type the MP4 muxer can write. */
    fun candidates(source: SourceVideoInfo): List<EncoderCandidate> {
        val geometry = OutputGeometry.forSource(source)
        val hdrColor = hdrColorInfo(source.transfer)
        val frameRate = if (source.frameRate > 0f) floor(source.frameRate.toDouble()).coerceAtLeast(1.0) else 0.0
        val result = ArrayList<EncoderCandidate>()
        for (info in encoders) {
            for (type in info.supportedTypes) {
                val mimeType = type.lowercase(Locale.ROOT)
                if (mimeType !in Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES) continue
                // Vendor capability tables are sometimes broken; one bad entry must not hide the other encoders.
                val candidate = try {
                    candidate(info, type, mimeType, geometry, frameRate, hdrColor)
                } catch (e: RuntimeException) {
                    null
                }
                if (candidate != null) result.add(candidate)
            }
        }
        return result
    }

    /** Highest bitrate [encoderName] accepts for [mimeType], or null when the encoder is not listed. */
    fun maxBitrate(encoderName: String, mimeType: String): Int? {
        val info = encoders.firstOrNull { it.name == encoderName } ?: return null
        return try {
            EncoderUtil.getSupportedBitrateRange(info, mimeType).upper
        } catch (e: RuntimeException) {
            null
        }
    }

    /** [bitrate] limited to what the encoder Transformer tries first for [mimeType] accepts. */
    fun clampToFirstEncoder(mimeType: String, bitrate: Int): Int = try {
        val first = EncoderSelector.DEFAULT.selectEncoderInfos(mimeType).firstOrNull()
        if (first == null) bitrate else EncoderUtil.getSupportedBitrateRange(first, mimeType).clamp(bitrate)
    } catch (e: RuntimeException) {
        bitrate
    }

    /** True when an encoder Transformer would consider for [mimeType] takes exactly this picture size. */
    fun anySupportsSize(mimeType: String, width: Int, height: Int): Boolean = try {
        EncoderSelector.DEFAULT.selectEncoderInfos(mimeType).any { EncoderUtil.isSizeSupported(it, mimeType, width, height) }
    } catch (e: RuntimeException) {
        false
    }

    private fun candidate(
        info: MediaCodecInfo,
        type: String,
        mimeType: String,
        geometry: OutputGeometry,
        frameRate: Double,
        hdrColor: ColorInfo?,
    ): EncoderCandidate? {
        val capabilities = info.getCapabilitiesForType(type)
        val video = capabilities.videoCapabilities ?: return null
        val width = geometry.encodedWidth
        val height = geometry.encodedHeight
        // Media3's check also accepts 1080p and 2160p on devices that under-report those heights.
        val supportsSize = EncoderUtil.isSizeSupported(info, type, width, height)
        val supportsSizeAndRate = when {
            !supportsSize -> false
            frameRate <= 0.0 -> true
            else -> video.areSizeAndRateSupported(width, height, frameRate) ||
                coveredByPerformancePoint(video, width, height, frameRate.toInt())
        }
        // Transformer needs both the editing feature and a 10-bit input surface before it keeps HDR.
        val supportsSourceHdr = hdrColor != null &&
            Build.VERSION.SDK_INT >= 33 &&
            EncoderUtil.isHdrEditingSupported(info, mimeType, hdrColor) &&
            capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR2101010)
        return EncoderCandidate(
            name = info.name,
            mimeType = mimeType,
            isHardware = EncoderUtil.isHardwareAccelerated(info, type),
            maxBitrate = video.bitrateRange.upper,
            supportsSize = supportsSize,
            supportsSizeAndRate = supportsSizeAndRate,
            supportsSourceHdr = supportsSourceHdr,
        )
    }

    private fun coveredByPerformancePoint(
        video: MediaCodecInfo.VideoCapabilities,
        width: Int,
        height: Int,
        frameRate: Int,
    ): Boolean = Build.VERSION.SDK_INT >= 29 && Api29.covers(video, width, height, frameRate)

    /** Holds the reference to a class older devices lack, so the survey's own code verifies on them. */
    @RequiresApi(29)
    private object Api29 {
        fun covers(video: MediaCodecInfo.VideoCapabilities, width: Int, height: Int, frameRate: Int): Boolean {
            val points = video.supportedPerformancePoints ?: return false
            val wanted = MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, frameRate)
            return points.any { it.covers(wanted) }
        }
    }

    private fun hdrColorInfo(transfer: TransferKind): ColorInfo? {
        val colorTransfer = when (transfer) {
            TransferKind.SDR -> return null
            TransferKind.HLG -> C.COLOR_TRANSFER_HLG
            TransferKind.PQ -> C.COLOR_TRANSFER_ST2084
        }
        return ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorTransfer(colorTransfer)
            .build()
    }
}
