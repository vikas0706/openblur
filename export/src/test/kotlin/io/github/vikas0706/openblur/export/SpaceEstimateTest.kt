package io.github.vikas0706.openblur.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceEstimateTest {
    private val overhead = SpaceEstimate.FIXED_OVERHEAD_BYTES

    @Test
    fun videoAtThePlanBitratePlusWhatTheSourceCarriesBesidesItsVideo() {
        // 10 s at 20 Mbps is 25,000,000 bytes. The source holds 20,000,000 bytes of video in a
        // 21,000,000 byte file, so 1,000,000 bytes of audio and container come along.
        val estimate = SpaceEstimate.outputBytes(20_000_000, video(bitrate = 16_000_000, fileSizeBytes = 21_000_000L))
        assertEquals(26_000_000 * 1.15 + overhead, estimate.toDouble(), 2.0)
    }

    @Test
    fun audioIsAssumedWhenTheFileSizeIsUnknown() {
        // 320 kbps for 10 s is 400,000 bytes.
        val estimate = SpaceEstimate.outputBytes(20_000_000, video(fileSizeBytes = 0L))
        assertEquals(25_400_000 * 1.15 + overhead, estimate.toDouble(), 2.0)
    }

    @Test
    fun silentSourceOfUnknownSizeCountsVideoOnly() {
        val silent = video(fileSizeBytes = 0L, audioTrackCount = 0, audioMimeType = null)
        assertEquals(25_000_000 * 1.15 + overhead, SpaceEstimate.outputBytes(20_000_000, silent).toDouble(), 2.0)
    }

    @Test
    fun sourceSmallerThanItsStatedVideoAddsNothingNegative() {
        val estimate = SpaceEstimate.outputBytes(20_000_000, video(bitrate = 16_000_000, fileSizeBytes = 1_000L))
        assertEquals(25_000_000 * 1.15 + overhead, estimate.toDouble(), 2.0)
    }

    @Test
    fun unknownDurationLeavesOnlyTheFixedOverhead() {
        assertEquals(overhead, SpaceEstimate.outputBytes(20_000_000, video(durationUs = 0L, fileSizeBytes = 0L)))
    }

    @Test
    fun twoHoursAtAHighBitrateDoesNotOverflow() {
        val twoHoursUs = 2L * 60 * 60 * 1_000_000
        val estimate = SpaceEstimate.outputBytes(200_000_000, video(durationUs = twoHoursUs, fileSizeBytes = 0L))
        // 200 Mbps for 7200 s is 180 GB before the margin.
        assertTrue(estimate > 180_000_000_000L)
        assertTrue(estimate < 220_000_000_000L)
    }

    @Test
    fun oneVolumeHoldsTheTempFileAndTheCopySoItNeedsTheEstimateTwice() {
        val shortfall = SpaceEstimate.shortfall(estimateBytes = 100, cacheFreeBytes = 150, destinationFreeBytes = 150, sameVolume = true)
        assertEquals(SpaceShortfall(onCacheVolume = true, neededBytes = 200, freeBytes = 150), shortfall)
        assertNull(SpaceEstimate.shortfall(estimateBytes = 100, cacheFreeBytes = 200, destinationFreeBytes = 200, sameVolume = true))
    }

    @Test
    fun separateVolumesEachNeedTheEstimateOnce() {
        assertNull(SpaceEstimate.shortfall(estimateBytes = 100, cacheFreeBytes = 100, destinationFreeBytes = 100, sameVolume = false))
        assertEquals(
            SpaceShortfall(onCacheVolume = true, neededBytes = 100, freeBytes = 99),
            SpaceEstimate.shortfall(estimateBytes = 100, cacheFreeBytes = 99, destinationFreeBytes = 500, sameVolume = false),
        )
        assertEquals(
            SpaceShortfall(onCacheVolume = false, neededBytes = 100, freeBytes = 40),
            SpaceEstimate.shortfall(estimateBytes = 100, cacheFreeBytes = 500, destinationFreeBytes = 40, sameVolume = false),
        )
    }
}
