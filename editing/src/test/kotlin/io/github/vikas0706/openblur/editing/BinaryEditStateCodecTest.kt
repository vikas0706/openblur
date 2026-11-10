package io.github.vikas0706.openblur.editing

import io.github.vikas0706.openblur.core.EditOps
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.ManualRegion
import io.github.vikas0706.openblur.core.MaskAppearance
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.MaskStyle
import io.github.vikas0706.openblur.core.RegionKeyframe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BinaryEditStateCodecTest {
    private val codec = BinaryEditStateCodec()

    private val fullState = EditState(
        appearance = MaskAppearance(MaskStyle.MOSAIC, MaskShape.EYES, strength = 0.35f, scale = 1.45f, solidColor = 0xFF102030.toInt()),
        visibleTrackIds = setOf(42, 7, 0, -3, Int.MAX_VALUE, Int.MIN_VALUE),
        personAppearance = mapOf(
            9 to MaskAppearance(MaskStyle.SOLID, MaskShape.RECT, strength = 1f, scale = 0.8f, solidColor = 0xFFFFFFFF.toInt()),
            2 to MaskAppearance(MaskStyle.BLUR, MaskShape.OVAL, strength = 0f, scale = 1.8f),
            -1 to MaskAppearance(),
        ),
        regions = listOf(
            ManualRegion(
                3, MaskAppearance(MaskStyle.SOLID, MaskShape.RECT, solidColor = 0xFF00AA55.toInt()), 0L, 90_000_000L,
                listOf(
                    RegionKeyframe(1_000_000L, 0.1f, 0.2f, 0.3f, 0.4f),
                    RegionKeyframe(2_500_000L, 0.5f, 0.6f, 0.03f, 1f),
                    RegionKeyframe(Long.MAX_VALUE, 1f, 0f, 0.5f, 0.5f),
                ),
            ),
            ManualRegion(1, MaskAppearance(), 5_000_000L, 5_000_000L, listOf(RegionKeyframe(0L, 0.5f, 0.5f, 0.3f, 0.2f))),
        ),
    )

    private fun encode(state: EditState): ByteArray = ByteArrayOutputStream().also { codec.write(state, it) }.toByteArray()

    private fun decode(bytes: ByteArray): EditState = codec.read(ByteArrayInputStream(bytes))

    private fun assertRejected(bytes: ByteArray, why: String) {
        try {
            decode(bytes)
            fail("$why was accepted")
        } catch (expected: IOException) {
            assertTrue("$why gave no message", !expected.message.isNullOrBlank())
        }
    }

    /** Wraps a payload the way the codec does, so a test can check what follows the checksum check. */
    private fun record(version: Int = 1, declaredLength: Int? = null, payload: DataOutputStream.() -> Unit): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).payload()
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeInt(0x4F424553)
        data.writeShort(version)
        data.writeInt(declaredLength ?: body.size())
        data.write(body.toByteArray())
        val crc = CRC32()
        crc.update(out.toByteArray())
        data.writeInt(crc.value.toInt())
        return out.toByteArray()
    }

    private fun DataOutputStream.appearance(style: Int = 0, shape: Int = 0) {
        writeByte(style)
        writeByte(shape)
        writeFloat(0.7f)
        writeFloat(1f)
        writeInt(0xFF000000.toInt())
    }

    @Test
    fun theDefaultStateSurvives() {
        assertEquals(EditState(), decode(encode(EditState())))
    }

    @Test
    fun aStateWithEveryFieldSetSurvives() {
        val decoded = decode(encode(fullState))

        assertEquals(fullState, decoded)
        assertEquals(fullState.regions.map { it.keyframes }, decoded.regions.map { it.keyframes })
        assertArrayEquals(encode(fullState), encode(decoded))
    }

    @Test
    fun everyStyleAndShapeSurvives() {
        for (style in MaskStyle.entries) {
            for (shape in MaskShape.entries) {
                val state = EditState(appearance = MaskAppearance(style, shape))
                assertEquals(state, decode(encode(state)))
            }
        }
    }

    @Test
    fun statesProducedByEditOpsSurvive() {
        val result = scan(listOf(stillTrack(1, 0L, 1_000_000L, personId = 5), stillTrack(2, 0L, 1_000_000L, personId = 6)))
        var state = EditOps.setAllBlurred(EditState(), result, blurred = false)
        state = EditOps.setTrackBlurred(state, 2, blurred = true)
        state = EditOps.setPersonAppearance(state, 6, MaskAppearance(MaskStyle.MOSAIC, strength = 0.9f))
        val (withArea, id) = EditOps.addRegion(state, 500_000L, 1_000_000L, MaskAppearance(shape = MaskShape.EYES))
        state = EditOps.placeRegion(withArea, id, 900_000L, 0.8f, 0.2f, 0.1f, 0.1f, asKeyframe = true)

        assertEquals(state, decode(encode(state)))
    }

    @Test
    fun equalStatesGiveEqualBytesWhateverOrderTheyWereBuiltIn() {
        val forwards = EditState(
            visibleTrackIds = linkedSetOf(1, 2, 3, 400, 5),
            personAppearance = linkedMapOf(1 to MaskAppearance(scale = 1.1f), 2 to MaskAppearance(scale = 1.2f)),
        )
        val backwards = EditState(
            visibleTrackIds = linkedSetOf(5, 400, 3, 2, 1),
            personAppearance = linkedMapOf(2 to MaskAppearance(scale = 1.2f), 1 to MaskAppearance(scale = 1.1f)),
        )

        assertEquals(forwards, backwards)
        assertArrayEquals(encode(forwards), encode(backwards))
    }

    @Test
    fun valuesTheModelDoesNotExpectStillComeBackUnchanged() {
        val odd = EditState(
            appearance = MaskAppearance(strength = Float.NaN, scale = Float.POSITIVE_INFINITY),
            regions = listOf(
                ManualRegion(
                    1, MaskAppearance(strength = -4f, scale = 99f), 10L, -10L,
                    listOf(RegionKeyframe(5L, -1f, 2f, 0f, Float.NaN), RegionKeyframe(5L, 0.5f, 0.5f, 0.5f, 0.5f), RegionKeyframe(1L, 0f, 0f, 0f, 0f)),
                ),
            ),
        )

        assertEquals(odd, decode(encode(odd)))
    }

    @Test
    fun aLargeStateSurvives() {
        val large = EditState(
            visibleTrackIds = (0 until 100_000).toSet(),
            personAppearance = (0 until 2_000).associateWith { MaskAppearance(strength = it / 2_000f) },
            regions = List(200) { id ->
                ManualRegion(id, MaskAppearance(), 0L, 1_000_000L, List(50) { RegionKeyframe(it * 1_000L, 0.5f, 0.5f, 0.1f, 0.1f) })
            },
        )

        assertEquals(large, decode(encode(large)))
    }

    @Test
    fun readTakesOneRecordAndLeavesTheStreamOpen() {
        val bytes = encode(fullState) + encode(EditState()) + byteArrayOf(1, 2, 3)
        var closed = false
        val stream = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun close() {
                closed = true
            }
        }

        assertEquals(fullState, codec.read(stream))
        assertEquals(EditState(), codec.read(stream))
        assertEquals(3, stream.available())
        assertFalse(closed)
    }

    @Test
    fun writeLeavesTheStreamOpenAndWritesNothingElse() {
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
            }
        }
        out.write(0x55)

        codec.write(fullState, out)
        out.write(0x66)

        val bytes = out.toByteArray()
        assertFalse(closed)
        assertEquals(0x55, bytes.first().toInt())
        assertEquals(0x66, bytes.last().toInt())
        assertEquals(fullState, decode(bytes.copyOfRange(1, bytes.size - 1)))
    }

    @Test
    fun aStreamThatDeliversOneByteAtATimeIsReadInFull() {
        val trickle = object : InputStream() {
            private val source = ByteArrayInputStream(encode(fullState))

            override fun read(): Int = source.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, minOf(len, 1))
        }

        assertEquals(fullState, codec.read(trickle))
    }

    @Test
    fun aRecordCutShortAnywhereIsRejected() {
        val bytes = encode(fullState)

        for (length in 0 until bytes.size) {
            assertRejected(bytes.copyOf(length), "a record cut to $length of ${bytes.size} bytes")
        }
    }

    @Test
    fun anyFlippedBitIsRejected() {
        val bytes = encode(fullState)

        for (bit in 0 until bytes.size * 8) {
            val damaged = bytes.copyOf()
            damaged[bit / 8] = (damaged[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
            assertRejected(damaged, "a record with bit $bit flipped")
        }
    }

    @Test
    fun otherFilesAndVersionsAreRejected() {
        val bytes = encode(EditState())

        assertRejected(ByteArray(0), "an empty file")
        assertRejected(ByteArray(64), "a file of zeros")
        assertRejected("OBAR".toByteArray() + bytes.copyOfRange(4, bytes.size), "another magic number")
        assertRejected(record(version = 2) { appearance(); writeInt(0); writeInt(0); writeInt(0) }, "a newer version")
        assertRejected(record(version = 0) { appearance(); writeInt(0); writeInt(0); writeInt(0) }, "version zero")
        assertRejected(record(version = 0xFFFF) { appearance(); writeInt(0); writeInt(0); writeInt(0) }, "version 65535")
    }

    @Test
    fun theSameBytesUnderVersionOneAreAccepted() {
        assertEquals(EditState(), decode(record { appearance(); writeInt(0); writeInt(0); writeInt(0) }))
    }

    @Test
    fun aLengthThatCannotBeRightIsRejectedWithoutReadingOrAllocatingIt() {
        for (length in intArrayOf(-1, Int.MIN_VALUE, 0, 25, 16 * 1024 * 1024 + 1, Int.MAX_VALUE)) {
            assertRejected(record(declaredLength = length) { appearance(); writeInt(0); writeInt(0); writeInt(0) }, "length $length")
        }
        assertRejected(record(declaredLength = 16 * 1024 * 1024) { appearance(); writeInt(0); writeInt(0); writeInt(0) }, "a length past the end")
    }

    @Test
    fun wellFormedRecordsWithBadContentAreRejected() {
        assertRejected(record { appearance(style = 3); writeInt(0); writeInt(0); writeInt(0) }, "an unknown style")
        assertRejected(record { appearance(shape = 3); writeInt(0); writeInt(0); writeInt(0) }, "an unknown shape")
        assertRejected(record { appearance(style = -1); writeInt(0); writeInt(0); writeInt(0) }, "a negative style")
        assertRejected(record { appearance(); writeInt(-1); writeInt(0); writeInt(0) }, "a negative id count")
        assertRejected(record { appearance(); writeInt(5); writeInt(1); writeInt(0); writeInt(0) }, "an id count past the end")
        assertRejected(record { appearance(); writeInt(Int.MAX_VALUE); writeInt(0); writeInt(0) }, "a huge id count")
        assertRejected(record { appearance(); writeInt(0); writeInt(Int.MAX_VALUE); writeInt(0) }, "a huge override count")
        assertRejected(record { appearance(); writeInt(0); writeInt(1); writeInt(4); appearance(shape = 9); writeInt(0) }, "an override with an unknown shape")
        assertRejected(record { appearance(); writeInt(0); writeInt(0); writeInt(Int.MAX_VALUE) }, "a huge region count")
        assertRejected(record { appearance(); writeInt(0); writeInt(0); writeInt(1) }, "a region count with no region")
        assertRejected(record { appearance(); writeInt(0); writeInt(0); writeInt(0); writeByte(0) }, "a byte after the last region")
        assertRejected(
            record {
                appearance(); writeInt(0); writeInt(0); writeInt(1)
                writeInt(7); appearance(); writeLong(0L); writeLong(10L); writeInt(0)
                write(ByteArray(24))
            },
            "a region without keyframes",
        )
        assertRejected(
            record {
                appearance(); writeInt(0); writeInt(0); writeInt(1)
                writeInt(7); appearance(); writeLong(0L); writeLong(10L); writeInt(2)
                writeLong(0L); writeFloat(0.5f); writeFloat(0.5f); writeFloat(0.2f); writeFloat(0.2f)
            },
            "a keyframe count past the end",
        )
        assertRejected(
            record {
                appearance(); writeInt(0); writeInt(0); writeInt(1)
                writeInt(7); appearance(); writeLong(0L); writeLong(10L); writeInt(-2)
                write(ByteArray(24))
            },
            "a negative keyframe count",
        )
    }

    @Test
    fun aHandWrittenRecordDecodesFieldByField() {
        val bytes = record {
            writeByte(2); writeByte(1); writeFloat(0.25f); writeFloat(1.5f); writeInt(0xFF336699.toInt())
            writeInt(2); writeInt(11); writeInt(12)
            writeInt(1); writeInt(4); writeByte(1); writeByte(2); writeFloat(0.5f); writeFloat(1f); writeInt(0xFF000000.toInt())
            writeInt(1)
            writeInt(8); writeByte(0); writeByte(0); writeFloat(0.7f); writeFloat(1f); writeInt(0xFF000000.toInt())
            writeLong(100L); writeLong(900L); writeInt(1)
            writeLong(450L); writeFloat(0.1f); writeFloat(0.2f); writeFloat(0.3f); writeFloat(0.4f)
        }

        val expected = EditState(
            appearance = MaskAppearance(MaskStyle.SOLID, MaskShape.RECT, 0.25f, 1.5f, 0xFF336699.toInt()),
            visibleTrackIds = setOf(11, 12),
            personAppearance = mapOf(4 to MaskAppearance(MaskStyle.MOSAIC, MaskShape.EYES, 0.5f, 1f)),
            regions = listOf(ManualRegion(8, MaskAppearance(), 100L, 900L, listOf(RegionKeyframe(450L, 0.1f, 0.2f, 0.3f, 0.4f)))),
        )
        assertEquals(expected, decode(bytes))
        assertArrayEquals(bytes, encode(expected))
    }

    @Test
    fun randomDamageNeverEscapesAsAnythingButAnIOException() {
        val random = Random(99)
        val bytes = encode(fullState)
        var rejected = 0
        repeat(20_000) {
            val damaged = bytes.copyOf(if (random.nextInt(8) == 0) random.nextInt(bytes.size + 1) else bytes.size)
            repeat(random.nextInt(1, 6)) {
                if (damaged.isNotEmpty()) damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte()
            }
            try {
                decode(damaged)
            } catch (expected: IOException) {
                rejected++
            }
        }
        assertTrue("only $rejected of 20000 damaged records were rejected", rejected > 19_000)
    }

    @Test
    fun randomPayloadsWithAValidChecksumNeverEscapeAsAnythingButAnIOException() {
        val random = Random(123)
        val payload = encode(fullState).let { it.copyOfRange(10, it.size - 4) }
        var accepted = 0
        repeat(20_000) {
            val damaged = payload.copyOf()
            repeat(random.nextInt(1, 4)) { damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
            try {
                decode(record { write(damaged) })
                accepted++
            } catch (expected: IOException) {
                // Rejected is fine; so is accepted when only a value changed. Any other exception fails the test.
            }
        }
        assertTrue("damage to plain values should still decode", accepted > 0)
    }

    @Test
    fun aRegionWithoutKeyframesCannotBeWritten() {
        val broken = EditState(regions = listOf(ManualRegion(1, MaskAppearance(), 0L, 1L, emptyList())))
        val out = ByteArrayOutputStream()

        try {
            codec.write(broken, out)
            fail("a region without keyframes was written")
        } catch (expected: IllegalArgumentException) {
            assertEquals("nothing reaches the stream", 0, out.size())
        }
    }

    @Test
    fun aFailingStreamPassesItsOwnException() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("disk gone")
        }

        try {
            codec.read(failing)
            fail("the stream's failure was swallowed")
        } catch (expected: IOException) {
            assertEquals("disk gone", expected.message)
        }
    }
}
