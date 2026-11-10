package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.FaceTrack
import io.github.vikas0706.openblur.core.Person
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BinaryAnalysisResultCodecTest {
    private val codec = BinaryAnalysisResultCodec()

    private fun sample(): AnalysisResult {
        val nan = Float.NaN
        val first = FaceTrack(
            id = 1,
            personId = 1,
            timesUs = longArrayOf(-5, 33_333, 66_667, 7_000_000_000L),
            boxes = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.07f, 0.08f, 0.9f, 0.1f, 0.11f, 0.12f, 0.5f, 0.5f, 1f, 1f),
            eyes = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, nan, nan, nan, nan, 0.5f, 0.5f, 0.6f, 0.5f, -0.1f, 1.2f, 0f, 0f),
            sampleKinds = byteArrayOf(2, 0, 1, 2),
            thumbnailHandle = 7,
            meanScore = 0.8125f,
        )
        val second = FaceTrack(
            id = 2,
            personId = 2,
            timesUs = longArrayOf(100, 200),
            boxes = FloatArray(8) { 0.25f },
            eyes = FloatArray(8) { nan },
            sampleKinds = byteArrayOf(0, 2),
            thumbnailHandle = -1,
            meanScore = 0.7f,
        )
        val people = listOf(
            Person(1, intArrayOf(1), 7, -5, 7_000_000_005L),
            Person(2, intArrayOf(2), -1, 100, 100),
        )
        return AnalysisResult(3840, 2160, 7_200_000_000L, listOf(first, second), people, longArrayOf(1_000_000, 2_500_000), 216_000, 108_000)
    }

    private fun encode(result: AnalysisResult): ByteArray = ByteArrayOutputStream().also { codec.write(result, it) }.toByteArray()

    private fun assertSame(expected: AnalysisResult, actual: AnalysisResult) {
        assertEquals(expected.frameWidth, actual.frameWidth)
        assertEquals(expected.frameHeight, actual.frameHeight)
        assertEquals(expected.durationUs, actual.durationUs)
        assertEquals(expected.framesSeen, actual.framesSeen)
        assertEquals(expected.framesDetected, actual.framesDetected)
        assertArrayEquals(expected.cutsUs, actual.cutsUs)
        assertEquals(expected.tracks.size, actual.tracks.size)
        for (i in expected.tracks.indices) {
            val e = expected.tracks[i]
            val a = actual.tracks[i]
            assertEquals(e.id, a.id)
            assertEquals(e.personId, a.personId)
            assertEquals(e.thumbnailHandle, a.thumbnailHandle)
            assertEquals(e.meanScore.toRawBits(), a.meanScore.toRawBits())
            assertArrayEquals(e.timesUs, a.timesUs)
            assertArrayEquals(e.sampleKinds, a.sampleKinds)
            assertArrayEquals(IntArray(e.boxes.size) { e.boxes[it].toRawBits() }, IntArray(a.boxes.size) { a.boxes[it].toRawBits() })
            assertArrayEquals(IntArray(e.eyes.size) { e.eyes[it].toRawBits() }, IntArray(a.eyes.size) { a.eyes[it].toRawBits() })
        }
        assertEquals(expected.people.size, actual.people.size)
        for (i in expected.people.indices) {
            val e = expected.people[i]
            val a = actual.people[i]
            assertEquals(e.id, a.id)
            assertArrayEquals(e.trackIds, a.trackIds)
            assertEquals(e.thumbnailHandle, a.thumbnailHandle)
            assertEquals(e.firstSeenUs, a.firstSeenUs)
            assertEquals(e.visibleUs, a.visibleUs)
        }
    }

    @Test
    fun roundTripKeepsEveryField() {
        val result = sample()
        assertSame(result, codec.read(ByteArrayInputStream(encode(result))))
    }

    @Test
    fun emptyResultRoundTrips() {
        val empty = AnalysisResult(320, 240, 0, emptyList(), emptyList(), LongArray(0), 0, 0)
        val bytes = encode(empty)
        assertEquals(8 + 28 + 4 + 4 + 4, bytes.size)
        assertSame(empty, codec.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun formatIsLittleEndianWithMagicVersionAndChecksum() {
        val bytes = encode(sample())
        assertEquals("OBAR", String(bytes, 0, 4, Charsets.US_ASCII))
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, view.getInt(4))
        assertEquals(3840, view.getInt(8))
        assertEquals(2160, view.getInt(12))
        assertEquals(7_200_000_000L, view.getLong(16))
        val crc = CRC32().also { it.update(bytes, 0, bytes.size - 4) }
        assertEquals(crc.value.toInt(), view.getInt(bytes.size - 4))
        // 41 bytes per sample and nothing else that grows with the track.
        assertEquals(8 + 28 + 16 + 4 + (20 + 4 * 41) + (20 + 2 * 41) + 4 + 2 * (28 + 4) + 4, bytes.size)
    }

    @Test
    fun scanResultRoundTrips() {
        val scan = Scan()
        for (i in 0 until 150) {
            val faces = ArrayList<Face>()
            if (i !in 40..50) faces += Face(1, 300f + i * 5f, 400f, 180f, landmarks = i % 7 != 0)
            if (i > 60) faces += Face(2, 1500f, 700f, 140f)
            scan.frame(at30(i), faces, scene = if (i < 100) 1 else 2)
        }
        val result = scan.finish(at30(150))
        assertTrue(result.tracks.size >= 3)
        assertEquals(1, result.cutsUs.size)
        assertSame(result, codec.read(ByteArrayInputStream(encode(result))))
    }

    @Test
    fun largeTrackRoundTripsAcrossBufferBoundaries() {
        val n = 70_001
        val track = FaceTrack(
            1, 1,
            LongArray(n) { it * 33_333L },
            FloatArray(n * 4) { (it % 1000) / 1000f },
            FloatArray(n * 4) { if (it % 13 == 0) Float.NaN else it / (n * 4f) },
            ByteArray(n) { (it % 3).toByte() },
            3, 0.5f,
        )
        val result = AnalysisResult(1920, 1080, n * 33_333L, listOf(track), listOf(Person(1, intArrayOf(1), 3, 0, (n - 1) * 33_333L)), LongArray(20_000) { it * 7L }, n, n)
        // One byte at a time, to show the reader copes with a stream that returns short reads.
        val trickle = object : InputStream() {
            private val source = ByteArrayInputStream(encode(result))
            override fun read(): Int = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else source.read(b, off, minOf(len, 977))
        }
        assertSame(result, codec.read(trickle))
    }

    @Test
    fun readStopsAtTheEndOfTheScan() {
        val bytes = encode(sample())
        val stream = ByteArrayInputStream(bytes + byteArrayOf(1, 2, 3, 4, 5))
        codec.read(stream)
        assertEquals("bytes after the scan are left for the caller", 5, stream.available())
    }

    @Test
    fun twoScansInOneStream() {
        val out = ByteArrayOutputStream()
        codec.write(sample(), out)
        val empty = AnalysisResult(320, 240, 0, emptyList(), emptyList(), LongArray(0), 0, 0)
        codec.write(empty, out)
        val stream = ByteArrayInputStream(out.toByteArray())
        assertSame(sample(), codec.read(stream))
        assertSame(empty, codec.read(stream))
        assertEquals(0, stream.available())
    }

    @Test
    fun everyTruncationIsAnIoException() {
        val bytes = encode(sample())
        for (length in 0 until bytes.size) {
            try {
                codec.read(ByteArrayInputStream(bytes, 0, length))
                fail("a scan cut to $length of ${bytes.size} bytes was accepted")
            } catch (expected: IOException) {
                // Wanted.
            }
        }
    }

    @Test
    fun wrongMagicIsAnIoException() {
        val bytes = encode(sample())
        bytes[0] = 'X'.code.toByte()
        val error = assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(bytes)) }
        assertTrue(error.message!!.contains("magic"))
        assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream("not a scan at all, just some text".toByteArray())) }
        assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(ByteArray(0))) }
    }

    @Test
    fun futureVersionIsAnIoException() {
        val bytes = encode(sample())
        bytes[4] = 2
        val error = assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(bytes)) }
        assertTrue(error.message!!.contains("version 2"))
        bytes[4] = 0
        assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun everySingleByteCorruptionIsAnIoException() {
        val bytes = encode(sample())
        for (at in bytes.indices) {
            for (flip in intArrayOf(0x01, 0x80, 0xFF)) {
                val damaged = bytes.copyOf()
                damaged[at] = (damaged[at].toInt() xor flip).toByte()
                try {
                    codec.read(ByteArrayInputStream(damaged))
                    fail("byte $at xor $flip was accepted")
                } catch (expected: IOException) {
                    // Wanted. Any other exception, or an OutOfMemoryError from a damaged length, fails the test.
                }
            }
        }
    }

    @Test
    fun forgedLengthsDoNotReserveMemory() {
        fun header(cuts: Int): ByteBuffer {
            val buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("OBAR".toByteArray(Charsets.US_ASCII)).putInt(1)
            buffer.putInt(1920).putInt(1080).putLong(1_000_000).putInt(30).putInt(30).putInt(cuts)
            return buffer
        }

        fun bytesOf(buffer: ByteBuffer): ByteArray = buffer.array().copyOf(buffer.position())

        // Two billion cut times would need 16 GB.
        assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(bytesOf(header(Int.MAX_VALUE)))) }
        assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(bytesOf(header(-1)))) }

        val hugeTrackCount = header(0).putInt(Int.MAX_VALUE)
        assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(bytesOf(hugeTrackCount))) }

        val hugeSampleCount = header(0).putInt(1).putInt(1).putInt(1).putInt(-1).putFloat(0.5f).putInt(Int.MAX_VALUE / 4)
        assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(bytesOf(hugeSampleCount))) }

        val overflowingSampleCount = header(0).putInt(1).putInt(1).putInt(1).putInt(-1).putFloat(0.5f).putInt(Int.MAX_VALUE)
        assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(bytesOf(overflowingSampleCount))) }

        val negativeSampleCount = header(0).putInt(1).putInt(1).putInt(1).putInt(-1).putFloat(0.5f).putInt(-7)
        assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(bytesOf(negativeSampleCount))) }

        val hugePersonTracks = header(0).putInt(0).putInt(1).putInt(1).putInt(-1).putLong(0).putLong(0).putInt(Int.MAX_VALUE)
        assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(bytesOf(hugePersonTracks))) }
    }

    @Test
    fun wellFormedFileWithABrokenTrackIsAnIoException() {
        fun withChecksum(body: ByteBuffer): ByteArray {
            val bytes = body.array().copyOf(body.position() + 4)
            val crc = CRC32().also { it.update(bytes, 0, bytes.size - 4) }
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size - 4, crc.value.toInt())
            return bytes
        }

        fun fileWithTimes(vararg times: Long): ByteArray {
            val buffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("OBAR".toByteArray(Charsets.US_ASCII)).putInt(1)
            buffer.putInt(1920).putInt(1080).putLong(1_000_000).putInt(30).putInt(30).putInt(0)
            buffer.putInt(1).putInt(1).putInt(1).putInt(-1).putFloat(0.5f).putInt(times.size)
            for (time in times) buffer.putLong(time)
            repeat(times.size * 8) { buffer.putFloat(0.5f) }
            repeat(times.size) { buffer.put(0) }
            buffer.putInt(0)
            return withChecksum(buffer)
        }

        codec.read(ByteArrayInputStream(fileWithTimes(10, 20)))
        assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(fileWithTimes(10))) }
        assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(fileWithTimes())) }
        assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(fileWithTimes(10, 10))) }
        assertThrows(IOException::class.java) { codec.read(ByteArrayInputStream(fileWithTimes(10, 20, 15))) }
    }

    @Test
    fun writeRefusesWhatCouldNotBeReadBack() {
        fun resultWith(track: FaceTrack) = AnalysisResult(10, 10, 10, listOf(track), emptyList(), LongArray(0), 1, 1)
        val oneSample = FaceTrack(1, 1, longArrayOf(5), FloatArray(4), FloatArray(4), ByteArray(1), -1, 0.5f)
        val shortBoxes = FaceTrack(1, 1, longArrayOf(5, 6), FloatArray(7), FloatArray(8), ByteArray(2), -1, 0.5f)
        val unsorted = FaceTrack(1, 1, longArrayOf(6, 5), FloatArray(8), FloatArray(8), ByteArray(2), -1, 0.5f)
        for (track in listOf(oneSample, shortBoxes, unsorted)) {
            val out = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) { codec.write(resultWith(track), out) }
            assertEquals("nothing is written for a refused result", 0, out.size())
        }
    }

    @Test
    fun streamFailuresPassThrough() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("disk gone")
        }
        val error = assertThrows(IOException::class.java) { codec.read(failing) }
        assertEquals("disk gone", error.message)
    }
}
