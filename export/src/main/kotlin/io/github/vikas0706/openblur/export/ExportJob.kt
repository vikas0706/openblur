package io.github.vikas0706.openblur.export

import android.content.Context
import android.media.MediaCodecInfo
import android.media.metrics.LogSessionId
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import io.github.vikas0706.openblur.api.ExportFailure
import io.github.vikas0706.openblur.api.ExportPhase
import io.github.vikas0706.openblur.api.ExportReport
import io.github.vikas0706.openblur.api.ExportState
import io.github.vikas0706.openblur.api.MaskEffectController
import io.github.vikas0706.openblur.core.EncodePlan
import io.github.vikas0706.openblur.core.PlanNotice
import io.github.vikas0706.openblur.core.SourceVideoInfo
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * Runs one export: tries the encode plans in order, saves the result and reports the final state.
 * Everything except [start] and the ticket's stop hook runs on [handler]'s looper, as Transformer requires.
 */
internal class ExportJob(
    context: Context,
    val ticket: ExportTicket,
    private val handler: Handler,
    private val host: Host,
) {
    /** Both functions are called on the export thread. */
    interface Host {
        fun onProgress(job: ExportJob, phase: ExportPhase, overall: Float)

        fun onFinished(job: ExportJob, terminal: ExportState)
    }

    /** How strictly the encoder is held to the requested picture size. */
    private enum class Mode {
        /** Media3 picks an encoder whose stated capabilities cover the exact size, or fails. */
        CHECKED,

        /** The first encoder is configured with the exact size without asking; it fails if it refuses. */
        EXACT,

        /** Media3 may pick a nearby size the encoder supports. */
        LOOSE,
    }

    private class Attempt(val index: Int, val plan: EncodePlan, val mode: Mode, val tempFile: File) {
        lateinit var transformer: Transformer
        lateinit var encoders: RecordingEncoderFactory
        var mask: MaskEffectController? = null
        val observed = LinkedHashSet<PlanNotice>()
    }

    private val appContext = context.applicationContext
    private val request = ticket.request
    private val source: SourceVideoInfo = request.info
    private val files = ExportFiles(appContext)
    private val storage = StorageProbe(appContext)
    private val saver = MediaStoreSaver(appContext)
    private val progressHolder = ProgressHolder()

    private var plans: List<EncodePlan> = emptyList()
    private var attempt: Attempt? = null
    private var phase = ExportPhase.PREPARING
    private var overall = 0f
    private var finished = false
    private val failureLog = ArrayList<String>()
    private var onlyEncoderSetupFailures = true
    private var lastSavePublishMs = 0L

    /** The name the saved file gets unless that name is taken. */
    val fileName: String = OutputNames.candidate(OutputNames.sanitize(request.displayName), 1)

    private val poll = object : Runnable {
        override fun run() {
            val current = attempt ?: return
            val state = try {
                current.transformer.getProgress(progressHolder)
            } catch (e: RuntimeException) {
                Transformer.PROGRESS_STATE_UNAVAILABLE
            }
            val fraction = ExportProgress.encodeFraction(state, progressHolder.progress)
            when {
                fraction != null -> publish(ExportPhase.ENCODING, fraction, current.plan)
                state == Transformer.PROGRESS_STATE_UNAVAILABLE -> publish(ExportPhase.ENCODING, 0f, current.plan)
                // Still waiting for the first figure; publishing keeps the elapsed time moving.
                else -> publish(phase, 0f, current.plan)
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        ticket.onStopRequested = { handler.post { if (!finished) stopIfRequested() } }
        handler.post { begin() }
    }

    private fun begin() {
        if (stopIfRequested()) return
        publish(ExportPhase.PREPARING, 0f, plan = null)
        val planned = try {
            ticket.policy.plan(source, EncoderSurvey.candidates(source), request.quality)
        } catch (e: RuntimeException) {
            fail(ExportFailure.UNKNOWN, "Could not plan the export: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        if (planned.isEmpty()) {
            val geometry = OutputGeometry.forSource(source)
            fail(
                ExportFailure.NO_ENCODER,
                "No encoder on this device accepts ${source.mimeType} at ${geometry.encodedWidth}x${geometry.encodedHeight}",
            )
            return
        }
        plans = planned
        startAttempt(0, firstMode(planned[0]))
    }

    private fun startAttempt(index: Int, mode: Mode) {
        if (stopIfRequested()) return
        val plan = plans[index]
        overall = 0f
        publish(ExportPhase.PREPARING, 0f, plan)

        val estimate = SpaceEstimate.outputBytes(plan.bitrate, source)
        val shortfall = storage.shortfall(estimate)
        if (shortfall != null) {
            fail(ExportFailure.OUT_OF_SPACE, describe(shortfall))
            return
        }
        val tempFile = try {
            files.newTempFile()
        } catch (e: IOException) {
            val failure = if (FailureMapping.isOutOfSpace(e)) ExportFailure.OUT_OF_SPACE else ExportFailure.SAVE_FAILED
            fail(failure, "Could not prepare the temp folder: ${e.message}")
            return
        }

        val next = Attempt(index, plan, mode, tempFile)
        try {
            next.transformer = buildTransformer(next)
            attempt = next
            startTransformer(next)
        } catch (e: RuntimeException) {
            attempt = null
            tempFile.delete()
            val verdict = FailureVerdict(ExportFailure.UNKNOWN, tryNextPlan = true)
            attemptFailed(next, verdict, errorCode = 0, "Could not start: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        handler.postDelayed(poll, POLL_INTERVAL_MS)
    }

    private fun buildTransformer(target: Attempt): Transformer {
        val plan = target.plan
        val settings = VideoEncoderSettings.Builder().setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        if (plan.bitrate > 0) {
            // In EXACT mode Media3 skips its own clamp, and an encoder may refuse a bitrate outside its range.
            val bitrate = if (target.mode == Mode.EXACT) EncoderSurvey.clampToFirstEncoder(plan.mimeType, plan.bitrate) else plan.bitrate
            settings.setBitrate(bitrate)
        }
        if (plan.iFrameIntervalSeconds > 0f) settings.setiFrameIntervalSeconds(plan.iFrameIntervalSeconds)

        // Audio settings stay at their defaults: any change makes Transformer re-encode the audio.
        val encoders = RecordingEncoderFactory(
            DefaultEncoderFactory.Builder(appContext)
                .setRequestedVideoEncoderSettings(settings.build())
                .setEnableFallback(target.mode != Mode.EXACT)
                .setEnableFormatFallback(target.mode == Mode.LOOSE)
                .build(),
        )
        target.encoders = encoders

        val geometry = OutputGeometry.forSource(source)
        // A loose plan is the last resort: if no encoder takes the tall picture, let Transformer lay it on its side.
        val portrait = geometry.portraitEncoding &&
            (target.mode != Mode.LOOSE || EncoderSurvey.anySupportsSize(plan.mimeType, geometry.encodedWidth, geometry.encodedHeight))

        return Transformer.Builder(appContext)
            .setLooper(handler.looper)
            .setVideoMimeType(plan.mimeType)
            .setEncoderFactory(encoders)
            .setMuxerFactory(InAppMp4Muxer.Factory(if (request.removeLocation) LocationStripper() else null))
            .setPortraitEncodingEnabled(portrait)
            .setUsePlatformDiagnostics(false)
            .addListener(AttemptListener(target))
            .build()
    }

    private fun startTransformer(target: Attempt) {
        val mask = ticket.maskEffects.create()
        mask.setTimeline(request.timeline)
        mask.setTimeOffsetUs(0L)
        target.mask = mask
        val item = EditedMediaItem.Builder(MediaItem.fromUri(request.sourceUri))
            .setEffects(Effects(emptyList(), listOf(mask.effect)))
            .build()
        val path = target.tempFile.absolutePath
        if (source.isHdr && !target.plan.keepHdr) {
            // The HDR mode lives on the Composition. A sequence that names an audio track gets silence
            // generated when the source has none, so the track types follow the source.
            val items = listOf(item)
            val sequence = if (source.audioTrackCount > 0) {
                EditedMediaItemSequence.withAudioAndVideoFrom(items)
            } else {
                EditedMediaItemSequence.withVideoFrom(items)
            }
            val composition = Composition.Builder(sequence)
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
            target.transformer.start(composition, path)
        } else {
            target.transformer.start(item, path)
        }
    }

    private fun onFallback(owner: Attempt, original: TransformationRequest, fallback: TransformationRequest) {
        val fallbackMimeType = fallback.videoMimeType
        if (fallbackMimeType != original.videoMimeType && fallbackMimeType != null &&
            !fallbackMimeType.equals(source.mimeType, ignoreCase = true)
        ) {
            when (fallbackMimeType) {
                MimeTypes.VIDEO_H264 -> owner.observed.add(PlanNotice.CODEC_CHANGED_TO_H264)
                MimeTypes.VIDEO_H265 -> owner.observed.add(PlanNotice.CODEC_CHANGED_TO_HEVC)
            }
        }
        if (fallback.hdrMode != original.hdrMode && source.isHdr) owner.observed.add(PlanNotice.HDR_TONE_MAPPED_TO_SDR)
        if (fallback.audioMimeType != original.audioMimeType) owner.observed.add(PlanNotice.AUDIO_REENCODED)
        if (fallback.outputHeight != original.outputHeight) {
            owner.observed.add(PlanNotice.SIZE_MAY_BE_ADJUSTED)
            if (owner.plan.strictSize) {
                abandon(owner)
                val verdict = FailureVerdict(ExportFailure.ENCODER_FAILED, tryNextPlan = true)
                attemptFailed(
                    owner,
                    verdict,
                    errorCode = 0,
                    "The encoder changed the picture height to ${fallback.outputHeight} under a plan that requires the exact size",
                )
            }
        }
    }

    private fun onEncodeError(owner: Attempt, error: ExportException) {
        handler.removeCallbacks(poll)
        attempt = null
        owner.tempFile.delete()
        val verdict = FailureMapping.classify(error.errorCode, error)
        val codecName = error.codecInfo?.name ?: owner.encoders.encoderName
        val line = FailureMapping.describe(error.errorCodeName, error.errorCode, codecName, error.message, error.cause)
        attemptFailed(owner, verdict, error.errorCode, line)
    }

    private fun attemptFailed(owner: Attempt, verdict: FailureVerdict, errorCode: Int, line: String) {
        val plan = owner.plan
        val entry = "plan ${owner.index + 1} of ${plans.size} (${plan.mimeType}, ${plan.bitrate} bps, ${owner.mode.name.lowercase()}): $line"
        failureLog.add(entry)
        Log.w(TAG, entry)
        val encoderSetup = errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED ||
            errorCode == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED
        if (!encoderSetup) onlyEncoderSetupFailures = false
        if (stopIfRequested()) return

        if (verdict.tryNextPlan) {
            // An encoder that understates what it supports fails the capability check but may still take the size.
            if (owner.mode == Mode.CHECKED && errorCode == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED) {
                startAttempt(owner.index, Mode.EXACT)
                return
            }
            val nextIndex = owner.index + 1
            if (nextIndex < plans.size) {
                startAttempt(nextIndex, firstMode(plans[nextIndex]))
                return
            }
        }
        // When every plan died while setting up its encoder, the device has no encoder for this video.
        val failure = if (verdict.tryNextPlan && onlyEncoderSetupFailures) ExportFailure.NO_ENCODER else verdict.failure
        fail(failure, failureLog.joinToString("\n"))
    }

    private fun onEncoded(owner: Attempt, result: ExportResult) {
        handler.removeCallbacks(poll)
        attempt = null
        // Transformer copies a video track untouched when it sees nothing to do; that file would show every face.
        val reencoded = owner.encoders.encoderName != null || result.videoEncoderName != null
        if (!reencoded && !request.timeline.isEmpty) {
            owner.tempFile.delete()
            fail(ExportFailure.UNKNOWN, "The video track was copied instead of re-encoded, so the masks were not drawn")
            return
        }
        val requestedWidth = owner.encoders.requestedWidth
        val requestedHeight = owner.encoders.requestedHeight
        val sizeChanged = requestedWidth > 0 && result.width > 0 && result.height > 0 &&
            (result.width != requestedWidth || result.height != requestedHeight)
        if (sizeChanged && owner.plan.strictSize) {
            owner.tempFile.delete()
            val verdict = FailureVerdict(ExportFailure.ENCODER_FAILED, tryNextPlan = true)
            attemptFailed(
                owner,
                verdict,
                errorCode = 0,
                "The encoder produced ${result.width}x${result.height} instead of ${requestedWidth}x$requestedHeight",
            )
            return
        }
        if (sizeChanged) owner.observed.add(PlanNotice.SIZE_MAY_BE_ADJUSTED)
        save(owner, result)
    }

    private fun save(owner: Attempt, result: ExportResult) {
        val plan = owner.plan
        val tempFile = owner.tempFile
        publish(ExportPhase.SAVING, 0f, plan)
        val saved = try {
            saver.save(
                tempFile = tempFile,
                baseName = OutputNames.sanitize(request.displayName),
                dateTakenMs = saver.sourceDateTakenMs(request.sourceUri),
                marker = files.markerFor(tempFile),
                isStopped = { ticket.stopReason != null },
                onProgress = { fraction -> publishCopyProgress(fraction, plan) },
            )
        } catch (e: SaveStoppedException) {
            tempFile.delete()
            stopIfRequested()
            return
        } catch (e: IOException) {
            tempFile.delete()
            val failure = if (FailureMapping.isOutOfSpace(e)) ExportFailure.OUT_OF_SPACE else ExportFailure.SAVE_FAILED
            fail(failure, e.message ?: e.javaClass.simpleName)
            return
        } catch (e: RuntimeException) {
            tempFile.delete()
            fail(ExportFailure.SAVE_FAILED, "Saving failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        tempFile.delete()

        val output = try {
            runBlocking { ticket.inspector.inspect(saved.uri) }
        } catch (e: Exception) {
            // A file our own reader cannot open is not a saved video.
            saver.delete(saved)
            fail(ExportFailure.SAVE_FAILED, "The saved file could not be read back: ${e.message}")
            return
        }
        if (ticket.stopReason != null) {
            saver.delete(saved)
            stopIfRequested()
            return
        }

        val audioCopied = result.audioConversionProcess == ExportResult.CONVERSION_PROCESS_TRANSMUXED
        val encoderName = result.videoEncoderName ?: owner.encoders.encoderName
        val encoderCeiling = encoderName?.let { EncoderSurvey.maxBitrate(it, result.videoMimeType ?: plan.mimeType) }
        val differences = OutputComparison.differences(source, output, audioCopied, plan.bitrate, encoderCeiling)
        if (differences.any) Log.i(TAG, "Saved file differs from the source: ${OutputComparison.describe(source, output, differences)}")
        val report = ExportReport(
            outputUri = saved.uri,
            displayName = saved.displayName,
            source = source,
            output = output,
            plan = plan,
            notices = OutputComparison.notices(plan, owner.observed, output, differences),
            audioCopied = audioCopied,
            elapsedMs = elapsedMs(),
        )
        finish(ExportState.Succeeded(report))
    }

    /** Returns true, after publishing the final state, when the export was cancelled or hit the time limit. */
    private fun stopIfRequested(): Boolean {
        if (finished) return true
        val reason = ticket.stopReason ?: return false
        attempt?.let { abandon(it) }
        when (reason) {
            StopReason.USER -> finish(ExportState.Cancelled)
            StopReason.TIME_LIMIT -> finish(
                ExportState.Failed(
                    ExportFailure.TIME_LIMIT,
                    "Android stopped the export: the app used up its daily time for media processing in the background",
                ),
            )
        }
        return true
    }

    private fun abandon(owner: Attempt) {
        handler.removeCallbacks(poll)
        if (attempt === owner) attempt = null
        try {
            owner.transformer.cancel()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cancelling the Transformer failed", e)
        }
        owner.tempFile.delete()
    }

    private fun fail(failure: ExportFailure, detail: String) {
        attempt?.let { abandon(it) }
        finish(ExportState.Failed(failure, detail))
    }

    private fun finish(terminal: ExportState) {
        if (finished) return
        finished = true
        handler.removeCallbacks(poll)
        ticket.onStopRequested = null
        host.onFinished(this, terminal)
    }

    private fun publish(phase: ExportPhase, phaseFraction: Float, plan: EncodePlan?) {
        if (finished) return
        this.phase = phase
        overall = if (phase == ExportPhase.PREPARING) {
            0f
        } else {
            ExportProgress.advance(overall, ExportProgress.overall(phase, phaseFraction))
        }
        ExportSession.update(ticket, ExportState.Running(phase, overall, plan, elapsedMs()))
        host.onProgress(this, phase, overall)
    }

    private fun publishCopyProgress(fraction: Float, plan: EncodePlan) {
        // A fast copy reports every few megabytes; ten updates a second is all anyone can see.
        val now = SystemClock.elapsedRealtime()
        if (fraction < 1f && now - lastSavePublishMs < SAVE_PUBLISH_INTERVAL_MS) return
        lastSavePublishMs = now
        // The last sliver of the bar is kept for reading the saved file back.
        publish(ExportPhase.SAVING, fraction * COPY_SHARE_OF_SAVING, plan)
    }

    private fun describe(shortfall: SpaceShortfall): String {
        val where = if (shortfall.onCacheVolume) "the phone's internal storage" else "shared storage"
        return "Needs about ${megabytes(shortfall.neededBytes)} MB free on $where, but only ${megabytes(shortfall.freeBytes)} MB are free"
    }

    private fun megabytes(bytes: Long): Long = bytes / (1024 * 1024)

    private fun elapsedMs(): Long = SystemClock.elapsedRealtime() - ticket.startedAtMs

    private fun firstMode(plan: EncodePlan): Mode = if (plan.strictSize) Mode.CHECKED else Mode.LOOSE

    private inner class AttemptListener(private val owner: Attempt) : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
            if (attempt === owner) onEncoded(owner, exportResult)
        }

        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
            if (attempt === owner) onEncodeError(owner, exportException)
        }

        override fun onFallbackApplied(
            composition: Composition,
            originalTransformationRequest: TransformationRequest,
            fallbackTransformationRequest: TransformationRequest,
        ) {
            if (attempt === owner) onFallback(owner, originalTransformationRequest, fallbackTransformationRequest)
        }
    }

    /** Passes encoder requests through and remembers the size that was asked for and which encoder took the video. */
    private class RecordingEncoderFactory(private val delegate: Codec.EncoderFactory) : Codec.EncoderFactory {
        @Volatile
        var requestedWidth = 0

        @Volatile
        var requestedHeight = 0

        @Volatile
        var encoderName: String? = null

        override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec =
            delegate.createForAudioEncoding(format, logSessionId)

        override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec {
            requestedWidth = format.width
            requestedHeight = format.height
            val codec = delegate.createForVideoEncoding(format, logSessionId)
            encoderName = codec.name
            return codec
        }

        override fun isVideoFormatSupported(format: Format): Boolean = delegate.isVideoFormatSupported(format)

        override fun audioNeedsEncoding(): Boolean = delegate.audioNeedsEncoding()

        override fun videoNeedsEncoding(): Boolean = delegate.videoNeedsEncoding()
    }

    private companion object {
        const val TAG = "OpenBlurExport"
        const val POLL_INTERVAL_MS = 250L
        const val SAVE_PUBLISH_INTERVAL_MS = 100L
        const val COPY_SHARE_OF_SAVING = 0.95f
    }
}
