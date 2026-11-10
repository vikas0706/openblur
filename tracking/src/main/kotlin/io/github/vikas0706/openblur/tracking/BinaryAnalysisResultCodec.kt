package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.AnalysisResultCodec
import io.github.vikas0706.openblur.core.FaceTrack
import io.github.vikas0706.openblur.core.Person
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.min

/**
 * Fixed-width little-endian fields behind a magic number and a version, closed by a CRC-32 of everything before it.
 * Neither [write] nor [read] closes its stream, and [read] takes no byte past the checksum.
 */
class BinaryAnalysisResultCodec : AnalysisResultCodec {
    /** Throws [IllegalArgumentException] for a result that breaks the [FaceTrack] contract and could not be read back. */
    override fun write(result: AnalysisResult, out: OutputStream) {
        require(result.frameWidth > 0 && result.frameHeight > 0) { "frame size ${result.frameWidth}x${result.frameHeight}" }
        for (track in result.tracks) {
            val problem = problemWith(track.timesUs, track.boxes, track.eyes.size, track.sampleKinds.size)
            require(problem == null) { "track ${track.id}: $problem" }
        }
        val writer = Writer(out)
        writer.int(MAGIC)
        writer.int(VERSION)
        writer.int(result.frameWidth)
        writer.int(result.frameHeight)
        writer.long(result.durationUs)
        writer.int(result.framesSeen)
        writer.int(result.framesDetected)
        writer.int(result.cutsUs.size)
        writer.longs(result.cutsUs)
        writer.int(result.tracks.size)
        for (track in result.tracks) {
            writer.int(track.id)
            writer.int(track.personId)
            writer.int(track.thumbnailHandle)
            writer.int(track.meanScore.toRawBits())
            writer.int(track.timesUs.size)
            writer.longs(track.timesUs)
            writer.floats(track.boxes)
            writer.floats(track.eyes)
            writer.bytes(track.sampleKinds)
        }
        writer.int(result.people.size)
        for (person in result.people) {
            writer.int(person.id)
            writer.int(person.thumbnailHandle)
            writer.long(person.firstSeenUs)
            writer.long(person.visibleUs)
            writer.int(person.trackIds.size)
            writer.ints(person.trackIds)
        }
        writer.finish()
    }

    override fun read(input: InputStream): AnalysisResult {
        val reader = Reader(input)
        reader.fill(8)
        if (reader.int() != MAGIC) throw IOException("not an OpenBlur scan: wrong magic number")
        val version = reader.int()
        if (version != VERSION) throw IOException("unsupported scan format version $version, this build reads $VERSION")
        reader.fill(HEADER_BYTES)
        val frameWidth = reader.int()
        val frameHeight = reader.int()
        if (frameWidth <= 0 || frameHeight <= 0) throw IOException("frame size ${frameWidth}x$frameHeight")
        val durationUs = reader.long()
        val framesSeen = reader.int()
        val framesDetected = reader.int()
        val cutsUs = reader.longs(reader.count("cut"))

        reader.fill(4)
        val trackCount = reader.count("track")
        // The declared count is not trusted for the list's size: every track must actually arrive first.
        val tracks = ArrayList<FaceTrack>(min(trackCount, 1024))
        for (i in 0 until trackCount) {
            reader.fill(TRACK_HEADER_BYTES)
            val id = reader.int()
            val personId = reader.int()
            val thumbnailHandle = reader.int()
            val meanScore = Float.fromBits(reader.int())
            val samples = reader.count("sample")
            if (samples > MAX_SAMPLES) throw IOException("track $id declares $samples samples")
            val timesUs = reader.longs(samples)
            val boxes = reader.floats(samples * 4)
            val eyes = reader.floats(samples * 4)
            val kinds = reader.bytes(samples)
            val problem = problemWith(timesUs, boxes, eyes.size, kinds.size)
            if (problem != null) throw IOException("track $id: $problem")
            tracks.add(FaceTrack(id, personId, timesUs, boxes, eyes, kinds, thumbnailHandle, meanScore))
        }

        reader.fill(4)
        val personCount = reader.count("person")
        val people = ArrayList<Person>(min(personCount, 1024))
        for (i in 0 until personCount) {
            reader.fill(PERSON_HEADER_BYTES)
            val id = reader.int()
            val thumbnailHandle = reader.int()
            val firstSeenUs = reader.long()
            val visibleUs = reader.long()
            val trackIds = reader.ints(reader.count("person track"))
            people.add(Person(id, trackIds, thumbnailHandle, firstSeenUs, visibleUs))
        }
        reader.verifyChecksum()
        return AnalysisResult(frameWidth, frameHeight, durationUs, tracks, people, cutsUs, framesSeen, framesDetected)
    }

    /** The one rule set shared by both directions, so nothing can be written that would be refused on reading. */
    private fun problemWith(timesUs: LongArray, boxes: FloatArray, eyeValues: Int, kinds: Int): String? {
        val n = timesUs.size
        if (n < 2) return "has $n samples, a track needs at least two"
        if (boxes.size.toLong() != n * 4L || eyeValues.toLong() != n * 4L || kinds != n) return "array sizes do not match $n samples"
        for (i in 1 until n) {
            if (timesUs[i] <= timesUs[i - 1]) return "sample times do not increase at index $i"
        }
        // A NaN or infinite box draws no mask, so the face would be exported unblurred.
        for (i in boxes.indices) {
            if (!boxes[i].isFinite()) return "box value ${boxes[i]} at sample ${i / 4}"
        }
        return null
    }

    private class Writer(private val out: OutputStream) {
        private val buffer = ByteArray(BUFFER_BYTES)
        private var used = 0
        private val crc = CRC32()

        fun int(value: Int) {
            if (used + 4 > buffer.size) flush()
            putInt(value)
        }

        fun long(value: Long) {
            if (used + 8 > buffer.size) flush()
            putInt(value.toInt())
            putInt((value ushr 32).toInt())
        }

        fun ints(values: IntArray) {
            for (value in values) int(value)
        }

        fun longs(values: LongArray) {
            for (value in values) long(value)
        }

        fun floats(values: FloatArray) {
            // Raw bits, so the NaN of an unknown eye comes back as the same NaN.
            for (value in values) int(value.toRawBits())
        }

        fun bytes(values: ByteArray) {
            flush()
            crc.update(values, 0, values.size)
            out.write(values, 0, values.size)
        }

        fun finish() {
            flush()
            val sum = crc.value.toInt()
            putInt(sum)
            out.write(buffer, 0, used)
            used = 0
        }

        private fun putInt(value: Int) {
            buffer[used] = value.toByte()
            buffer[used + 1] = (value ushr 8).toByte()
            buffer[used + 2] = (value ushr 16).toByte()
            buffer[used + 3] = (value ushr 24).toByte()
            used += 4
        }

        private fun flush() {
            if (used == 0) return
            crc.update(buffer, 0, used)
            out.write(buffer, 0, used)
            used = 0
        }
    }

    /**
     * Reads exact byte counts and never more, so the stream can hold other data after the scan.
     * Arrays grow as their bytes arrive, which keeps a forged length from reserving memory the stream cannot fill.
     */
    private class Reader(private val input: InputStream) {
        private val buffer = ByteArray(BUFFER_BYTES)
        private var at = 0
        private var end = 0
        private val crc = CRC32()

        /** Loads exactly [bytes] bytes for the scalar getters that follow, which must use all of them. */
        fun fill(bytes: Int) {
            load(bytes)
            crc.update(buffer, 0, bytes)
        }

        fun int(): Int {
            if (at + 4 > end) fill(4)
            val value = (buffer[at].toInt() and 0xFF) or
                ((buffer[at + 1].toInt() and 0xFF) shl 8) or
                ((buffer[at + 2].toInt() and 0xFF) shl 16) or
                ((buffer[at + 3].toInt() and 0xFF) shl 24)
            at += 4
            return value
        }

        fun long(): Long {
            if (at + 8 > end) fill(8)
            val low = int().toLong() and 0xFFFFFFFFL
            val high = int().toLong()
            return low or (high shl 32)
        }

        fun count(what: String): Int {
            val n = int()
            if (n < 0) throw IOException("negative $what count $n")
            return n
        }

        fun ints(n: Int): IntArray {
            var out = IntArray(min(n, CHUNK_VALUES))
            var done = 0
            while (done < n) {
                val take = min(n - done, CHUNK_VALUES)
                if (done + take > out.size) out = out.copyOf(grown(out.size, done + take, n))
                fill(take * 4)
                for (i in 0 until take) out[done + i] = int()
                done += take
            }
            return out
        }

        fun floats(n: Int): FloatArray {
            var out = FloatArray(min(n, CHUNK_VALUES))
            var done = 0
            while (done < n) {
                val take = min(n - done, CHUNK_VALUES)
                if (done + take > out.size) out = out.copyOf(grown(out.size, done + take, n))
                fill(take * 4)
                for (i in 0 until take) out[done + i] = Float.fromBits(int())
                done += take
            }
            return out
        }

        fun longs(n: Int): LongArray {
            var out = LongArray(min(n, CHUNK_VALUES))
            var done = 0
            while (done < n) {
                val take = min(n - done, CHUNK_VALUES)
                if (done + take > out.size) out = out.copyOf(grown(out.size, done + take, n))
                fill(take * 8)
                for (i in 0 until take) out[done + i] = long()
                done += take
            }
            return out
        }

        fun bytes(n: Int): ByteArray {
            var out = ByteArray(min(n, BUFFER_BYTES))
            var done = 0
            while (done < n) {
                val take = min(n - done, BUFFER_BYTES)
                if (done + take > out.size) out = out.copyOf(grown(out.size, done + take, n))
                readFully(out, done, take)
                crc.update(out, done, take)
                done += take
            }
            return out
        }

        fun verifyChecksum() {
            val expected = crc.value.toInt()
            load(4)
            if (int() != expected) throw IOException("scan data is corrupt: checksum mismatch")
        }

        private fun load(bytes: Int) {
            check(at == end) { "${end - at} loaded bytes were not read" }
            readFully(buffer, 0, bytes)
            at = 0
            end = bytes
        }

        private fun readFully(into: ByteArray, offset: Int, length: Int) {
            var done = 0
            while (done < length) {
                val got = input.read(into, offset + done, length - done)
                if (got < 0) throw EOFException("scan data ends early")
                done += got
            }
        }

        private fun grown(current: Int, needed: Int, limit: Int): Int = min(limit.toLong(), max(current * 2L, needed.toLong())).toInt()
    }

    private companion object {
        /** "OBAR" when the four bytes are read in file order. */
        const val MAGIC = 0x5241424F
        const val VERSION = 1

        /** Everything from frameWidth to the cut count. */
        const val HEADER_BYTES = 28
        const val TRACK_HEADER_BYTES = 20
        const val PERSON_HEADER_BYTES = 28
        const val BUFFER_BYTES = 1 shl 16
        const val CHUNK_VALUES = BUFFER_BYTES / 8

        /** Four values per sample must still fit an array index. */
        const val MAX_SAMPLES = Int.MAX_VALUE / 4
    }
}
