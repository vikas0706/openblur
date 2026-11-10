package io.github.vikas0706.openblur.shell

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

internal enum class BitrateUnit { KBPS, MBPS }

internal enum class SizeUnit { KB, MB, GB }

internal data class BitrateText(val value: String, val unit: BitrateUnit)

internal data class SizeText(val value: String, val unit: SizeUnit)

/** Time left, rounded up to whole minutes. Both zero means less than a minute. */
internal data class RemainingTime(val hours: Int, val minutes: Int) {
    val isUnderOneMinute: Boolean get() = hours == 0 && minutes == 0
}

/** Number formatting without Android resources, so it runs in plain JVM tests. Digits are always ASCII. */
internal object Formats {

    /** Null when the rate is unknown. Whole rates print without decimals, others with two (29.97). */
    fun frameRate(fps: Float): String? {
        if (fps.isNaN() || fps <= 0f) return null
        val hundredths = (fps * 100.0).roundToLong()
        return if (hundredths % 100L == 0L) {
            (hundredths / 100L).toString()
        } else {
            String.format(Locale.US, "%.2f", hundredths / 100.0)
        }
    }

    /** Null when the bitrate is unknown. */
    fun bitrate(bitsPerSecond: Int): BitrateText? {
        if (bitsPerSecond <= 0) return null
        if (bitsPerSecond < 1_000_000) {
            return BitrateText(((bitsPerSecond + 500) / 1000).coerceAtLeast(1).toString(), BitrateUnit.KBPS)
        }
        val mbps = bitsPerSecond / 1_000_000.0
        val text = if (mbps >= 100.0) String.format(Locale.US, "%.0f", mbps) else String.format(Locale.US, "%.1f", mbps)
        return BitrateText(text, BitrateUnit.MBPS)
    }

    /** Null when the size is unknown. Decimal units, as the system's file apps use. */
    fun fileSize(bytes: Long): SizeText? {
        if (bytes <= 0L) return null
        return when {
            bytes < 1_000_000L -> SizeText(((bytes + 500L) / 1000L).coerceAtLeast(1L).toString(), SizeUnit.KB)
            bytes < 100_000_000L -> SizeText(String.format(Locale.US, "%.1f", bytes / 1_000_000.0), SizeUnit.MB)
            bytes < 1_000_000_000L -> SizeText(String.format(Locale.US, "%.0f", bytes / 1_000_000.0), SizeUnit.MB)
            else -> SizeText(String.format(Locale.US, "%.2f", bytes / 1_000_000_000.0), SizeUnit.GB)
        }
    }

    /**
     * Expected size of an export: the planned video bitrate over the duration, plus whatever the source
     * file holds beyond its own video (audio and container), since that part is carried over.
     * Returns 0 when the bitrate or duration is unknown.
     */
    fun estimatedExportBytes(
        planBitrate: Int,
        durationUs: Long,
        sourceBitrate: Int,
        sourceFileBytes: Long,
    ): Long {
        if (planBitrate <= 0 || durationUs <= 0L) return 0L
        val seconds = durationUs / 1_000_000.0
        val video = planBitrate / 8.0 * seconds
        val sourceVideo = if (sourceBitrate > 0) sourceBitrate / 8.0 * seconds else 0.0
        val carriedOver = if (sourceFileBytes > 0L && sourceVideo > 0.0) (sourceFileBytes - sourceVideo).coerceAtLeast(0.0) else 0.0
        return (video + carriedOver).roundToLong()
    }

    /** Whole percent, rounded down so 100 appears only when the work is done. */
    fun percent(fraction: Float): Int {
        if (fraction.isNaN()) return 0
        return (fraction.coerceIn(0f, 1f) * 100f).toInt()
    }

    /** The file name without its extension, for naming the export after its source. */
    fun baseName(displayName: String): String {
        val trimmed = displayName.trim()
        val dot = trimmed.lastIndexOf('.')
        if (dot <= 0 || dot == trimmed.length - 1) return trimmed
        val extension = trimmed.substring(dot + 1)
        val looksLikeExtension = extension.length <= 5 && extension.all { it.isLetterOrDigit() }
        return if (looksLikeExtension) trimmed.substring(0, dot) else trimmed
    }

    fun remaining(remainingMs: Long): RemainingTime {
        if (remainingMs < 60_000L) return RemainingTime(0, 0)
        val minutes = ceil(remainingMs / 60_000.0).toLong()
        return RemainingTime((minutes / 60L).toInt(), (minutes % 60L).toInt())
    }

    /** Codec names are technical proper names and are the same in every language. */
    fun videoCodec(mimeType: String): String = when (mimeType.lowercase(Locale.US)) {
        "video/avc" -> "H.264 (AVC)"
        "video/hevc" -> "HEVC (H.265)"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/dolby-vision" -> "Dolby Vision"
        "video/mp4v-es" -> "MPEG-4"
        "video/3gpp" -> "H.263"
        "video/mpeg2" -> "MPEG-2"
        "video/apv" -> "APV"
        else -> subtype(mimeType)
    }

    fun audioCodec(mimeType: String): String = when (mimeType.lowercase(Locale.US)) {
        "audio/mp4a-latm" -> "AAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/mpeg" -> "MP3"
        "audio/ac3" -> "Dolby Digital"
        "audio/eac3" -> "Dolby Digital Plus"
        "audio/eac3-joc" -> "Dolby Atmos"
        "audio/ac4" -> "Dolby AC-4"
        "audio/raw" -> "PCM"
        "audio/3gpp" -> "AMR"
        "audio/amr-wb" -> "AMR-WB"
        else -> subtype(mimeType)
    }

    private fun subtype(mimeType: String): String {
        val slash = mimeType.indexOf('/')
        return if (slash >= 0 && slash < mimeType.length - 1) mimeType.substring(slash + 1) else mimeType
    }
}
