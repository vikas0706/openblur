package io.github.vikas0706.openblur.editing

import io.github.vikas0706.openblur.core.EncodePlan
import io.github.vikas0706.openblur.core.EncoderCandidate
import io.github.vikas0706.openblur.core.ExportQuality
import io.github.vikas0706.openblur.core.PlanNotice
import io.github.vikas0706.openblur.core.PlanNotice.AUDIO_REENCODED
import io.github.vikas0706.openblur.core.PlanNotice.BITRATE_CAPPED_BY_ENCODER
import io.github.vikas0706.openblur.core.PlanNotice.CODEC_CHANGED_TO_H264
import io.github.vikas0706.openblur.core.PlanNotice.CODEC_CHANGED_TO_HEVC
import io.github.vikas0706.openblur.core.PlanNotice.DOLBY_VISION_METADATA_DROPPED
import io.github.vikas0706.openblur.core.PlanNotice.EXTRA_AUDIO_TRACKS_DROPPED
import io.github.vikas0706.openblur.core.PlanNotice.HDR_TONE_MAPPED_TO_SDR
import io.github.vikas0706.openblur.core.PlanNotice.SIZE_MAY_BE_ADJUSTED
import io.github.vikas0706.openblur.core.PlanNotice.SLOWER_THAN_REAL_TIME
import io.github.vikas0706.openblur.core.PlanNotice.TEN_BIT_SDR_REDUCED_TO_EIGHT
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.core.TransferKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultEncoderPolicyTest {
    private val policy = DefaultEncoderPolicy()

    private val avc = "video/avc"
    private val hevc = "video/hevc"
    private val av1 = "video/av01"
    private val vp9 = "video/x-vnd.on2.vp9"
    private val dolbyVision = "video/dolby-vision"

    private fun source(
        mime: String = avc,
        width: Int = 1920,
        height: Int = 1080,
        frameRate: Float = 30f,
        bitrate: Int = 17_000_000,
        keyframeSeconds: Float = 1f,
        transfer: TransferKind = TransferKind.SDR,
        bitDepth: Int = 8,
        dolby: Boolean = false,
        audioMime: String? = "audio/mp4a-latm",
        audioTracks: Int = 1,
        rotation: Int = 0,
    ) = SourceVideoInfo(
        mimeType = mime,
        codedWidth = width,
        codedHeight = height,
        rotationDegrees = rotation,
        frameRate = frameRate,
        bitrate = bitrate,
        durationUs = 60_000_000L,
        frameCount = 1_800,
        keyframeIntervalSeconds = keyframeSeconds,
        transfer = transfer,
        bitDepth = bitDepth,
        isDolbyVision = dolby,
        audioMimeType = audioMime,
        audioTrackCount = audioTracks,
        hasLocation = false,
        fileSizeBytes = 0L,
    )

    private fun encoder(
        mime: String,
        hardware: Boolean = true,
        maxBitrate: Int = 240_000_000,
        size: Boolean = true,
        rate: Boolean = true,
        hdr: Boolean = false,
        name: String = (if (hardware) "c2.vendor." else "c2.android.") + mime.substringAfter('/'),
    ) = EncoderCandidate(name, mime, hardware, maxBitrate, size, rate, hdr)

    private val phone = listOf(encoder(avc), encoder(hevc))

    private fun plan(
        mime: String,
        bitrate: Int,
        vararg notices: PlanNotice,
        keepHdr: Boolean = false,
        strict: Boolean = true,
        keyframeSeconds: Float = 1f,
    ) = EncodePlan(mime, bitrate, keyframeSeconds, keepHdr, strict, notices.toSet())

    private fun plans(source: SourceVideoInfo, encoders: List<EncoderCandidate>, quality: ExportQuality = ExportQuality.MATCH_SOURCE) =
        policy.plan(source, encoders, quality)

    @Test
    fun h264At1080p30KeepsItsCodecALittleAboveTheSourceBitrate() {
        assertEquals(
            listOf(
                plan(avc, 21_250_000),
                plan(avc, 21_250_000, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(source(), phone),
        )
    }

    @Test
    fun maximumQualityDoublesTheSourceBitrate() {
        assertEquals(
            listOf(
                plan(avc, 34_000_000),
                plan(avc, 34_000_000, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(source(), phone, ExportQuality.MAXIMUM),
        )
    }

    @Test
    fun hevcAt4k60FallsBackToH264AtTwiceTheBitrateThenToALooseSize() {
        val uhd = source(hevc, 3840, 2160, frameRate = 60f, bitrate = 80_000_000)

        assertEquals(
            listOf(
                plan(hevc, 100_000_000),
                plan(avc, 160_000_000, CODEC_CHANGED_TO_H264),
                plan(hevc, 100_000_000, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(uhd, phone),
        )
        assertEquals(
            listOf(160_000_000, 256_000_000, 160_000_000),
            plans(uhd, listOf(encoder(avc, maxBitrate = 300_000_000), encoder(hevc)), ExportQuality.MAXIMUM).map { it.bitrate },
        )
    }

    @Test
    fun theH264FallbackNeverDropsBelowAQuarterBitPerPixel() {
        val compressed = source(hevc, bitrate = 5_000_000)

        val fallback = plans(compressed, phone)[1]

        assertEquals(avc, fallback.mimeType)
        assertEquals(15_552_000, fallback.bitrate)
        assertEquals(6_250_000, plans(compressed, phone)[0].bitrate)
    }

    @Test
    fun aLeanH264SourceIsLiftedToTheFloor() {
        // 0.4 of 0.25 bits per pixel at 1920x1080, 30 fps.
        assertEquals(6_220_800, plans(source(bitrate = 2_000_000), phone)[0].bitrate)
    }

    @Test
    fun aCameraRateSourceIsNotLifted() {
        assertEquals(21_250_000, plans(source(bitrate = 17_000_000), phone)[0].bitrate)
    }

    @Test
    fun hlgStaysHdrWhenTheHevcEncoderCanProduceIt() {
        val hlg = source(hevc, 3840, 2160, bitrate = 50_000_000, transfer = TransferKind.HLG, bitDepth = 10)
        val encoders = listOf(encoder(avc), encoder(hevc, hdr = true))

        assertEquals(
            listOf(
                plan(hevc, 62_500_000, keepHdr = true),
                plan(avc, 100_000_000, CODEC_CHANGED_TO_H264, HDR_TONE_MAPPED_TO_SDR),
                plan(hevc, 62_500_000, SIZE_MAY_BE_ADJUSTED, keepHdr = true, strict = false),
            ),
            plans(hlg, encoders),
        )
    }

    @Test
    fun hlgIsToneMappedWhenNoEncoderCanProduceIt() {
        val hlg = source(hevc, 3840, 2160, bitrate = 50_000_000, transfer = TransferKind.HLG, bitDepth = 10)

        assertEquals(
            listOf(
                plan(hevc, 62_500_000, HDR_TONE_MAPPED_TO_SDR),
                plan(avc, 100_000_000, CODEC_CHANGED_TO_H264, HDR_TONE_MAPPED_TO_SDR),
                plan(hevc, 62_500_000, HDR_TONE_MAPPED_TO_SDR, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(hlg, phone),
        )
    }

    @Test
    fun pqFollowsTheSameRuleAsHlg() {
        val pq = source(hevc, 3840, 2160, bitrate = 50_000_000, transfer = TransferKind.PQ, bitDepth = 10)

        assertTrue(plans(pq, listOf(encoder(avc), encoder(hevc, hdr = true)))[0].keepHdr)
        assertFalse(plans(pq, phone)[0].keepHdr)
        assertTrue(HDR_TONE_MAPPED_TO_SDR in plans(pq, phone)[0].notices)
    }

    @Test
    fun anSdrSourceNeverKeepsHdrEvenIfAnEncoderClaimsIt() {
        val all = plans(source(hevc), listOf(encoder(avc, hdr = true), encoder(hevc, hdr = true)))

        assertTrue(all.none { it.keepHdr })
        assertTrue(all.none { HDR_TONE_MAPPED_TO_SDR in it.notices })
    }

    @Test
    fun dolbyVisionBecomesHevcAndSaysTheMetadataIsGone() {
        val dolby = source(dolbyVision, 3840, 2160, bitrate = 40_000_000, transfer = TransferKind.HLG, bitDepth = 10, dolby = true)
        val encoders = listOf(encoder(avc), encoder(hevc, hdr = true), encoder(dolbyVision, hdr = true))

        assertEquals(
            listOf(
                plan(hevc, 50_000_000, DOLBY_VISION_METADATA_DROPPED, keepHdr = true),
                plan(avc, 80_000_000, CODEC_CHANGED_TO_H264, HDR_TONE_MAPPED_TO_SDR, DOLBY_VISION_METADATA_DROPPED),
                plan(hevc, 50_000_000, DOLBY_VISION_METADATA_DROPPED, SIZE_MAY_BE_ADJUSTED, keepHdr = true, strict = false),
            ),
            plans(dolby, encoders),
        )
    }

    @Test
    fun dolbyVisionIsRecognisedFromTheMimeTypeOrTheFlagAlone() {
        val byMime = source(dolbyVision, bitrate = 20_000_000, transfer = TransferKind.HLG, bitDepth = 10, dolby = false)
        val byFlag = source(hevc, bitrate = 20_000_000, transfer = TransferKind.HLG, bitDepth = 10, dolby = true)

        for (dolby in listOf(byMime, byFlag)) {
            val all = plans(dolby, phone)
            assertEquals(listOf(hevc, avc, hevc), all.map { it.mimeType })
            assertTrue(all.all { DOLBY_VISION_METADATA_DROPPED in it.notices })
            assertTrue(all.none { CODEC_CHANGED_TO_HEVC in it.notices })
        }
    }

    @Test
    fun av1WithOnlyASoftwareAv1EncoderGoesToHevcThenH264() {
        val web = source(av1, bitrate = 4_000_000)
        val encoders = listOf(encoder(av1, hardware = false), encoder(avc), encoder(hevc))

        assertEquals(
            listOf(
                plan(hevc, 6_000_000, CODEC_CHANGED_TO_HEVC),
                plan(avc, 15_552_000, CODEC_CHANGED_TO_H264),
                plan(hevc, 6_000_000, CODEC_CHANGED_TO_HEVC, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(web, encoders),
        )
    }

    @Test
    fun av1WithAHardwareAv1EncoderStaysAv1() {
        val web = source(av1, bitrate = 20_000_000)
        val encoders = listOf(encoder(avc), encoder(hevc), encoder(av1))

        assertEquals(
            listOf(
                plan(av1, 25_000_000),
                plan(hevc, 30_000_000, CODEC_CHANGED_TO_HEVC),
                plan(avc, 40_000_000, CODEC_CHANGED_TO_H264),
                plan(av1, 25_000_000, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(web, encoders),
        )
    }

    @Test
    fun tenBitSdrIsReportedAsReducedToEightBits() {
        val tenBit = source(hevc, bitrate = 20_000_000, bitDepth = 10)

        assertEquals(
            listOf(
                plan(hevc, 25_000_000, TEN_BIT_SDR_REDUCED_TO_EIGHT),
                plan(avc, 40_000_000, CODEC_CHANGED_TO_H264, TEN_BIT_SDR_REDUCED_TO_EIGHT),
                plan(hevc, 25_000_000, TEN_BIT_SDR_REDUCED_TO_EIGHT, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(tenBit, listOf(encoder(avc), encoder(hevc, hdr = true))),
        )
        assertTrue(plans(source(hevc, transfer = TransferKind.HLG, bitDepth = 10), phone).none { TEN_BIT_SDR_REDUCED_TO_EIGHT in it.notices })
    }

    @Test
    fun anUnknownBitrateUsesBitsPerPixelForEachCodec() {
        assertEquals(
            listOf(15_552_000, 15_552_000),
            plans(source(bitrate = 0), phone).map { it.bitrate },
        )
        assertEquals(
            listOf(hevc to 10_575_360, avc to 15_552_000, hevc to 10_575_360),
            plans(source(hevc, bitrate = 0), phone).map { it.mimeType to it.bitrate },
        )
        assertEquals(
            listOf(av1 to 10_575_360, hevc to 10_575_360, avc to 15_552_000, av1 to 10_575_360),
            plans(source(av1, bitrate = -1), listOf(encoder(av1), encoder(hevc), encoder(avc))).map { it.mimeType to it.bitrate },
        )
        assertEquals(24_883_200, plans(source(bitrate = 0), phone, ExportQuality.MAXIMUM)[0].bitrate)
        assertEquals(31_104_000, plans(source(bitrate = 0, frameRate = 60f), phone)[0].bitrate)
        assertEquals("30 fps is assumed when the rate is unknown too", 15_552_000, plans(source(bitrate = 0, frameRate = 0f), phone)[0].bitrate)
    }

    @Test
    fun aTargetAboveTheEncodersMaximumIsCappedAndReported() {
        val encoders = listOf(encoder(avc, maxBitrate = 20_000_000), encoder(hevc, maxBitrate = 12_000_000))

        assertEquals(
            listOf(
                plan(avc, 20_000_000, BITRATE_CAPPED_BY_ENCODER),
                plan(avc, 20_000_000, BITRATE_CAPPED_BY_ENCODER, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(source(), encoders),
        )
        assertEquals(
            listOf(
                plan(hevc, 12_000_000, BITRATE_CAPPED_BY_ENCODER),
                plan(avc, 20_000_000, CODEC_CHANGED_TO_H264, BITRATE_CAPPED_BY_ENCODER),
                plan(hevc, 12_000_000, BITRATE_CAPPED_BY_ENCODER, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(source(hevc), encoders),
        )
    }

    @Test
    fun aTargetAtOrBelowTheMaximumIsLeftAlone() {
        val exact = plans(source(), listOf(encoder(avc, maxBitrate = 21_250_000)))
        val unknownLimit = plans(source(), listOf(encoder(avc, maxBitrate = 0)))

        assertEquals(21_250_000, exact[0].bitrate)
        assertTrue(exact.none { BITRATE_CAPPED_BY_ENCODER in it.notices })
        assertEquals(21_250_000, unknownLimit[0].bitrate)
        assertTrue(unknownLimit.none { BITRATE_CAPPED_BY_ENCODER in it.notices })
    }

    @Test
    fun twoAudioTracksAreReportedAsDropped() {
        val all = plans(source(audioTracks = 2), phone)

        assertEquals(2, all.size)
        assertTrue(all.all { it.notices - SIZE_MAY_BE_ADJUSTED == setOf(EXTRA_AUDIO_TRACKS_DROPPED) })
        assertTrue(plans(source(audioTracks = 1), phone).none { EXTRA_AUDIO_TRACKS_DROPPED in it.notices })
    }

    @Test
    fun ac3AudioIsReportedAsReencoded() {
        val all = plans(source(audioMime = "audio/ac3"), phone)

        assertEquals(2, all.size)
        assertTrue(all.all { it.notices - SIZE_MAY_BE_ADJUSTED == setOf(AUDIO_REENCODED) })
    }

    @Test
    fun onlyAudioTheMuxerCannotCopyIsReportedAsReencoded() {
        val copied = listOf("audio/mp4a-latm", "audio/3gpp", "audio/amr-wb", "audio/opus", "audio/vorbis", "audio/raw", "audio/iamf", "AUDIO/MP4A-LATM")
        val reencoded = listOf("audio/ac3", "audio/eac3", "audio/mpeg", "audio/flac", "audio/ac4", "audio/true-hd", "audio/amr")

        for (mime in copied) {
            assertTrue(mime, plans(source(audioMime = mime), phone).none { AUDIO_REENCODED in it.notices })
        }
        for (mime in reencoded) {
            assertTrue(mime, plans(source(audioMime = mime), phone).all { AUDIO_REENCODED in it.notices })
        }
        assertTrue(plans(source(audioMime = null, audioTracks = 0), phone).all { it.notices.isEmpty() || it.notices == setOf(SIZE_MAY_BE_ADJUSTED) })
        assertTrue(plans(source(audioMime = "audio/ac3", audioTracks = 0), phone).none { AUDIO_REENCODED in it.notices })
    }

    @Test
    fun aSizeNoEncoderAcceptsGivesNoPlans() {
        val huge = source(hevc, 7680, 4320, bitrate = 200_000_000)
        val encoders = listOf(encoder(avc, size = false, rate = false), encoder(hevc, size = false, rate = false), encoder(avc, hardware = false, size = false))

        assertEquals(emptyList<EncodePlan>(), plans(huge, encoders))
    }

    @Test
    fun noEncodersGiveNoPlans() {
        assertEquals(emptyList<EncodePlan>(), plans(source(), emptyList()))
        assertEquals(emptyList<EncodePlan>(), plans(source(hevc), emptyList(), ExportQuality.MAXIMUM))
    }

    @Test
    fun encodersForCodecsThePolicyCannotWriteAreIgnored() {
        val encoders = listOf(encoder("video/x-vnd.on2.vp8"), encoder("video/mp4v-es"), encoder(dolbyVision), encoder("video/3gpp"))

        assertEquals(emptyList<EncodePlan>(), plans(source(), encoders))
    }

    @Test
    fun aSourceWithoutASizeGivesNoPlans() {
        assertEquals(emptyList<EncodePlan>(), plans(source(width = 0, height = 1080), phone))
        assertEquals(emptyList<EncodePlan>(), plans(source(width = 1920, height = -1), phone))
    }

    @Test
    fun aCodecWhoseEncoderRejectsTheSizeIsSkipped() {
        val uhd = source(hevc, 3840, 2160, bitrate = 60_000_000)
        val encoders = listOf(encoder(hevc, size = false), encoder(avc))

        assertEquals(
            listOf(
                plan(avc, 120_000_000, CODEC_CHANGED_TO_H264),
                plan(avc, 120_000_000, CODEC_CHANGED_TO_H264, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(uhd, encoders),
        )
    }

    @Test
    fun aSoftwareEncoderIsUsedOnlyWhenNoHardwareEncoderFits() {
        val small = source(hevc, 480, 360, bitrate = 1_000_000)
        val softwareHevc = encoder(hevc, hardware = false, maxBitrate = 900_000)

        assertEquals(
            "a hardware H.264 encoder is preferred to a software HEVC encoder",
            listOf(avc, avc),
            plans(small, listOf(softwareHevc, encoder(avc))).map { it.mimeType },
        )
        assertEquals(
            listOf(
                plan(hevc, 900_000, BITRATE_CAPPED_BY_ENCODER),
                plan(avc, 2_000_000, CODEC_CHANGED_TO_H264),
                plan(hevc, 900_000, BITRATE_CAPPED_BY_ENCODER, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(small, listOf(softwareHevc, encoder(avc, hardware = false))),
        )
        assertEquals(
            "the hardware encoder's limit applies, not the software one's",
            1_250_000,
            plans(small, listOf(softwareHevc, encoder(hevc), encoder(avc)))[0].bitrate,
        )
    }

    @Test
    fun aHardwareEncoderThatRejectsTheSizeDoesNotBlockASoftwareOne() {
        val encoders = listOf(encoder(avc, size = false), encoder(avc, hardware = false, maxBitrate = 12_000_000))

        assertEquals(
            listOf(
                plan(avc, 12_000_000, BITRATE_CAPPED_BY_ENCODER),
                plan(avc, 12_000_000, BITRATE_CAPPED_BY_ENCODER, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(source(), encoders),
        )
    }

    @Test
    fun softwareAv1IsUsedOnlyWhenItIsTheOnlyEncoderLeft() {
        val web = source(av1, bitrate = 4_000_000)

        assertEquals(
            listOf(avc, avc),
            plans(web, listOf(encoder(av1, hardware = false), encoder(avc, hardware = false))).map { it.mimeType },
        )
        assertEquals(
            listOf(av1, av1),
            plans(web, listOf(encoder(av1, hardware = false))).map { it.mimeType },
        )
    }

    @Test
    fun anEncoderThatCannotKeepUpIsReportedAsSlow() {
        val uhd60 = source(hevc, 3840, 2160, frameRate = 60f, bitrate = 80_000_000)
        val encoders = listOf(encoder(hevc, rate = false), encoder(avc))

        val all = plans(uhd60, encoders)

        assertEquals(setOf(SLOWER_THAN_REAL_TIME), all[0].notices)
        assertEquals(setOf(CODEC_CHANGED_TO_H264), all[1].notices)
        assertEquals(setOf(SLOWER_THAN_REAL_TIME, SIZE_MAY_BE_ADJUSTED), all[2].notices)
    }

    @Test
    fun amongEncodersOfOneCodecTheMostCapableDecidesThePlan() {
        val hlg = source(hevc, 3840, 2160, frameRate = 60f, bitrate = 50_000_000, transfer = TransferKind.HLG, bitDepth = 10)
        val encoders = listOf(
            encoder(hevc, name = "slow", rate = false, maxBitrate = 300_000_000),
            encoder(hevc, name = "small", size = false, hdr = true),
            encoder(hevc, name = "hdr", hdr = true, rate = false, maxBitrate = 40_000_000),
            encoder(hevc, name = "fast", maxBitrate = 300_000_000),
            encoder(avc),
        )

        val first = plans(hlg, encoders)[0]
        assertTrue("an encoder that keeps HDR wins over a faster one", first.keepHdr)
        assertEquals(setOf(SLOWER_THAN_REAL_TIME, BITRATE_CAPPED_BY_ENCODER), first.notices)
        assertEquals(40_000_000, first.bitrate)

        val sdr = plans(source(hevc, 3840, 2160, frameRate = 60f, bitrate = 50_000_000), encoders)[0]
        assertEquals("for SDR the encoder that keeps up wins", emptySet<PlanNotice>(), sdr.notices)
        assertEquals(62_500_000, sdr.bitrate)
    }

    @Test
    fun theKeyframeIntervalFollowsTheSourceOrDefaultsToOneSecond() {
        assertTrue(plans(source(keyframeSeconds = 2.5f), phone).all { it.iFrameIntervalSeconds == 2.5f })
        assertTrue(plans(source(keyframeSeconds = 0.5f), phone).all { it.iFrameIntervalSeconds == 0.5f })
        assertTrue(plans(source(keyframeSeconds = 0f), phone).all { it.iFrameIntervalSeconds == 1f })
        assertTrue(plans(source(keyframeSeconds = -3f), phone).all { it.iFrameIntervalSeconds == 1f })
        assertTrue(plans(source(keyframeSeconds = Float.NaN), phone).all { it.iFrameIntervalSeconds == 1f })
        assertTrue(plans(source(hevc, keyframeSeconds = 4f), phone).all { it.iFrameIntervalSeconds == 4f })
    }

    @Test
    fun vp9KeepsItsCodecOnlyWithAHardwareEncoder() {
        val web = source(vp9, bitrate = 8_000_000)

        assertEquals(
            listOf(
                plan(vp9, 10_000_000),
                plan(hevc, 10_000_000, CODEC_CHANGED_TO_HEVC),
                plan(avc, 16_000_000, CODEC_CHANGED_TO_H264),
                plan(vp9, 10_000_000, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(web, listOf(encoder(avc), encoder(hevc), encoder(vp9))),
        )
        assertEquals(
            listOf(
                plan(hevc, 10_000_000, CODEC_CHANGED_TO_HEVC),
                plan(avc, 16_000_000, CODEC_CHANGED_TO_H264),
                plan(hevc, 10_000_000, CODEC_CHANGED_TO_HEVC, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(web, listOf(encoder(avc), encoder(hevc), encoder(vp9, hardware = false))),
        )
    }

    @Test
    fun aCodecThatKeepsHdrComesBeforeTheSourceCodecThatCannot() {
        val hdr10 = source(av1, 3840, 2160, bitrate = 30_000_000, transfer = TransferKind.PQ, bitDepth = 10)
        val encoders = listOf(encoder(avc), encoder(av1), encoder(hevc, hdr = true))

        assertEquals(
            listOf(
                plan(hevc, 45_000_000, CODEC_CHANGED_TO_HEVC, keepHdr = true),
                plan(av1, 37_500_000, HDR_TONE_MAPPED_TO_SDR),
                plan(avc, 62_208_000, CODEC_CHANGED_TO_H264, HDR_TONE_MAPPED_TO_SDR),
                plan(hevc, 45_000_000, CODEC_CHANGED_TO_HEVC, SIZE_MAY_BE_ADJUSTED, keepHdr = true, strict = false),
            ),
            plans(hdr10, encoders),
        )
    }

    @Test
    fun h264IsNotMovedAheadEvenIfItsEncoderClaimsHdr() {
        val hlg = source(hevc, bitrate = 20_000_000, transfer = TransferKind.HLG, bitDepth = 10)
        val encoders = listOf(encoder(avc, hdr = true), encoder(hevc))

        assertEquals(
            listOf(
                plan(hevc, 25_000_000, HDR_TONE_MAPPED_TO_SDR),
                plan(avc, 40_000_000, CODEC_CHANGED_TO_H264, keepHdr = true),
                plan(hevc, 25_000_000, HDR_TONE_MAPPED_TO_SDR, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(hlg, encoders),
        )
    }

    @Test
    fun encoderForNamesTheEncoderEachPlanAssumed() {
        val hlg = source(hevc, 3840, 2160, frameRate = 60f, bitrate = 50_000_000, transfer = TransferKind.HLG, bitDepth = 10)
        val encoders = listOf(
            encoder(hevc, name = "small", size = false, hdr = true),
            encoder(hevc, name = "plain"),
            encoder(hevc, name = "hdr", hdr = true),
            encoder(hevc, name = "software", hardware = false, hdr = true),
            encoder(avc, name = "h264"),
            encoder(av1, name = "av1"),
        )

        assertEquals("hdr", policy.encoderFor(hevc, hlg, encoders)?.name)
        assertEquals("h264", policy.encoderFor("VIDEO/AVC", hlg, encoders)?.name)
        assertEquals("no plan for an HEVC source uses AV1", null, policy.encoderFor(av1, hlg, encoders))
        assertEquals("plain", policy.encoderFor(hevc, source(hevc), encoders)?.name)
        assertEquals("software", policy.encoderFor(hevc, hlg, encoders.filter { it.name == "software" || it.name == "small" })?.name)
        assertEquals(null, policy.encoderFor(hevc, hlg, emptyList()))
        assertEquals(
            plans(hlg, encoders).map { it.mimeType }.toSet(),
            listOf(avc, hevc, av1, vp9).filter { policy.encoderFor(it, hlg, encoders) != null }.toSet(),
        )
    }

    @Test
    fun olderCodecsAreRewrittenAsH264WithoutExtraBitrate() {
        val old = source("video/mp4v-es", 640, 480, bitrate = 3_000_000)

        assertEquals(
            listOf(
                plan(avc, 3_750_000, CODEC_CHANGED_TO_H264),
                plan(avc, 3_750_000, CODEC_CHANGED_TO_H264, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(old, listOf(encoder(avc), encoder(hevc), encoder("video/mp4v-es"))),
        )
    }

    @Test
    fun hevcIsTheLastResortWhenNoH264EncoderTakesTheSize() {
        val big = source(avc, 3840, 2160, bitrate = 60_000_000)
        val encoders = listOf(encoder(avc, size = false), encoder(hevc))

        assertEquals(
            listOf(
                plan(hevc, 75_000_000, CODEC_CHANGED_TO_HEVC),
                plan(hevc, 75_000_000, CODEC_CHANGED_TO_HEVC, SIZE_MAY_BE_ADJUSTED, strict = false),
            ),
            plans(big, encoders),
        )
    }

    @Test
    fun plansAreNeverRepeated() {
        val sources = listOf(
            source(), source(hevc), source(av1), source(vp9), source(dolbyVision, dolby = true, transfer = TransferKind.HLG, bitDepth = 10),
            source("video/mp4v-es"), source(bitrate = 0), source(hevc, bitrate = 0),
        )
        val devices = listOf(
            phone,
            listOf(encoder(avc)),
            listOf(encoder(hevc)),
            listOf(encoder(avc), encoder(avc, name = "second"), encoder(hevc), encoder(hevc, hardware = false), encoder(av1), encoder(vp9)),
            listOf(encoder(avc, hardware = false), encoder(av1, hardware = false)),
            listOf(encoder(avc, maxBitrate = 1_000_000), encoder(hevc, maxBitrate = 1_000_000)),
        )
        for (source in sources) {
            for (device in devices) {
                for (quality in ExportQuality.entries) {
                    val all = plans(source, device, quality)
                    assertEquals("$source on $device", all.distinct(), all)
                    assertTrue(all.isNotEmpty())
                    assertTrue("the first plan is strict", all.first().strictSize)
                    assertEquals("only the last plan is loose", listOf(all.lastIndex), all.indices.filter { !all[it].strictSize })
                    assertEquals(all.first().mimeType, all.last().mimeType)
                    assertTrue(all.all { it.bitrate > 0 && it.iFrameIntervalSeconds > 0f })
                }
            }
        }
    }

    @Test
    fun mimeTypesAreMatchedWithoutRegardToCase() {
        val all = plans(source("VIDEO/HEVC", bitrate = 10_000_000), listOf(encoder("Video/Avc"), encoder("video/HEVC")))

        assertEquals(listOf(hevc, avc, hevc), all.map { it.mimeType })
        assertEquals(emptySet<PlanNotice>(), all[0].notices)
    }

    @Test
    fun rotationDoesNotChangeThePlan() {
        assertEquals(plans(source(rotation = 0), phone), plans(source(rotation = 90), phone))
    }

    @Test
    fun anAbsurdBitrateStaysAValidInt() {
        val all = plans(source(hevc, bitrate = Int.MAX_VALUE), listOf(encoder(avc, maxBitrate = 0), encoder(hevc, maxBitrate = 0)), ExportQuality.MAXIMUM)

        assertTrue(all.all { it.bitrate == Int.MAX_VALUE })
    }
}
