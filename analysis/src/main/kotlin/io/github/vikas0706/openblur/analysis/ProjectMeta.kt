package io.github.vikas0706.openblur.analysis

import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.core.TransferKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/** What a project folder records besides the scan and the edit. Writing this file is what makes a project saved. */
internal data class ProjectMeta(
    /** Names the result and edit files this metadata belongs to, so a half-finished save is never read. */
    val generation: Int,
    val sourceUri: String,
    val displayName: String,
    val info: SourceVideoInfo,
    val options: AnalysisOptions,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** Copied from the scan so listing projects does not have to decode every result. */
    val durationUs: Long,
    val peopleCount: Int,
    /** Thumbnail handle of the first person, or -1. */
    val coverHandle: Int,
)

/** Layout: magic, version, payload length, payload, CRC32 of the payload. All integers big-endian. */
internal object ProjectMetaCodec {
    private const val MAGIC = 0x4F424D54
    private const val VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 1 shl 20

    fun write(meta: ProjectMeta, out: OutputStream) {
        val payload = ByteArrayOutputStream(512)
        DataOutputStream(payload).run {
            writeInt(meta.generation)
            writeText(meta.sourceUri)
            writeText(meta.displayName)
            writeInfo(meta.info)
            writeInt(meta.options.detectorLongSide)
            writeFloat(meta.options.maxDetectionsPerSecond)
            writeBoolean(meta.options.groupPeople)
            writeLong(meta.createdAtMs)
            writeLong(meta.updatedAtMs)
            writeLong(meta.durationUs)
            writeInt(meta.peopleCount)
            writeInt(meta.coverHandle)
            flush()
        }
        val bytes = payload.toByteArray()
        DataOutputStream(out).run {
            writeInt(MAGIC)
            writeInt(VERSION)
            writeInt(bytes.size)
            write(bytes)
            writeLong(CRC32().apply { update(bytes) }.value)
            flush()
        }
    }

    /** Throws [IOException] when the data is truncated, corrupt or from an unknown version. */
    fun read(input: InputStream): ProjectMeta {
        val header = DataInputStream(input)
        if (header.readInt() != MAGIC) throw IOException("Not a project metadata file")
        val version = header.readInt()
        if (version != VERSION) throw IOException("Unknown project metadata version $version")
        val length = header.readInt()
        if (length !in 0..MAX_PAYLOAD_BYTES) throw IOException("Implausible project metadata length $length")
        val bytes = ByteArray(length)
        header.readFully(bytes)
        if (header.readLong() != CRC32().apply { update(bytes) }.value) {
            throw IOException("Project metadata checksum mismatch")
        }
        val data = DataInputStream(ByteArrayInputStream(bytes))
        return ProjectMeta(
            generation = data.readInt(),
            sourceUri = data.readText(),
            displayName = data.readText(),
            info = data.readInfo(),
            options = AnalysisOptions(
                detectorLongSide = data.readInt(),
                maxDetectionsPerSecond = data.readFloat(),
                groupPeople = data.readBoolean(),
            ),
            createdAtMs = data.readLong(),
            updatedAtMs = data.readLong(),
            durationUs = data.readLong(),
            peopleCount = data.readInt(),
            coverHandle = data.readInt(),
        )
    }

    private fun DataOutputStream.writeInfo(info: SourceVideoInfo) {
        writeText(info.mimeType)
        writeInt(info.codedWidth)
        writeInt(info.codedHeight)
        writeInt(info.rotationDegrees)
        writeFloat(info.frameRate)
        writeInt(info.bitrate)
        writeLong(info.durationUs)
        writeInt(info.frameCount)
        writeFloat(info.keyframeIntervalSeconds)
        writeText(info.transfer.name)
        writeInt(info.bitDepth)
        writeBoolean(info.isDolbyVision)
        writeBoolean(info.audioMimeType != null)
        info.audioMimeType?.let { writeText(it) }
        writeInt(info.audioTrackCount)
        writeBoolean(info.hasLocation)
        writeLong(info.fileSizeBytes)
    }

    private fun DataInputStream.readInfo(): SourceVideoInfo = SourceVideoInfo(
        mimeType = readText(),
        codedWidth = readInt(),
        codedHeight = readInt(),
        rotationDegrees = readInt(),
        frameRate = readFloat(),
        bitrate = readInt(),
        durationUs = readLong(),
        frameCount = readInt(),
        keyframeIntervalSeconds = readFloat(),
        transfer = readTransfer(),
        bitDepth = readInt(),
        isDolbyVision = readBoolean(),
        audioMimeType = if (readBoolean()) readText() else null,
        audioTrackCount = readInt(),
        hasLocation = readBoolean(),
        fileSizeBytes = readLong(),
    )

    // Stored by name so reordering the enum cannot silently change what an old file means.
    private fun DataInputStream.readTransfer(): TransferKind {
        val name = readText()
        return TransferKind.entries.firstOrNull { it.name == name }
            ?: throw IOException("Unknown transfer kind $name")
    }

    // writeUTF stops at 65535 bytes, which a long content Uri with an encoded path could pass.
    private fun DataOutputStream.writeText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val length = readInt()
        if (length !in 0..MAX_PAYLOAD_BYTES) throw IOException("Implausible text length $length")
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
