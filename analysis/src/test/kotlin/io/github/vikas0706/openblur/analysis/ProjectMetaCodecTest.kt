package io.github.vikas0706.openblur.analysis

import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.TransferKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectMetaCodecTest {
    private fun meta(
        sourceUri: String = "content://media/picker/0/com.android.providers.media.photopicker/media/1000012345",
        displayName: String = "Geburtstag über den Dächern 🎂.mp4",
        audioMimeType: String? = "audio/mp4a-latm",
        options: AnalysisOptions = AnalysisOptions.THOROUGH.copy(groupPeople = false),
    ) = ProjectMeta(
        generation = 3,
        sourceUri = sourceUri,
        displayName = displayName,
        info = Fixtures.info(audioMimeType = audioMimeType),
        options = options,
        createdAtMs = 1_794_300_000_000L,
        updatedAtMs = 1_794_300_123_456L,
        durationUs = 12_500_000L,
        peopleCount = 4,
        coverHandle = 17,
    )

    private fun encode(meta: ProjectMeta): ByteArray =
        ByteArrayOutputStream().also { ProjectMetaCodec.write(meta, it) }.toByteArray()

    private fun decode(bytes: ByteArray): ProjectMeta = ProjectMetaCodec.read(ByteArrayInputStream(bytes))

    @Test
    fun everyFieldSurvivesARoundTrip() {
        val original = meta()
        assertEquals(original, decode(encode(original)))
    }

    @Test
    fun missingAudioAndDefaultsSurviveARoundTrip() {
        val original = meta(audioMimeType = null, options = AnalysisOptions.STANDARD)
        val decoded = decode(encode(original))
        assertEquals(original, decoded)
        assertEquals(null, decoded.info.audioMimeType)
        assertEquals(AnalysisOptions.STANDARD, decoded.options)
    }

    @Test
    fun everyTransferKindSurvivesARoundTrip() {
        for (transfer in TransferKind.entries) {
            val original = meta().let { it.copy(info = it.info.copy(transfer = transfer)) }
            assertEquals(transfer, decode(encode(original)).info.transfer)
        }
    }

    @Test
    fun textLongerThanSixtyFourKilobytesSurvives() {
        val uri = "content://provider/" + "a%2Fvery%2Flong%2Fpath".repeat(4_000)
        assertEquals(uri, decode(encode(meta(sourceUri = uri))).sourceUri)
    }

    @Test
    fun truncatedDataIsRejectedAtEveryLength() {
        val bytes = encode(meta())
        for (length in 0 until bytes.size) {
            assertThrows("length $length", IOException::class.java) { decode(bytes.copyOf(length)) }
        }
    }

    @Test
    fun anyFlippedPayloadByteIsRejected() {
        val bytes = encode(meta())
        // Header is magic, version, length: 12 bytes. Everything after it is covered by the checksum.
        for (index in 12 until bytes.size) {
            val damaged = bytes.clone()
            damaged[index] = (damaged[index].toInt() xor 0x40).toByte()
            assertThrows("byte $index", IOException::class.java) { decode(damaged) }
        }
    }

    @Test
    fun wrongMagicUnknownVersionAndAbsurdLengthAreRejected() {
        val bytes = encode(meta())
        val wrongMagic = bytes.clone().also { it[0] = 0 }
        assertThrows(IOException::class.java) { decode(wrongMagic) }

        val newerVersion = bytes.clone().also { ByteBuffer.wrap(it).putInt(4, 2) }
        assertThrows(IOException::class.java) { decode(newerVersion) }

        val hugeLength = bytes.clone().also { ByteBuffer.wrap(it).putInt(8, Int.MAX_VALUE) }
        assertThrows(IOException::class.java) { decode(hugeLength) }

        val negativeLength = bytes.clone().also { ByteBuffer.wrap(it).putInt(8, -1) }
        assertThrows(IOException::class.java) { decode(negativeLength) }
    }

    @Test
    fun emptyInputIsRejected() {
        assertThrows(IOException::class.java) { decode(ByteArray(0)) }
    }
}
