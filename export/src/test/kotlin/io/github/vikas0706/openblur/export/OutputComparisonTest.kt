package io.github.vikas0706.openblur.export

import io.github.vikas0706.openblur.core.PlanNotice
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.core.TransferKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputComparisonTest {
    private val source = video()

    private fun compare(
        output: SourceVideoInfo,
        from: SourceVideoInfo = source,
        audioCopied: Boolean = true,
        planBitrate: Int = 20_000_000,
        encoderMaxBitrate: Int? = 100_000_000,
    ) = OutputComparison.differences(from, output, audioCopied, planBitrate, encoderMaxBitrate)

    private fun noticesFor(
        output: SourceVideoInfo,
        from: SourceVideoInfo = source,
        planned: Set<PlanNotice> = emptySet(),
        observed: Set<PlanNotice> = emptySet(),
        audioCopied: Boolean = true,
    ): Set<PlanNotice> {
        val differences = compare(output, from, audioCopied)
        return OutputComparison.notices(plan(notices = planned), observed, output, differences)
    }

    @Test
    fun identicalOutputHasNoDifferencesAndNoNotices() {
        // The measured bitrate and file size always move a little; they are not differences.
        val output = source.copy(bitrate = 15_400_000, fileSizeBytes = 20_300_000L)
        assertFalse(compare(output).any)
        assertEquals(emptySet<PlanNotice>(), noticesFor(output))
    }

    @Test
    fun plannedNoticesAreKeptAndObservedOnesAdded() {
        val notices = noticesFor(
            output = source,
            planned = setOf(PlanNotice.SLOWER_THAN_REAL_TIME, PlanNotice.EXTRA_AUDIO_TRACKS_DROPPED),
            observed = setOf(PlanNotice.HDR_TONE_MAPPED_TO_SDR),
        )
        assertEquals(
            setOf(PlanNotice.SLOWER_THAN_REAL_TIME, PlanNotice.EXTRA_AUDIO_TRACKS_DROPPED, PlanNotice.HDR_TONE_MAPPED_TO_SDR),
            notices,
        )
    }

    @Test
    fun sizeWarningIsDroppedOnceTheSavedFileHasTheSourceSize() {
        val planned = setOf(PlanNotice.SIZE_MAY_BE_ADJUSTED, PlanNotice.CODEC_CHANGED_TO_H264)
        assertEquals(setOf(PlanNotice.CODEC_CHANGED_TO_H264), noticesFor(source.copy(mimeType = "video/avc"), planned = planned))
    }

    @Test
    fun changedCodedSizeIsReported() {
        val output = source.copy(codedHeight = 1088)
        assertTrue(compare(output).codedSizeChanged)
        assertEquals(setOf(PlanNotice.SIZE_MAY_BE_ADJUSTED), noticesFor(output))
    }

    @Test
    fun changedRotationIsReportedEvenWithTheSameCodedSize() {
        val upsideDown = video(rotationDegrees = 180)
        val output = upsideDown.copy(rotationDegrees = 0)
        val differences = compare(output, from = upsideDown)
        assertTrue(differences.rotationChanged)
        assertFalse(differences.codedSizeChanged)
        assertEquals(setOf(PlanNotice.SIZE_MAY_BE_ADJUSTED), noticesFor(output, from = upsideDown))
    }

    @Test
    fun codecChangeNamesTheCodecOfTheSavedFile() {
        assertEquals(setOf(PlanNotice.CODEC_CHANGED_TO_H264), noticesFor(source.copy(mimeType = "video/avc")))
        val fromH264 = video(mimeType = "video/avc")
        assertEquals(setOf(PlanNotice.CODEC_CHANGED_TO_HEVC), noticesFor(fromH264.copy(mimeType = "video/hevc"), from = fromH264))
        assertFalse(compare(source.copy(mimeType = "VIDEO/HEVC")).codecChanged)
    }

    @Test
    fun frameCountMayBeOffByOneFrameButNotTwo() {
        assertFalse(compare(source.copy(frameCount = 299)).frameCountChanged)
        assertFalse(compare(source.copy(frameCount = 301)).frameCountChanged)
        assertTrue(compare(source.copy(frameCount = 298)).frameCountChanged)
        assertTrue(compare(source.copy(frameCount = 302)).frameCountChanged)
    }

    @Test
    fun unknownFrameCountIsNotADifference() {
        assertFalse(compare(source.copy(frameCount = 0)).frameCountChanged)
        assertFalse(compare(source, from = video(frameCount = 0)).frameCountChanged)
    }

    @Test
    fun lostHdrIsReported() {
        val hlg = video(transfer = TransferKind.HLG, bitDepth = 10)
        val output = hlg.copy(transfer = TransferKind.SDR, bitDepth = 8)
        val differences = compare(output, from = hlg)
        assertTrue(differences.hdrLost)
        // The drop to 8 bits is part of tone mapping and gets no notice of its own.
        assertFalse(differences.tenBitSdrReduced)
        assertEquals(setOf(PlanNotice.HDR_TONE_MAPPED_TO_SDR), noticesFor(output, from = hlg))
        assertFalse(compare(hlg, from = hlg).hdrLost)
    }

    @Test
    fun tenBitSdrComingOutAsEightBitIsReported() {
        val tenBit = video(bitDepth = 10)
        assertEquals(setOf(PlanNotice.TEN_BIT_SDR_REDUCED_TO_EIGHT), noticesFor(tenBit.copy(bitDepth = 8), from = tenBit))
        assertEquals(emptySet<PlanNotice>(), noticesFor(tenBit, from = tenBit))
    }

    @Test
    fun lostDolbyVisionIsReported() {
        val dolby = video(transfer = TransferKind.HLG, bitDepth = 10, isDolbyVision = true)
        assertEquals(setOf(PlanNotice.DOLBY_VISION_METADATA_DROPPED), noticesFor(dolby.copy(isDolbyVision = false), from = dolby))
    }

    @Test
    fun fewerAudioTracksAreReported() {
        val twoTracks = video(audioTrackCount = 2)
        assertEquals(setOf(PlanNotice.EXTRA_AUDIO_TRACKS_DROPPED), noticesFor(twoTracks.copy(audioTrackCount = 1), from = twoTracks))
    }

    @Test
    fun audioIsReportedAsReencodedWhenItWasNotCopiedOrItsCodecChanged() {
        assertEquals(setOf(PlanNotice.AUDIO_REENCODED), noticesFor(source, audioCopied = false))
        val ac3 = video(audioMimeType = "audio/ac3")
        assertEquals(setOf(PlanNotice.AUDIO_REENCODED), noticesFor(ac3.copy(audioMimeType = "audio/mp4a-latm"), from = ac3))
    }

    @Test
    fun silentVideoNeverReportsAudioChanges() {
        val silent = video(audioTrackCount = 0, audioMimeType = null)
        assertFalse(compare(silent, from = silent, audioCopied = false).any)
    }

    @Test
    fun bitrateCapIsReportedOnlyWhenTheEncoderCeilingIsBelowThePlan() {
        assertTrue(compare(source, planBitrate = 20_000_000, encoderMaxBitrate = 12_000_000).bitrateCapped)
        assertFalse(compare(source, planBitrate = 20_000_000, encoderMaxBitrate = 20_000_000).bitrateCapped)
        assertFalse(compare(source, planBitrate = 20_000_000, encoderMaxBitrate = null).bitrateCapped)
        val capped = compare(source, planBitrate = 20_000_000, encoderMaxBitrate = 12_000_000)
        assertEquals(
            setOf(PlanNotice.BITRATE_CAPPED_BY_ENCODER),
            OutputComparison.notices(plan(), emptySet(), source, capped),
        )
    }

    @Test
    fun descriptionListsWhatChangedIncludingWhatHasNoNotice() {
        val output = source.copy(frameCount = 290, rotationDegrees = 90)
        val text = OutputComparison.describe(source, output, compare(output))
        assertEquals("rotation 0 -> 90; frames 300 -> 290", text)
    }
}
