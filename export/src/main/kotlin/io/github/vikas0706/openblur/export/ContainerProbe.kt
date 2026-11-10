package io.github.vikas0706.openblur.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackAwareSeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException

internal class ProbedTrack(val type: Int, val format: Format?, val durationUs: Long)

/** What is known about the video track's samples. Times are presentation times in microseconds. */
internal class ProbedSamples(
    /** Frames with a presentation time of zero or later, which are the frames a player and the export show. */
    val frameCount: Int,
    /** True when [frameCount] covers the whole track and is not a prefix. */
    val complete: Boolean,
    val firstUs: Long,
    val lastUs: Long,
    /** Sample bytes behind [frameCount]; 0 when the sizes were not read. */
    val bytes: Long,
    val keyframeCount: Int,
    val firstKeyframeUs: Long,
    val lastKeyframeUs: Long,
)

internal class ProbeResult(
    val tracks: List<ProbedTrack>,
    /** Index into [tracks] of the first video track, or -1. */
    val videoIndex: Int,
    /** Container duration, or [C.TIME_UNSET]. */
    val durationUs: Long,
    /** 0 when the source does not report a length. */
    val fileSizeBytes: Long,
    val samples: ProbedSamples?,
)

/**
 * Reads a container with the Media3 extractors that playback and export use. MP4 and MOV answer from the
 * header's sample table; other containers are walked for at most [WALK_BUDGET_NANOS] or [WALK_BUDGET_BYTES].
 */
internal class ContainerProbe(context: Context) {
    private val appContext = context.applicationContext

    /** Blocking. Throws [IOException] (including Media3's ParserException) when the data cannot be read. */
    fun probe(uri: Uri): ProbeResult {
        if (!isLocal(uri)) throw IOException("Only files on this device can be opened, not ${uri.scheme} addresses")
        val dataSource = DefaultDataSource(appContext, /* allowCrossProtocolRedirects = */ false)
        val collector = Collector()
        var extractor: Extractor? = null
        try {
            val length = dataSource.open(DataSpec(uri))
            var input: ExtractorInput = DefaultExtractorInput(dataSource, 0, length)
            extractor = sniff(uri, input)
            extractor.init(collector)
            val mp4 = extractor.underlyingImplementation as? Mp4Extractor
            val position = PositionHolder()
            var walkDeadline = 0L
            var reachedEnd = false
            while (true) {
                if (Thread.interrupted()) throw InterruptedIOException("Inspection was cancelled")
                if (collector.headerComplete && mp4 != null && collector.allFormatsKnown) break
                if (collector.headerComplete || collector.videoFormatKnown) {
                    val now = System.nanoTime()
                    if (walkDeadline == 0L) walkDeadline = now + WALK_BUDGET_NANOS
                    if (now >= walkDeadline || collector.sampleBytes >= WALK_BUDGET_BYTES) break
                } else if (collector.sampleBytes >= HEADERLESS_LIMIT_BYTES) {
                    break
                }
                when (extractor.read(input, position)) {
                    Extractor.RESULT_SEEK -> {
                        dataSource.close()
                        val remaining = dataSource.open(DataSpec.Builder().setUri(uri).setPosition(position.position).build())
                        val end = if (remaining == C.LENGTH_UNSET.toLong()) remaining else position.position + remaining
                        input = DefaultExtractorInput(dataSource, position.position, end)
                    }
                    Extractor.RESULT_END_OF_INPUT -> {
                        reachedEnd = true
                        break
                    }
                }
            }
            return collect(collector, mp4, reachedEnd, if (length == C.LENGTH_UNSET.toLong()) 0L else length)
        } finally {
            DataSourceUtil.closeQuietly(dataSource)
            extractor?.release()
        }
    }

    private fun sniff(uri: Uri, input: ExtractorInput): Extractor {
        val headers = appContext.contentResolver.takeIf { uri.scheme == ContentResolver.SCHEME_CONTENT }
            ?.let { resolver -> runCatching { resolver.getType(uri) }.getOrNull() }
            ?.let { mapOf("Content-Type" to listOf(it)) }
            ?: emptyMap()
        // The factory puts the extractor matching the MIME type or file extension first; sniffing settles it.
        val extractors = DefaultExtractorsFactory().createExtractors(uri, headers)
        for (candidate in extractors) {
            try {
                if (candidate.sniff(input)) return candidate
            } catch (e: EOFException) {
                // The file is shorter than this format's signature, so it is not this format.
            } finally {
                input.resetPeekPosition()
            }
        }
        throw IOException("No Media3 extractor recognises this file")
    }

    private fun collect(collector: Collector, mp4: Mp4Extractor?, reachedEnd: Boolean, fileSize: Long): ProbeResult {
        val sinks = collector.tracks.values.toList()
        val videoIndex = sinks.indexOfFirst { sink ->
            sink.type == C.TRACK_TYPE_VIDEO && MimeTypes.isVideo(sink.format?.sampleMimeType)
        }
        val seekMap = collector.seekMap
        val trackDurations = sinks.map { it.durationUs }.filter { it != C.TIME_UNSET && it > 0L }
        val durationUs = seekMap?.durationUs?.takeIf { it != C.TIME_UNSET && it > 0L }
            ?: trackDurations.maxOrNull()
            ?: C.TIME_UNSET
        val samples = if (videoIndex < 0) {
            null
        } else {
            val video = sinks[videoIndex]
            if (mp4 != null) tableSamples(mp4, seekMap, video.id) else video.walkedSamples(reachedEnd)
        }
        return ProbeResult(
            tracks = sinks.map { ProbedTrack(it.type, it.format, it.durationUs) },
            videoIndex = videoIndex,
            durationUs = durationUs,
            fileSizeBytes = fileSize,
            samples = samples,
        )
    }

    /** Frame count from the MP4 sample table and keyframe spacing from its sync sample index. No sample data is read. */
    private fun tableSamples(mp4: Mp4Extractor, seekMap: SeekMap?, trackId: Int): ProbedSamples? {
        val times = mp4.getSampleTimestampsUs(trackId)
        var count = 0
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        for (time in times) {
            // Samples before an edit list's start carry negative times and are decoded but never shown.
            if (time < 0L) continue
            count++
            if (time < first) first = time
            if (time > last) last = time
        }
        if (count == 0) return null

        var keyframes = 0
        var firstKey = 0L
        var lastKey = 0L
        if (seekMap is TrackAwareSeekMap) {
            val deadline = System.nanoTime() + KEYFRAME_BUDGET_NANOS
            var current = seekMap.getSeekPoints(Long.MIN_VALUE, trackId).first.timeUs
            firstKey = current
            lastKey = current
            keyframes = 1
            while (keyframes < MAX_KEYFRAME_HOPS) {
                // For a time just past a sync sample the map returns that sample and the next sync sample.
                val points = seekMap.getSeekPoints(current + 1, trackId)
                val next = if (points.first.timeUs > current) points.first.timeUs else points.second.timeUs
                if (next <= current) break
                current = next
                lastKey = next
                keyframes++
                if (keyframes and 0x3FF == 0 && System.nanoTime() >= deadline) break
            }
        }
        return ProbedSamples(
            frameCount = count,
            complete = true,
            firstUs = first,
            lastUs = last,
            bytes = 0L,
            keyframeCount = keyframes,
            firstKeyframeUs = firstKey,
            lastKeyframeUs = lastKey,
        )
    }

    private fun isLocal(uri: Uri): Boolean = when (uri.scheme) {
        null, "", ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE,
        ContentResolver.SCHEME_ANDROID_RESOURCE, "asset", "rawresource",
        -> true
        else -> false
    }

    private class Collector : ExtractorOutput {
        val tracks = LinkedHashMap<Int, TrackSink>()
        var seekMap: SeekMap? = null
        private var tracksEnded = false
        var sampleBytes = 0L
        val scratch = ByteArray(SCRATCH_BYTES)

        val headerComplete: Boolean get() = tracksEnded && seekMap != null
        val allFormatsKnown: Boolean get() = tracks.values.all { it.format != null }
        val videoFormatKnown: Boolean get() = tracks.values.any { it.type == C.TRACK_TYPE_VIDEO && it.format != null }

        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { TrackSink(id, type, this) }

        override fun endTracks() {
            tracksEnded = true
        }

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    /** Takes what an extractor emits for one track, keeps the numbers and throws the sample bytes away. */
    private class TrackSink(val id: Int, val type: Int, private val owner: Collector) : TrackOutput {
        var format: Format? = null
        var durationUs: Long = C.TIME_UNSET

        private var count = 0
        private var bytes = 0L
        private var first = Long.MAX_VALUE
        private var last = Long.MIN_VALUE
        private var keyframes = 0
        private var firstKey = 0L
        private var lastKey = 0L

        override fun format(format: Format) {
            this.format = format
        }

        override fun durationUs(durationUs: Long) {
            this.durationUs = durationUs
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val read = input.read(owner.scratch, 0, minOf(length, owner.scratch.size))
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            owner.sampleBytes += read
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
            owner.sampleBytes += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (type != C.TRACK_TYPE_VIDEO || timeUs < 0L) return
            count++
            bytes += size
            if (timeUs < first) first = timeUs
            if (timeUs > last) last = timeUs
            if (flags and C.BUFFER_FLAG_KEY_FRAME != 0) {
                if (keyframes == 0) firstKey = timeUs
                lastKey = timeUs
                keyframes++
            }
        }

        fun walkedSamples(complete: Boolean): ProbedSamples? {
            if (count == 0) return null
            return ProbedSamples(count, complete, first, last, bytes, keyframes, firstKey, lastKey)
        }
    }

    private companion object {
        /** Wall-clock limit for walking samples of a container that has no sample table. */
        const val WALK_BUDGET_NANOS = 1_500_000_000L
        const val WALK_BUDGET_BYTES = 64L * 1024 * 1024

        /** Stops a file that never declares its tracks from being read to the end. */
        const val HEADERLESS_LIMIT_BYTES = 256L * 1024 * 1024

        /** An all-keyframe two hour file at 60 fps has 432,000 sync samples; a hop is a lookup in memory. */
        const val MAX_KEYFRAME_HOPS = 500_000
        const val KEYFRAME_BUDGET_NANOS = 1_000_000_000L

        const val SCRATCH_BYTES = 64 * 1024
    }
}
