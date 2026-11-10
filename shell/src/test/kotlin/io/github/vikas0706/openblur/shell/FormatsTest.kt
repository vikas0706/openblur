package io.github.vikas0706.openblur.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {

    @Test
    fun frameRate_wholeRatesHaveNoDecimals() {
        assertEquals("30", Formats.frameRate(30f))
        assertEquals("60", Formats.frameRate(60.001f))
        assertEquals("24", Formats.frameRate(23.999f))
    }

    @Test
    fun frameRate_fractionalRatesKeepTwoDecimals() {
        assertEquals("29.97", Formats.frameRate(29.97f))
        assertEquals("23.98", Formats.frameRate(23.976f))
        assertEquals("59.94", Formats.frameRate(59.94f))
    }

    @Test
    fun frameRate_unknownIsNull() {
        assertNull(Formats.frameRate(0f))
        assertNull(Formats.frameRate(-1f))
        assertNull(Formats.frameRate(Float.NaN))
    }

    @Test
    fun bitrate_picksUnitBySize() {
        assertEquals(BitrateText("12.4", BitrateUnit.MBPS), Formats.bitrate(12_400_000))
        assertEquals(BitrateText("1.0", BitrateUnit.MBPS), Formats.bitrate(1_000_000))
        assertEquals(BitrateText("120", BitrateUnit.MBPS), Formats.bitrate(120_000_000))
        assertEquals(BitrateText("800", BitrateUnit.KBPS), Formats.bitrate(800_000))
        assertEquals(BitrateText("1", BitrateUnit.KBPS), Formats.bitrate(200))
        assertNull(Formats.bitrate(0))
    }

    @Test
    fun fileSize_picksUnitBySize() {
        assertEquals(SizeText("46.5", SizeUnit.MB), Formats.fileSize(46_500_000L))
        assertEquals(SizeText("250", SizeUnit.MB), Formats.fileSize(250_000_000L))
        assertEquals(SizeText("1.50", SizeUnit.GB), Formats.fileSize(1_500_000_000L))
        assertEquals(SizeText("12", SizeUnit.KB), Formats.fileSize(12_000L))
        assertEquals(SizeText("1", SizeUnit.KB), Formats.fileSize(1L))
        assertNull(Formats.fileSize(0L))
    }

    @Test
    fun estimatedExportBytes_addsWhatTheSourceHoldsBesidesVideo() {
        // 12 Mbps for 30 s is 45 MB of video; the source has 1.5 MB of audio and container on top of its video.
        assertEquals(46_500_000L, Formats.estimatedExportBytes(12_000_000, 30_000_000L, 10_000_000, 39_000_000L))
    }

    @Test
    fun estimatedExportBytes_withoutSourceFactsIsVideoOnly() {
        assertEquals(45_000_000L, Formats.estimatedExportBytes(12_000_000, 30_000_000L, 0, 0L))
        assertEquals(45_000_000L, Formats.estimatedExportBytes(12_000_000, 30_000_000L, 10_000_000, 0L))
    }

    @Test
    fun estimatedExportBytes_neverSubtractsWhenTheSourceIsSmallerThanItsBitrateSuggests() {
        assertEquals(45_000_000L, Formats.estimatedExportBytes(12_000_000, 30_000_000L, 10_000_000, 1_000L))
    }

    @Test
    fun estimatedExportBytes_unknownInputsGiveZero() {
        assertEquals(0L, Formats.estimatedExportBytes(0, 30_000_000L, 10_000_000, 39_000_000L))
        assertEquals(0L, Formats.estimatedExportBytes(12_000_000, 0L, 10_000_000, 39_000_000L))
    }

    @Test
    fun percent_roundsDownAndClamps() {
        assertEquals(0, Formats.percent(0f))
        assertEquals(49, Formats.percent(0.499f))
        assertEquals(99, Formats.percent(0.999f))
        assertEquals(100, Formats.percent(1f))
        assertEquals(100, Formats.percent(1.7f))
        assertEquals(0, Formats.percent(-0.2f))
        assertEquals(0, Formats.percent(Float.NaN))
    }

    @Test
    fun baseName_dropsOnlyARealExtension() {
        assertEquals("holiday", Formats.baseName("holiday.mp4"))
        assertEquals("VID_2026.11.10", Formats.baseName("VID_2026.11.10.mov"))
        assertEquals("clip", Formats.baseName("  clip.MKV "))
        assertEquals("no extension", Formats.baseName("no extension"))
        assertEquals(".hidden", Formats.baseName(".hidden"))
        assertEquals("trailing.", Formats.baseName("trailing."))
        assertEquals("party at 10.30 with friends", Formats.baseName("party at 10.30 with friends"))
    }

    @Test
    fun remaining_roundsUpToWholeMinutes() {
        assertTrue(Formats.remaining(0L).isUnderOneMinute)
        assertTrue(Formats.remaining(59_999L).isUnderOneMinute)
        assertEquals(RemainingTime(0, 1), Formats.remaining(60_000L))
        assertEquals(RemainingTime(0, 2), Formats.remaining(60_001L))
        assertEquals(RemainingTime(1, 0), Formats.remaining(3_600_000L))
        assertEquals(RemainingTime(1, 21), Formats.remaining(4_810_000L))
    }

    @Test
    fun codecNames_coverPhoneFormatsAndFallBackToTheSubtype() {
        assertEquals("H.264 (AVC)", Formats.videoCodec("video/avc"))
        assertEquals("HEVC (H.265)", Formats.videoCodec("video/HEVC"))
        assertEquals("AV1", Formats.videoCodec("video/av01"))
        assertEquals("x-custom", Formats.videoCodec("video/x-custom"))
        assertEquals("AAC", Formats.audioCodec("audio/mp4a-latm"))
        assertEquals("Opus", Formats.audioCodec("audio/opus"))
        assertEquals("weird", Formats.audioCodec("weird"))
    }
}
