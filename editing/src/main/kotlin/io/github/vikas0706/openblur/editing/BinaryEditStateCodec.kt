package io.github.vikas0706.openblur.editing

import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.EditStateCodec
import io.github.vikas0706.openblur.core.ManualRegion
import io.github.vikas0706.openblur.core.MaskAppearance
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.MaskStyle
import io.github.vikas0706.openblur.core.RegionKeyframe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Format version 1, big-endian:
 *
 *     magic "OBES" (4) | version (2) | payload length (4) | payload | CRC-32 of everything before it (4)
 *
 *     payload    = appearance | visible track ids | person overrides | regions
 *     appearance = style (1) | shape (1) | strength (4) | scale (4) | solid colour (4)
 *     ids        = count (4) | id (4) each, ascending
 *     overrides  = count (4) | person id (4), appearance each, ascending by id
 *     regions    = count (4) | id (4), appearance, start (8), end (8), keyframe count (4), keyframes each
 *     keyframe   = time (8) | cx (4) | cy (4) | w (4) | h (4)
 *
 * Equal states encode to equal bytes. [read] consumes exactly one record and leaves the stream open.
 */
class BinaryEditStateCodec : EditStateCodec {
    /**
     * Throws [IllegalArgumentException] for a region without keyframes, which no reader could give back,
     * and [IOException] when the state is too large for the format or [out] fails.
     */
    override fun write(state: EditState, out: OutputStream) {
        val body = ByteArrayOutputStream(256)
        val data = DataOutputStream(body)
        writeAppearance(data, state.appearance)

        val visible = state.visibleTrackIds.toIntArray()
        visible.sort()
        data.writeInt(visible.size)
        for (id in visible) data.writeInt(id)

        val overrides = state.personAppearance.entries.sortedBy { it.key }
        data.writeInt(overrides.size)
        for ((personId, appearance) in overrides) {
            data.writeInt(personId)
            writeAppearance(data, appearance)
        }

        data.writeInt(state.regions.size)
        for (region in state.regions) {
            require(region.keyframes.isNotEmpty()) { "Region ${region.id} has no keyframes" }
            data.writeInt(region.id)
            writeAppearance(data, region.appearance)
            data.writeLong(region.startUs)
            data.writeLong(region.endUs)
            data.writeInt(region.keyframes.size)
            for (frame in region.keyframes) {
                data.writeLong(frame.timeUs)
                data.writeFloat(frame.cx)
                data.writeFloat(frame.cy)
                data.writeFloat(frame.w)
                data.writeFloat(frame.h)
            }
        }

        val payloadSize = body.size()
        if (payloadSize > MAX_PAYLOAD_BYTES) throw IOException("Edit state is too large to save: $payloadSize bytes")
        val record = ByteBuffer.allocate(HEADER_BYTES + payloadSize + CHECKSUM_BYTES)
        record.putInt(MAGIC)
        record.putShort(VERSION.toShort())
        record.putInt(payloadSize)
        record.put(body.toByteArray())
        val crc = CRC32()
        crc.update(record.array(), 0, record.position())
        record.putInt(crc.value.toInt())
        // One write call, so a stream that fails part way is less likely to leave half a record behind.
        out.write(record.array())
        out.flush()
    }

    override fun read(input: InputStream): EditState {
        val header = readExactly(input, HEADER_BYTES)
        val fields = ByteBuffer.wrap(header)
        if (fields.int != MAGIC) throw IOException("Not an OpenBlur edit state")
        val version = fields.short.toInt() and 0xFFFF
        if (version != VERSION) throw IOException("Unsupported edit state version $version")
        val payloadSize = fields.int
        if (payloadSize < MIN_PAYLOAD_BYTES || payloadSize > MAX_PAYLOAD_BYTES) {
            throw IOException("Corrupt edit state: payload length $payloadSize")
        }
        val payload = readExactly(input, payloadSize)
        val stored = ByteBuffer.wrap(readExactly(input, CHECKSUM_BYTES)).int
        val crc = CRC32()
        crc.update(header)
        crc.update(payload)
        if (crc.value.toInt() != stored) throw IOException("Corrupt edit state: checksum mismatch")

        try {
            return parse(ByteBuffer.wrap(payload))
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            // The checks in parse should make this unreachable; callers are promised IOException either way.
            throw IOException("Corrupt edit state", e)
        }
    }

    private fun parse(buf: ByteBuffer): EditState {
        val appearance = readAppearance(buf)

        val visibleCount = readCount(buf, Int.SIZE_BYTES)
        val visible = LinkedHashSet<Int>(hashCapacity(visibleCount))
        repeat(visibleCount) { visible += buf.int }

        val overrideCount = readCount(buf, Int.SIZE_BYTES + APPEARANCE_BYTES)
        val overrides = LinkedHashMap<Int, MaskAppearance>(hashCapacity(overrideCount))
        repeat(overrideCount) {
            val personId = buf.int
            overrides[personId] = readAppearance(buf)
        }

        val regionCount = readCount(buf, MIN_REGION_BYTES)
        val regions = ArrayList<ManualRegion>(regionCount)
        repeat(regionCount) {
            need(buf, REGION_HEAD_BYTES)
            val id = buf.int
            val regionAppearance = readAppearance(buf)
            val startUs = buf.long
            val endUs = buf.long
            val frameCount = readCount(buf, KEYFRAME_BYTES)
            if (frameCount == 0) throw IOException("Corrupt edit state: region $id has no keyframes")
            val frames = ArrayList<RegionKeyframe>(frameCount)
            repeat(frameCount) {
                frames += RegionKeyframe(buf.long, buf.float, buf.float, buf.float, buf.float)
            }
            regions += ManualRegion(id, regionAppearance, startUs, endUs, frames)
        }

        if (buf.hasRemaining()) throw IOException("Corrupt edit state: ${buf.remaining()} unread bytes")
        return EditState(appearance, visible, overrides, regions)
    }

    private fun writeAppearance(data: DataOutputStream, appearance: MaskAppearance) {
        data.writeByte(styleCode(appearance.style))
        data.writeByte(shapeCode(appearance.shape))
        data.writeFloat(appearance.strength)
        data.writeFloat(appearance.scale)
        data.writeInt(appearance.solidColor)
    }

    private fun readAppearance(buf: ByteBuffer): MaskAppearance {
        need(buf, APPEARANCE_BYTES)
        val style = styleOf(buf.get().toInt())
        val shape = shapeOf(buf.get().toInt())
        return MaskAppearance(style, shape, strength = buf.float, scale = buf.float, solidColor = buf.int)
    }

    /** Reads an element count and rejects one the rest of the payload could not hold, before anything is sized by it. */
    private fun readCount(buf: ByteBuffer, bytesPerElement: Int): Int {
        need(buf, Int.SIZE_BYTES)
        val count = buf.int
        if (count < 0 || count.toLong() * bytesPerElement > buf.remaining()) {
            throw IOException("Corrupt edit state: count $count does not fit in ${buf.remaining()} bytes")
        }
        return count
    }

    private fun need(buf: ByteBuffer, bytes: Int) {
        if (buf.remaining() < bytes) throw IOException("Corrupt edit state: record ends early")
    }

    /** Grows in steps, so a wrong length in a short file fails at the end of the file and not with a huge buffer. */
    private fun readExactly(input: InputStream, length: Int): ByteArray {
        var buffer = ByteArray(minOf(length, READ_CHUNK_BYTES))
        var filled = 0
        while (filled < length) {
            if (filled == buffer.size) buffer = buffer.copyOf(minOf(length.toLong(), buffer.size * 2L).toInt())
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) throw EOFException("Truncated edit state")
            filled += read
        }
        return buffer
    }

    private fun hashCapacity(entries: Int): Int = (entries * 4L / 3 + 1).coerceAtMost(1L shl 30).toInt()

    // Codes are part of the file format: they stay fixed even if the enums are reordered.
    private fun styleCode(style: MaskStyle): Int = when (style) {
        MaskStyle.BLUR -> 0
        MaskStyle.MOSAIC -> 1
        MaskStyle.SOLID -> 2
    }

    private fun styleOf(code: Int): MaskStyle = when (code) {
        0 -> MaskStyle.BLUR
        1 -> MaskStyle.MOSAIC
        2 -> MaskStyle.SOLID
        else -> throw IOException("Corrupt edit state: unknown mask style $code")
    }

    private fun shapeCode(shape: MaskShape): Int = when (shape) {
        MaskShape.OVAL -> 0
        MaskShape.RECT -> 1
        MaskShape.EYES -> 2
    }

    private fun shapeOf(code: Int): MaskShape = when (code) {
        0 -> MaskShape.OVAL
        1 -> MaskShape.RECT
        2 -> MaskShape.EYES
        else -> throw IOException("Corrupt edit state: unknown mask shape $code")
    }

    private companion object {
        /** "OBES" in ASCII. */
        const val MAGIC = 0x4F424553
        const val VERSION = 1

        const val HEADER_BYTES = 10
        const val CHECKSUM_BYTES = 4
        const val APPEARANCE_BYTES = 14
        const val KEYFRAME_BYTES = 24
        const val REGION_HEAD_BYTES = Int.SIZE_BYTES + APPEARANCE_BYTES + 2 * Long.SIZE_BYTES
        const val MIN_REGION_BYTES = REGION_HEAD_BYTES + Int.SIZE_BYTES + KEYFRAME_BYTES

        /** An appearance and three empty lists. */
        const val MIN_PAYLOAD_BYTES = APPEARANCE_BYTES + 3 * Int.SIZE_BYTES

        /** Room for about four million visible track ids, far beyond any real project. */
        const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024

        const val READ_CHUNK_BYTES = 8 * 1024
    }
}
