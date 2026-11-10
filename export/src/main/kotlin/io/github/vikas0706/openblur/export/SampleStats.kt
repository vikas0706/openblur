package io.github.vikas0706.openblur.export

/** Figures derived from sample timestamps and sizes. All times are presentation times in microseconds. */
internal object SampleStats {
    /**
     * Average seconds between keyframes. With a single keyframe the whole span is one group of pictures,
     * so the span is the answer; 0 when nothing was seen.
     */
    fun keyframeIntervalSeconds(firstKeyframeUs: Long, lastKeyframeUs: Long, keyframeCount: Int, videoSpanUs: Long): Float {
        if (keyframeCount <= 0) return 0f
        if (keyframeCount == 1) return if (videoSpanUs > 0L) videoSpanUs / 1_000_000f else 0f
        val spanUs = lastKeyframeUs - firstKeyframeUs
        if (spanUs <= 0L) return 0f
        return (spanUs.toDouble() / (keyframeCount - 1) / 1_000_000.0).toFloat()
    }

    /** Average frames per second over [frameCount] frames whose first and last timestamps are given; 0 when unknown. */
    fun frameRate(frameCount: Int, firstUs: Long, lastUs: Long): Float {
        if (frameCount < 2 || lastUs <= firstUs) return 0f
        return ((frameCount - 1) * 1_000_000.0 / (lastUs - firstUs)).toFloat()
    }

    /**
     * Bits per second for [bytes] spread over [frameCount] frames. The last frame's own duration is not
     * known, so the average frame duration stands in for it.
     */
    fun bitrate(bytes: Long, frameCount: Int, firstUs: Long, lastUs: Long): Int {
        if (bytes <= 0L || frameCount < 2 || lastUs <= firstUs) return 0
        val spanUs = (lastUs - firstUs).toDouble() * frameCount / (frameCount - 1)
        return (bytes * 8.0 * 1_000_000.0 / spanUs).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    /** Whole-file estimate for containers without a sample table: everything that is not known audio counts as video. */
    fun bitrateFromFileSize(fileSizeBytes: Long, durationUs: Long, knownAudioBitrate: Int): Int {
        if (fileSizeBytes <= 0L || durationUs <= 0L) return 0
        val total = fileSizeBytes * 8.0 * 1_000_000.0 / durationUs
        return (total - knownAudioBitrate.coerceAtLeast(0)).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
    }
}
