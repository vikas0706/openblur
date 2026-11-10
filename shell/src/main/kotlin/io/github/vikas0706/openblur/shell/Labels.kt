package io.github.vikas0706.openblur.shell

import android.content.res.Resources
import io.github.vikas0706.openblur.api.ExportFailure
import io.github.vikas0706.openblur.api.ExportPhase
import io.github.vikas0706.openblur.core.PlanNotice
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.core.TransferKind
import io.github.vikas0706.openblur.designsystem.formatTimeUs

/** Turns model values into the words the screens show. Everything here reads from strings.xml. */
internal object Labels {

    fun resolution(res: Resources, info: SourceVideoInfo): String =
        res.getString(R.string.format_resolution, info.displayWidth, info.displayHeight)

    fun frameRate(res: Resources, fps: Float): String {
        val value = Formats.frameRate(fps) ?: return res.getString(R.string.value_unknown)
        return res.getString(R.string.format_frame_rate, value)
    }

    fun bitrate(res: Resources, bitsPerSecond: Int): String {
        val text = Formats.bitrate(bitsPerSecond) ?: return res.getString(R.string.value_unknown)
        return when (text.unit) {
            BitrateUnit.KBPS -> res.getString(R.string.format_kbps, text.value)
            BitrateUnit.MBPS -> res.getString(R.string.format_mbps, text.value)
        }
    }

    fun fileSize(res: Resources, bytes: Long): String {
        val text = Formats.fileSize(bytes) ?: return res.getString(R.string.value_unknown)
        return when (text.unit) {
            SizeUnit.KB -> res.getString(R.string.format_kb, text.value)
            SizeUnit.MB -> res.getString(R.string.format_mb, text.value)
            SizeUnit.GB -> res.getString(R.string.format_gb, text.value)
        }
    }

    fun about(res: Resources, value: String): String = res.getString(R.string.format_about, value)

    fun duration(durationUs: Long): String = formatTimeUs(durationUs)

    fun videoCodec(res: Resources, info: SourceVideoInfo): String =
        res.getString(R.string.format_bit_depth, Formats.videoCodec(info.mimeType), info.bitDepth)

    fun audio(res: Resources, mimeType: String?): String =
        if (mimeType == null) res.getString(R.string.value_none) else Formats.audioCodec(mimeType)

    fun hdr(res: Resources, info: SourceVideoInfo): String {
        val base = when (info.transfer) {
            TransferKind.SDR -> return res.getString(R.string.hdr_none)
            TransferKind.HLG -> res.getString(R.string.hdr_hlg)
            TransferKind.PQ -> res.getString(R.string.hdr_pq)
        }
        return if (info.isDolbyVision) res.getString(R.string.hdr_dolby_vision, base) else base
    }

    fun people(res: Resources, count: Int): String =
        if (count <= 0) res.getString(R.string.project_no_people)
        else res.getQuantityString(R.plurals.project_people, count, count)

    fun planNotice(res: Resources, notice: PlanNotice): String = res.getString(
        when (notice) {
            PlanNotice.CODEC_CHANGED_TO_H264 -> R.string.notice_codec_h264
            PlanNotice.CODEC_CHANGED_TO_HEVC -> R.string.notice_codec_hevc
            PlanNotice.HDR_TONE_MAPPED_TO_SDR -> R.string.notice_hdr_tone_mapped
            PlanNotice.DOLBY_VISION_METADATA_DROPPED -> R.string.notice_dolby_vision
            PlanNotice.TEN_BIT_SDR_REDUCED_TO_EIGHT -> R.string.notice_ten_bit
            PlanNotice.EXTRA_AUDIO_TRACKS_DROPPED -> R.string.notice_extra_audio
            PlanNotice.AUDIO_REENCODED -> R.string.notice_audio_reencoded
            PlanNotice.SIZE_MAY_BE_ADJUSTED -> R.string.notice_size_adjusted
            PlanNotice.BITRATE_CAPPED_BY_ENCODER -> R.string.notice_bitrate_capped
            PlanNotice.SLOWER_THAN_REAL_TIME -> R.string.notice_slow
        },
    )

    fun exportPhase(res: Resources, phase: ExportPhase): String = res.getString(
        when (phase) {
            ExportPhase.PREPARING -> R.string.export_phase_preparing
            ExportPhase.ENCODING -> R.string.export_phase_encoding
            ExportPhase.SAVING -> R.string.export_phase_saving
        },
    )

    fun remaining(res: Resources, remainingMs: Long): String {
        val time = Formats.remaining(remainingMs)
        return when {
            time.isUnderOneMinute -> res.getString(R.string.export_remaining_soon)
            time.hours == 0 -> res.getQuantityString(R.plurals.export_remaining_minutes, time.minutes, time.minutes)
            else -> res.getString(R.string.export_remaining_hours, time.hours, time.minutes)
        }
    }

    fun exportFailure(res: Resources, failure: ExportFailure): String = res.getString(
        when (failure) {
            ExportFailure.NO_ENCODER -> R.string.failure_no_encoder
            ExportFailure.SOURCE_UNREADABLE -> R.string.failure_source
            ExportFailure.DECODER_FAILED -> R.string.failure_decoder
            ExportFailure.ENCODER_FAILED -> R.string.failure_encoder
            ExportFailure.OUT_OF_SPACE -> R.string.failure_space
            ExportFailure.SAVE_FAILED -> R.string.failure_save
            ExportFailure.TIME_LIMIT -> R.string.failure_time
            ExportFailure.UNKNOWN -> R.string.failure_unknown
        },
    )

    fun exportFailureAdvice(res: Resources, failure: ExportFailure): String = res.getString(
        when (failure) {
            ExportFailure.NO_ENCODER -> R.string.failure_no_encoder_advice
            ExportFailure.SOURCE_UNREADABLE -> R.string.failure_source_advice
            ExportFailure.DECODER_FAILED -> R.string.failure_decoder_advice
            ExportFailure.ENCODER_FAILED -> R.string.failure_encoder_advice
            ExportFailure.OUT_OF_SPACE -> R.string.failure_space_advice
            ExportFailure.SAVE_FAILED -> R.string.failure_save_advice
            ExportFailure.TIME_LIMIT -> R.string.failure_time_advice
            ExportFailure.UNKNOWN -> R.string.failure_unknown_advice
        },
    )

    fun scanFailureTitle(res: Resources, failure: ScanFailure): String = res.getString(
        when (failure) {
            ScanFailure.UNREADABLE -> R.string.scan_fail_unreadable_title
            ScanFailure.DECODER -> R.string.scan_fail_decoder_title
            ScanFailure.MODEL -> R.string.scan_fail_model_title
            ScanFailure.OUT_OF_MEMORY -> R.string.scan_fail_memory_title
            ScanFailure.SAVE -> R.string.scan_fail_save_title
            ScanFailure.UNKNOWN -> R.string.scan_fail_unknown_title
        },
    )

    fun scanFailureText(res: Resources, failure: ScanFailure): String = res.getString(
        when (failure) {
            ScanFailure.UNREADABLE -> R.string.scan_fail_unreadable_text
            ScanFailure.DECODER -> R.string.scan_fail_decoder_text
            ScanFailure.MODEL -> R.string.scan_fail_model_text
            ScanFailure.OUT_OF_MEMORY -> R.string.scan_fail_memory_text
            ScanFailure.SAVE -> R.string.scan_fail_save_text
            ScanFailure.UNKNOWN -> R.string.scan_fail_unknown_text
        },
    )
}
