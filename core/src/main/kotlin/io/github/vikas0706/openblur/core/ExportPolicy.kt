package io.github.vikas0706.openblur.core

enum class TransferKind { SDR, HLG, PQ }

/** What a video file contains, as far as the export needs to reproduce it. Used for sources and for finished exports. */
data class SourceVideoInfo(
    /** Sample MIME type of the video track, for example "video/avc" or "video/hevc". */
    val mimeType: String,
    /** Encoded picture size, before [rotationDegrees] is applied. */
    val codedWidth: Int,
    val codedHeight: Int,
    /** 0, 90, 180 or 270: how a player turns the encoded picture for display. */
    val rotationDegrees: Int,
    /** Average frames per second; 0 when unknown. */
    val frameRate: Float,
    /** Average video bitrate in bits per second, measured from sample sizes; 0 when unknown. */
    val bitrate: Int,
    val durationUs: Long,
    /** 0 when unknown. */
    val frameCount: Int,
    /** Average seconds between keyframes; 0 when unknown. */
    val keyframeIntervalSeconds: Float,
    val transfer: TransferKind,
    /** 8 or 10. */
    val bitDepth: Int,
    val isDolbyVision: Boolean,
    /** Sample MIME type of the first audio track, or null when there is none. */
    val audioMimeType: String?,
    val audioTrackCount: Int,
    val hasLocation: Boolean,
    /** 0 when unknown. */
    val fileSizeBytes: Long,
) {
    val isRotatedSideways: Boolean get() = rotationDegrees % 180 != 0

    /** Size as displayed, which is the size frames have when analysis and rendering see them. */
    val displayWidth: Int get() = if (isRotatedSideways) codedHeight else codedWidth
    val displayHeight: Int get() = if (isRotatedSideways) codedWidth else codedHeight
    val isHdr: Boolean get() = transfer != TransferKind.SDR
}

/** One encoder on this device, already checked against one particular source. */
data class EncoderCandidate(
    val name: String,
    val mimeType: String,
    val isHardware: Boolean,
    /** Highest bitrate the encoder accepts, in bits per second. */
    val maxBitrate: Int,
    /** The encoder accepts the source's exact coded size. */
    val supportsSize: Boolean,
    /** The encoder states it can keep up with the source's size at the source's frame rate. */
    val supportsSizeAndRate: Boolean,
    /** The encoder can produce the source's HDR format; false for SDR sources. */
    val supportsSourceHdr: Boolean,
)

enum class ExportQuality {
    /** Bitrate a little above the source so the second encode is not visible. */
    MATCH_SOURCE,
    /** About twice the source bitrate: a larger file with more headroom. */
    MAXIMUM,
}

/** Ways an export can differ from its source. The UI shows each one to the user before and after exporting. */
enum class PlanNotice {
    CODEC_CHANGED_TO_H264,
    CODEC_CHANGED_TO_HEVC,
    HDR_TONE_MAPPED_TO_SDR,
    DOLBY_VISION_METADATA_DROPPED,
    TEN_BIT_SDR_REDUCED_TO_EIGHT,
    EXTRA_AUDIO_TRACKS_DROPPED,
    AUDIO_REENCODED,
    SIZE_MAY_BE_ADJUSTED,
    BITRATE_CAPPED_BY_ENCODER,
    SLOWER_THAN_REAL_TIME,
}

/** One way to encode the export. */
data class EncodePlan(
    val mimeType: String,
    val bitrate: Int,
    val iFrameIntervalSeconds: Float,
    /** False means HDR input is tone-mapped to SDR. Always false for SDR sources. */
    val keepHdr: Boolean,
    /** True means fail instead of letting the encoder change the picture size. */
    val strictSize: Boolean,
    val notices: Set<PlanNotice>,
)

interface EncoderPolicy {
    /**
     * Returns the plans to try in order: the first is the closest match to [source], each later one is a
     * fallback for when the one before it fails on this device. Empty when no encoder can take the video.
     */
    fun plan(source: SourceVideoInfo, encoders: List<EncoderCandidate>, quality: ExportQuality): List<EncodePlan>
}
