package io.github.vikas0706.openblur.export

import io.github.vikas0706.openblur.core.SourceVideoInfo

/** Which volume is short and by how much. */
internal data class SpaceShortfall(val onCacheVolume: Boolean, val neededBytes: Long, val freeBytes: Long)

/** How much room an export needs: once for the temp file and once for the copy in the Movies folder. */
internal object SpaceEstimate {
    /** A VBR encoder may run above its target; the CDD allows up to 15 percent between keyframes. */
    const val MARGIN = 1.15

    /** Container tables and rounding. */
    const val FIXED_OVERHEAD_BYTES = 8L * 1024 * 1024

    /** Used for the audio track when the source's file size cannot tell us how large it is. */
    const val ASSUMED_AUDIO_BITRATE = 320_000

    fun outputBytes(planBitrate: Int, source: SourceVideoInfo): Long {
        val durationUs = source.durationUs.coerceAtLeast(0L)
        val videoBytes = bytesFor(planBitrate, durationUs)
        val sourceVideoBytes = bytesFor(source.bitrate, durationUs)
        // Whatever the source holds besides its video is copied, so the same number of bytes comes out again.
        val otherBytes = if (source.fileSizeBytes > 0L && source.bitrate > 0) {
            (source.fileSizeBytes - sourceVideoBytes).coerceAtLeast(0L)
        } else if (source.audioTrackCount > 0) {
            bytesFor(ASSUMED_AUDIO_BITRATE, durationUs)
        } else {
            0L
        }
        return ((videoBytes + otherBytes) * MARGIN).toLong() + FIXED_OVERHEAD_BYTES
    }

    /**
     * Null when there is room. When the cache and the Movies folder share one volume the temp file and
     * the saved copy exist side by side, so that volume needs the estimate twice.
     */
    fun shortfall(estimateBytes: Long, cacheFreeBytes: Long, destinationFreeBytes: Long, sameVolume: Boolean): SpaceShortfall? {
        if (sameVolume) {
            val needed = estimateBytes * 2
            val free = minOf(cacheFreeBytes, destinationFreeBytes)
            return if (free < needed) SpaceShortfall(onCacheVolume = true, neededBytes = needed, freeBytes = free) else null
        }
        if (cacheFreeBytes < estimateBytes) {
            return SpaceShortfall(onCacheVolume = true, neededBytes = estimateBytes, freeBytes = cacheFreeBytes)
        }
        if (destinationFreeBytes < estimateBytes) {
            return SpaceShortfall(onCacheVolume = false, neededBytes = estimateBytes, freeBytes = destinationFreeBytes)
        }
        return null
    }

    private fun bytesFor(bitsPerSecond: Int, durationUs: Long): Long {
        if (bitsPerSecond <= 0 || durationUs <= 0L) return 0L
        // Seconds first, so a long video at a high bitrate cannot overflow.
        return (bitsPerSecond.toDouble() / 8.0 * (durationUs / 1_000_000.0)).toLong()
    }
}
