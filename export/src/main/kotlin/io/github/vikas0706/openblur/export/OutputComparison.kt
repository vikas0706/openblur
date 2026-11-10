package io.github.vikas0706.openblur.export

import io.github.vikas0706.openblur.core.EncodePlan
import io.github.vikas0706.openblur.core.PlanNotice
import io.github.vikas0706.openblur.core.SourceVideoInfo
import kotlin.math.abs

/** What reading the saved file back shows, one flag per property that no longer matches the source. */
internal data class OutputDifferences(
    val codecChanged: Boolean,
    val codedSizeChanged: Boolean,
    val rotationChanged: Boolean,
    /** More than one frame apart. False when either count is unknown. */
    val frameCountChanged: Boolean,
    val hdrLost: Boolean,
    val tenBitSdrReduced: Boolean,
    val dolbyVisionLost: Boolean,
    val audioTracksDropped: Boolean,
    val audioReencoded: Boolean,
    val bitrateCapped: Boolean,
) {
    val any: Boolean
        get() = codecChanged || codedSizeChanged || rotationChanged || frameCountChanged || hdrLost ||
            tenBitSdrReduced || dolbyVisionLost || audioTracksDropped || audioReencoded || bitrateCapped
}

internal object OutputComparison {
    const val MIME_H264 = "video/avc"
    const val MIME_HEVC = "video/hevc"

    /**
     * [audioCopied] comes from the export result. [encoderMaxBitrate] is the ceiling of the encoder that
     * ran, or null when it is unknown.
     */
    fun differences(
        source: SourceVideoInfo,
        output: SourceVideoInfo,
        audioCopied: Boolean,
        planBitrate: Int,
        encoderMaxBitrate: Int?,
    ): OutputDifferences {
        val bothHaveAudio = source.audioTrackCount > 0 && output.audioTrackCount > 0
        return OutputDifferences(
            codecChanged = !source.mimeType.equals(output.mimeType, ignoreCase = true),
            codedSizeChanged = source.codedWidth != output.codedWidth || source.codedHeight != output.codedHeight,
            rotationChanged = source.rotationDegrees != output.rotationDegrees,
            frameCountChanged = source.frameCount > 0 && output.frameCount > 0 &&
                abs(source.frameCount - output.frameCount) > 1,
            hdrLost = source.isHdr && !output.isHdr,
            tenBitSdrReduced = !source.isHdr && source.bitDepth > 8 && output.bitDepth <= 8,
            dolbyVisionLost = source.isDolbyVision && !output.isDolbyVision,
            audioTracksDropped = output.audioTrackCount < source.audioTrackCount,
            audioReencoded = bothHaveAudio &&
                (!audioCopied || !source.audioMimeType.equals(output.audioMimeType, ignoreCase = true)),
            bitrateCapped = encoderMaxBitrate != null && encoderMaxBitrate in 1 until planBitrate,
        )
    }

    /**
     * What the plan announced, what Transformer reported while it ran ([observed]) and what [differences]
     * found. The plan's size warning is dropped once the saved file is known to match the source.
     */
    fun notices(
        plan: EncodePlan,
        observed: Set<PlanNotice>,
        output: SourceVideoInfo,
        differences: OutputDifferences,
    ): Set<PlanNotice> {
        val notices = LinkedHashSet<PlanNotice>(plan.notices)
        notices.addAll(observed)
        val sizeMatches = !differences.codedSizeChanged && !differences.rotationChanged
        if (sizeMatches) notices.remove(PlanNotice.SIZE_MAY_BE_ADJUSTED) else notices.add(PlanNotice.SIZE_MAY_BE_ADJUSTED)
        if (differences.codecChanged) {
            when (output.mimeType.lowercase()) {
                MIME_H264 -> notices.add(PlanNotice.CODEC_CHANGED_TO_H264)
                MIME_HEVC -> notices.add(PlanNotice.CODEC_CHANGED_TO_HEVC)
            }
        }
        if (differences.hdrLost) notices.add(PlanNotice.HDR_TONE_MAPPED_TO_SDR)
        if (differences.tenBitSdrReduced) notices.add(PlanNotice.TEN_BIT_SDR_REDUCED_TO_EIGHT)
        if (differences.dolbyVisionLost) notices.add(PlanNotice.DOLBY_VISION_METADATA_DROPPED)
        if (differences.audioTracksDropped) notices.add(PlanNotice.EXTRA_AUDIO_TRACKS_DROPPED)
        if (differences.audioReencoded) notices.add(PlanNotice.AUDIO_REENCODED)
        if (differences.bitrateCapped) notices.add(PlanNotice.BITRATE_CAPPED_BY_ENCODER)
        return notices
    }

    /** Text for the log; a frame count or rotation change has no [PlanNotice] of its own. */
    fun describe(source: SourceVideoInfo, output: SourceVideoInfo, differences: OutputDifferences): String {
        val parts = ArrayList<String>()
        if (differences.codecChanged) parts.add("codec ${source.mimeType} -> ${output.mimeType}")
        if (differences.codedSizeChanged) {
            parts.add("coded size ${source.codedWidth}x${source.codedHeight} -> ${output.codedWidth}x${output.codedHeight}")
        }
        if (differences.rotationChanged) parts.add("rotation ${source.rotationDegrees} -> ${output.rotationDegrees}")
        if (differences.frameCountChanged) parts.add("frames ${source.frameCount} -> ${output.frameCount}")
        if (differences.hdrLost) parts.add("transfer ${source.transfer} -> ${output.transfer}")
        if (differences.tenBitSdrReduced) parts.add("bit depth ${source.bitDepth} -> ${output.bitDepth}")
        if (differences.dolbyVisionLost) parts.add("Dolby Vision metadata dropped")
        if (differences.audioTracksDropped) parts.add("audio tracks ${source.audioTrackCount} -> ${output.audioTrackCount}")
        if (differences.audioReencoded) parts.add("audio ${source.audioMimeType} -> ${output.audioMimeType}, re-encoded")
        if (differences.bitrateCapped) parts.add("bitrate capped by the encoder")
        return parts.joinToString("; ")
    }
}
