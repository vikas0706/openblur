package io.github.vikas0706.openblur.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import io.github.vikas0706.openblur.api.SourceInspector
import io.github.vikas0706.openblur.api.UnreadableVideoException
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.core.TransferKind
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Describes a video with the Media3 extractors, so the numbers match what export and preview see.
 * Figures a container cannot give exactly within [ContainerProbe]'s limits are reported as unknown (0).
 */
class Media3SourceInspector(context: Context) : SourceInspector {
    private val appContext = context.applicationContext
    private val probe = ContainerProbe(appContext)

    override suspend fun inspect(uri: Uri): SourceVideoInfo {
        try {
            return runInterruptible(Dispatchers.IO) { describe(uri, probe.probe(uri)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnreadableVideoException) {
            throw e
        } catch (e: Exception) {
            // An interrupted read surfaces as an IO error; report the cancellation that caused it.
            currentCoroutineContext().ensureActive()
            throw UnreadableVideoException("Could not read $uri: ${e.javaClass.simpleName}: ${e.message}", e)
        } catch (e: OutOfMemoryError) {
            throw UnreadableVideoException("The index of $uri does not fit in memory", e)
        }
    }

    override suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        val name = when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> queryString(uri, OpenableColumns.DISPLAY_NAME)
            ContentResolver.SCHEME_FILE, null -> uri.lastPathSegment
            else -> null
        }
        name?.takeIf { it.isNotBlank() }
    }

    private fun describe(uri: Uri, result: ProbeResult): SourceVideoInfo {
        if (result.videoIndex < 0) throw UnreadableVideoException("$uri has no video track")
        val video = result.tracks[result.videoIndex]
        val format = video.format ?: throw UnreadableVideoException("$uri has no video track")
        if (format.width <= 0 || format.height <= 0) {
            throw UnreadableVideoException("The video track of $uri does not state its picture size")
        }

        val samples = result.samples
        val durationUs = when {
            result.durationUs != C.TIME_UNSET -> result.durationUs
            samples != null && samples.complete -> samples.lastUs
            else -> 0L
        }
        val frameRate = when {
            format.frameRate > 0f -> format.frameRate
            samples != null -> SampleStats.frameRate(samples.frameCount, samples.firstUs, samples.lastUs)
            else -> 0f
        }
        val audioTracks = result.tracks.filter { it.type == C.TRACK_TYPE_AUDIO }
        val fileSize = if (result.fileSizeBytes > 0L) result.fileSizeBytes else queryLong(uri, OpenableColumns.SIZE)
        val transfer = transferOf(format)
        val isDolbyVision = format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION

        return SourceVideoInfo(
            mimeType = videoMimeType(format, isDolbyVision),
            codedWidth = format.width,
            codedHeight = format.height,
            rotationDegrees = ((format.rotationDegrees % 360) + 360) % 360,
            frameRate = frameRate,
            bitrate = videoBitrate(format, samples, fileSize, durationUs, audioTracks),
            durationUs = durationUs,
            frameCount = if (samples != null && samples.complete) samples.frameCount else 0,
            keyframeIntervalSeconds = keyframeInterval(samples, durationUs),
            transfer = transfer,
            bitDepth = bitDepthOf(format, transfer),
            isDolbyVision = isDolbyVision,
            // A track whose format never arrived still exists; the generic audio type stands in for it.
            audioMimeType = audioTracks.firstOrNull()?.let { it.format?.sampleMimeType ?: MimeTypes.AUDIO_UNKNOWN },
            audioTrackCount = audioTracks.size,
            hasLocation = result.tracks.any { LocationMetadata.hasLocation(it.format?.metadata) },
            fileSizeBytes = fileSize,
        )
    }

    private fun videoMimeType(format: Format, isDolbyVision: Boolean): String {
        val sampleMimeType = format.sampleMimeType ?: MimeTypes.VIDEO_UNKNOWN
        if (!isDolbyVision) return sampleMimeType
        // Dolby Vision is reported as its compatible base layer (HEVC for profile 8) plus the isDolbyVision flag.
        // Profiles without such a layer (5, 7) have no alternative and keep the Dolby Vision type.
        return MediaCodecUtil.getAlternativeCodecMimeType(format) ?: sampleMimeType
    }

    private fun videoBitrate(
        format: Format,
        samples: ProbedSamples?,
        fileSize: Long,
        durationUs: Long,
        audioTracks: List<ProbedTrack>,
    ): Int {
        // For MP4 the extractor has already divided the summed sample sizes by the track duration.
        if (format.averageBitrate > 0) return format.averageBitrate
        if (samples != null && samples.bytes > 0L) {
            val measured = SampleStats.bitrate(samples.bytes, samples.frameCount, samples.firstUs, samples.lastUs)
            val longEnough = samples.complete || samples.lastUs - samples.firstUs >= MIN_BITRATE_WINDOW_US
            if (measured > 0 && longEnough) return measured
        }
        val audioBitrate = audioTracks.sumOf { track ->
            val audio = track.format
            when {
                audio == null -> 0
                audio.averageBitrate > 0 -> audio.averageBitrate
                audio.bitrate > 0 -> audio.bitrate
                else -> 0
            }
        }
        return SampleStats.bitrateFromFileSize(fileSize, durationUs, audioBitrate)
    }

    private fun keyframeInterval(samples: ProbedSamples?, durationUs: Long): Float {
        if (samples == null) return 0f
        // One keyframe in a partial walk only says the interval is longer than the part that was read.
        if (samples.keyframeCount == 1 && !samples.complete) return 0f
        val spanUs = if (durationUs > 0L) durationUs else samples.lastUs - samples.firstUs
        return SampleStats.keyframeIntervalSeconds(samples.firstKeyframeUs, samples.lastKeyframeUs, samples.keyframeCount, spanUs)
    }

    private fun transferOf(format: Format): TransferKind = when (format.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_HLG -> TransferKind.HLG
        C.COLOR_TRANSFER_ST2084 -> TransferKind.PQ
        else -> TransferKind.SDR
    }

    private fun bitDepthOf(format: Format, transfer: TransferKind): Int {
        val luma = format.colorInfo?.lumaBitdepth ?: Format.NO_VALUE
        return when {
            luma > 8 -> 10
            luma > 0 -> 8
            // Without a stated depth, HDR transfer functions imply at least 10 bits.
            transfer != TransferKind.SDR -> 10
            else -> 8
        }
    }

    private fun queryString(uri: Uri, column: String): String? = try {
        appContext.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        // Providers reject unknown columns and revoked grants in their own ways; no name is not an error.
        null
    }

    private fun queryLong(uri: Uri, column: String): Long {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return 0L
        return try {
            appContext.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private companion object {
        /** A shorter prefix says too little about the average bitrate of a whole file. */
        const val MIN_BITRATE_WINDOW_US = 2_000_000L
    }
}
