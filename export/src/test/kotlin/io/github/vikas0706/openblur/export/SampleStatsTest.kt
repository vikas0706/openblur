package io.github.vikas0706.openblur.export

import org.junit.Assert.assertEquals
import org.junit.Test

class SampleStatsTest {
    @Test
    fun keyframeIntervalIsTheSpanDividedByTheGaps() {
        // Ten keyframes from 0 s to 9 s leave nine gaps of one second.
        assertEquals(1f, SampleStats.keyframeIntervalSeconds(0L, 9_000_000L, 10, 10_000_000L), 1e-6f)
        assertEquals(0.5f, SampleStats.keyframeIntervalSeconds(1_000_000L, 2_000_000L, 3, 10_000_000L), 1e-6f)
    }

    @Test
    fun singleKeyframeMeansOneGroupAsLongAsTheVideo() {
        assertEquals(4.2f, SampleStats.keyframeIntervalSeconds(0L, 0L, 1, 4_200_000L), 1e-6f)
    }

    @Test
    fun keyframeIntervalIsUnknownWithoutKeyframesOrSpan() {
        assertEquals(0f, SampleStats.keyframeIntervalSeconds(0L, 0L, 0, 10_000_000L), 0f)
        assertEquals(0f, SampleStats.keyframeIntervalSeconds(0L, 0L, 1, 0L), 0f)
        assertEquals(0f, SampleStats.keyframeIntervalSeconds(5L, 5L, 2, 10_000_000L), 0f)
    }

    @Test
    fun frameRateCountsGapsBetweenFrames() {
        // 301 frames whose first and last are ten seconds apart are 300 frame durations.
        assertEquals(30f, SampleStats.frameRate(301, 0L, 10_000_000L), 1e-4f)
        assertEquals(0f, SampleStats.frameRate(1, 0L, 10_000_000L), 0f)
        assertEquals(0f, SampleStats.frameRate(10, 7L, 7L), 0f)
    }

    @Test
    fun bitrateAllowsForTheLastFramesOwnDuration() {
        // 30 frames at 30 fps: first to last is 29 frame durations, the video lasts 30 of them, one second.
        val lastUs = 29 * 1_000_000L / 30
        assertEquals(10_000_000.0, SampleStats.bitrate(1_250_000L, 30, 0L, lastUs).toDouble(), 100.0)
    }

    @Test
    fun bitrateIsUnknownForTooFewFrames() {
        assertEquals(0, SampleStats.bitrate(1_000L, 1, 0L, 0L))
        assertEquals(0, SampleStats.bitrate(0L, 30, 0L, 1_000_000L))
    }

    @Test
    fun fileSizeEstimateSubtractsKnownAudio() {
        // 1,500,000 bytes in one second are 12 Mbps in total.
        assertEquals(11_872_000, SampleStats.bitrateFromFileSize(1_500_000L, 1_000_000L, 128_000))
        assertEquals(12_000_000, SampleStats.bitrateFromFileSize(1_500_000L, 1_000_000L, 0))
    }

    @Test
    fun fileSizeEstimateNeverGoesNegativeAndNeedsSizeAndDuration() {
        assertEquals(0, SampleStats.bitrateFromFileSize(1_000L, 1_000_000L, 128_000))
        assertEquals(0, SampleStats.bitrateFromFileSize(0L, 1_000_000L, 0))
        assertEquals(0, SampleStats.bitrateFromFileSize(1_500_000L, 0L, 0))
    }
}
