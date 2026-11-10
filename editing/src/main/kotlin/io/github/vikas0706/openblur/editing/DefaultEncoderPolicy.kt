package io.github.vikas0706.openblur.editing

import io.github.vikas0706.openblur.core.EncodePlan
import io.github.vikas0706.openblur.core.EncoderCandidate
import io.github.vikas0706.openblur.core.EncoderPolicy
import io.github.vikas0706.openblur.core.ExportQuality
import io.github.vikas0706.openblur.core.PlanNotice
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.core.TransferKind
import java.util.Collections
import java.util.EnumSet
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Plans run from closest to the source: its codec at the exact size, then HEVC, then H.264, then the first
 * again with the size left to the encoder. A software encoder is used only when no hardware encoder takes the size.
 */
class DefaultEncoderPolicy : EncoderPolicy {
    override fun plan(
        source: SourceVideoInfo,
        encoders: List<EncoderCandidate>,
        quality: ExportQuality,
    ): List<EncodePlan> {
        val family = Codec.ofSource(source)
        val steps = stepsFor(source, family, encoders)
        if (steps.isEmpty()) return emptyList()

        val shared = sourceNotices(source)
        val plans = LinkedHashSet<EncodePlan>()
        for (step in steps) plans += planFor(source, family, step, quality, shared, strictSize = true)
        plans += planFor(source, family, steps[0], quality, shared, strictSize = false)
        return plans.toList()
    }

    /**
     * The encoder whose bitrate limit, HDR support and speed the plans with [mimeType] reflect, so an export
     * should run on it. Null when [plan] returns no plan with that type.
     */
    fun encoderFor(mimeType: String, source: SourceVideoInfo, encoders: List<EncoderCandidate>): EncoderCandidate? =
        stepsFor(source, Codec.ofSource(source), encoders)
            .firstOrNull { it.codec.mimeType.equals(mimeType, ignoreCase = true) }
            ?.encoder

    private fun stepsFor(source: SourceVideoInfo, family: Codec?, encoders: List<EncoderCandidate>): List<Step> {
        if (source.codedWidth <= 0 || source.codedHeight <= 0) return emptyList()
        val steps = choose(ladderFor(family), encoders, source.isHdr)
        // Nothing takes this size in the usual codecs. HEVC is the only other codec a notice can announce.
        return steps.ifEmpty { choose(listOf(Codec.HEVC), encoders, source.isHdr) }
    }

    private fun planFor(
        source: SourceVideoInfo,
        family: Codec?,
        step: Step,
        quality: ExportQuality,
        shared: Set<PlanNotice>,
        strictSize: Boolean,
    ): EncodePlan {
        val notices = EnumSet.noneOf(PlanNotice::class.java)
        notices += shared
        if (step.codec != family) {
            if (step.codec == Codec.AVC) notices += PlanNotice.CODEC_CHANGED_TO_H264
            if (step.codec == Codec.HEVC) notices += PlanNotice.CODEC_CHANGED_TO_HEVC
        }
        val keepHdr = source.isHdr && step.encoder.supportsSourceHdr
        if (source.isHdr && !keepHdr) notices += PlanNotice.HDR_TONE_MAPPED_TO_SDR
        if (!step.encoder.supportsSizeAndRate) notices += PlanNotice.SLOWER_THAN_REAL_TIME
        if (!strictSize) notices += PlanNotice.SIZE_MAY_BE_ADJUSTED

        var bitrate = targetBitrate(source, family, step.codec, quality).coerceIn(1L, Int.MAX_VALUE.toLong())
        val ceiling = step.encoder.maxBitrate
        if (ceiling > 0 && ceiling < bitrate) {
            bitrate = ceiling.toLong()
            notices += PlanNotice.BITRATE_CAPPED_BY_ENCODER
        }

        return EncodePlan(
            mimeType = step.codec.mimeType,
            bitrate = bitrate.toInt(),
            iFrameIntervalSeconds = keyframeInterval(source),
            keepHdr = keepHdr,
            strictSize = strictSize,
            notices = Collections.unmodifiableSet(notices),
        )
    }

    /** Differences that hold whichever encoder is used. */
    private fun sourceNotices(source: SourceVideoInfo): Set<PlanNotice> {
        val notices = EnumSet.noneOf(PlanNotice::class.java)
        if (source.isDolbyVision || source.mimeType.equals(MIME_DOLBY_VISION, ignoreCase = true)) {
            notices += PlanNotice.DOLBY_VISION_METADATA_DROPPED
        }
        if (source.transfer == TransferKind.SDR && source.bitDepth > 8) {
            notices += PlanNotice.TEN_BIT_SDR_REDUCED_TO_EIGHT
        }
        if (source.audioTrackCount > 1) notices += PlanNotice.EXTRA_AUDIO_TRACKS_DROPPED
        val audio = source.audioMimeType
        if (source.audioTrackCount > 0 && audio != null && audio.lowercase() !in COPYABLE_AUDIO) {
            notices += PlanNotice.AUDIO_REENCODED
        }
        return notices
    }

    private fun targetBitrate(source: SourceVideoInfo, family: Codec?, codec: Codec, quality: ExportQuality): Long {
        val frameRate = if (source.frameRate > 0f && source.frameRate.isFinite()) source.frameRate.toDouble() else 30.0
        val pixelsPerSecond = source.codedWidth.toDouble() * source.codedHeight * frameRate
        val headroom = if (quality == ExportQuality.MAXIMUM) MAXIMUM_HEADROOM else MATCH_HEADROOM
        if (source.bitrate <= 0) {
            // The per-pixel figures are camera bitrates, so they stand in for a source with its headroom included.
            return (codec.bitsPerPixel * pixelsPerSecond * (headroom / MATCH_HEADROOM)).roundToLong()
        }
        val changedFrom = family?.takeIf { it != codec }
        var target = source.bitrate * headroom * (changedFrom?.let { codec.costAfter(it) } ?: 1.0)
        // A heavily compressed HEVC or AV1 source would otherwise give H.264 too few bits to work with.
        if (changedFrom != null && codec == Codec.AVC) target = max(target, Codec.AVC.bitsPerPixel * pixelsPerSecond)
        // A second encode of an already lean file loses more than one of a camera original, so lean sources get a floor.
        target = max(target, codec.bitsPerPixel * MIN_SHARE_OF_CAMERA_RATE * pixelsPerSecond * (headroom / MATCH_HEADROOM))
        return target.roundToLong()
    }

    private fun keyframeInterval(source: SourceVideoInfo): Float {
        val measured = source.keyframeIntervalSeconds
        return if (measured > 0f && measured.isFinite()) measured else DEFAULT_KEYFRAME_SECONDS
    }

    /** One step per codec of [ladder] that has a usable encoder, all hardware or, failing that, all software. */
    private fun choose(ladder: List<Codec>, encoders: List<EncoderCandidate>, hdr: Boolean): List<Step> {
        var steps = ladder.mapNotNull { codec -> best(codec, encoders, hardware = true, hdr)?.let { Step(codec, it) } }
        if (steps.isEmpty()) {
            val software = ladder.mapNotNull { codec -> best(codec, encoders, hardware = false, hdr)?.let { Step(codec, it) } }
            steps = software.filterNot { it.codec.needsHardware }.ifEmpty { software }
        }
        // Losing HDR changes the picture more than moving to another codec that carries it.
        // H.264 keeps its place because few phones can play its 10-bit profile.
        return if (hdr) steps.sortedByDescending { it.codec != Codec.AVC && it.encoder.supportsSourceHdr } else steps
    }

    /**
     * Prefers an encoder that keeps HDR, then one that keeps up with the frame rate. Equal encoders resolve to
     * the first listed, which is the one Media3 picks by default when the list is in MediaCodecList order.
     */
    private fun best(codec: Codec, encoders: List<EncoderCandidate>, hardware: Boolean, hdr: Boolean): EncoderCandidate? {
        var best: EncoderCandidate? = null
        var bestRank = -1
        for (encoder in encoders) {
            if (!encoder.supportsSize || encoder.isHardware != hardware) continue
            if (!encoder.mimeType.equals(codec.mimeType, ignoreCase = true)) continue
            val rank = (if (hdr && encoder.supportsSourceHdr) 2 else 0) + (if (encoder.supportsSizeAndRate) 1 else 0)
            if (rank > bestRank) {
                best = encoder
                bestRank = rank
            }
        }
        return best
    }

    private fun ladderFor(family: Codec?): List<Codec> = when (family) {
        Codec.AVC, null -> listOf(Codec.AVC)
        Codec.HEVC -> listOf(Codec.HEVC, Codec.AVC)
        Codec.AV1 -> listOf(Codec.AV1, Codec.HEVC, Codec.AVC)
        Codec.VP9 -> listOf(Codec.VP9, Codec.HEVC, Codec.AVC)
    }

    private class Step(val codec: Codec, val encoder: EncoderCandidate)

    /**
     * Codecs an export can be written in. [bitsPerPixel] is the bitrate per pixel per frame used when the
     * source's bitrate is unknown. [needsHardware] marks codecs whose software encoders are too slow to offer.
     */
    private enum class Codec(val mimeType: String, val bitsPerPixel: Double, val needsHardware: Boolean) {
        AVC("video/avc", 0.25, false),
        HEVC("video/hevc", 0.17, false),
        AV1("video/av01", 0.17, true),
        VP9("video/x-vnd.on2.vp9", 0.17, true);

        /** How much more bitrate this codec needs than [source] did to hold the same picture. */
        fun costAfter(source: Codec): Double = when {
            this == AVC -> 1.6
            this == HEVC && source == AV1 -> 1.2
            else -> 1.0
        }

        companion object {
            /** Null for codecs no plan can keep, such as MPEG-4 or VP8. */
            fun ofSource(source: SourceVideoInfo): Codec? {
                val mime = source.mimeType
                // Dolby Vision on phones is profile 8.4, an HEVC stream with extra metadata.
                if (mime.equals(MIME_DOLBY_VISION, ignoreCase = true)) return HEVC
                return entries.firstOrNull { it.mimeType.equals(mime, ignoreCase = true) }
            }
        }
    }

    private companion object {
        const val MIME_DOLBY_VISION = "video/dolby-vision"

        /** Bitrate over the source's, so the second encode is not visible. */
        const val MATCH_HEADROOM = 1.25

        /** Floor as a share of [Codec.bitsPerPixel]; chosen from on-device PSNR measurements of low-bitrate clips. */
        const val MIN_SHARE_OF_CAMERA_RATE = 0.4
        const val MAXIMUM_HEADROOM = 2.0

        const val DEFAULT_KEYFRAME_SECONDS = 1.0f

        /** Audio the Media3 1.11.1 MP4 muxer writes as is: AAC, AMR-NB, AMR-WB, Opus, Vorbis, raw PCM, IAMF. */
        val COPYABLE_AUDIO = setOf(
            "audio/mp4a-latm",
            "audio/3gpp",
            "audio/amr-wb",
            "audio/opus",
            "audio/vorbis",
            "audio/raw",
            "audio/iamf",
        )
    }
}
