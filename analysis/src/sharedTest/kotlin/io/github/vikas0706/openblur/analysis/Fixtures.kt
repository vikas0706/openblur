package io.github.vikas0706.openblur.analysis

import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.AnalysisResultCodec
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.EditStateCodec
import io.github.vikas0706.openblur.core.FaceTrack
import io.github.vikas0706.openblur.core.ManualRegion
import io.github.vikas0706.openblur.core.MaskAppearance
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.MaskStyle
import io.github.vikas0706.openblur.core.Person
import io.github.vikas0706.openblur.core.RegionKeyframe
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.core.TransferKind
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Test data and stand-in codecs. The real codecs live in modules this one does not depend on. */
internal object Fixtures {
    fun info(durationUs: Long = 12_500_000L, audioMimeType: String? = "audio/mp4a-latm") = SourceVideoInfo(
        mimeType = "video/hevc",
        codedWidth = 3840,
        codedHeight = 2160,
        rotationDegrees = 90,
        frameRate = 59.94f,
        bitrate = 48_000_000,
        durationUs = durationUs,
        frameCount = 749,
        keyframeIntervalSeconds = 1.001f,
        transfer = TransferKind.HLG,
        bitDepth = 10,
        isDolbyVision = true,
        audioMimeType = audioMimeType,
        audioTrackCount = if (audioMimeType == null) 0 else 2,
        hasLocation = true,
        fileSizeBytes = 75_000_123L,
    )

    /** [thumbnails] gives one handle per person; each person gets one track with the same handle. */
    fun result(thumbnails: List<Int> = listOf(0, 1), durationUs: Long = 12_500_000L): AnalysisResult {
        val tracks = thumbnails.mapIndexed { index, handle ->
            FaceTrack(
                id = index + 1,
                personId = index + 10,
                timesUs = longArrayOf(index * 1_000L, index * 1_000L + 33_366L, index * 1_000L + 66_733L),
                boxes = FloatArray(12) { 0.1f + it * 0.01f + index * 0.05f },
                eyes = FloatArray(12) { if (it % 5 == 0) Float.NaN else 0.2f + it * 0.01f },
                sampleKinds = byteArrayOf(
                    FaceTrack.SAMPLE_DETECTED,
                    FaceTrack.SAMPLE_INTERPOLATED,
                    FaceTrack.SAMPLE_EXTRAPOLATED,
                ),
                thumbnailHandle = handle,
                meanScore = 0.9f - index * 0.1f,
            )
        }
        val people = thumbnails.mapIndexed { index, handle ->
            Person(
                id = index + 10,
                trackIds = intArrayOf(index + 1),
                thumbnailHandle = handle,
                firstSeenUs = index * 1_000L,
                visibleUs = 66_733L,
            )
        }
        return AnalysisResult(
            frameWidth = 2160,
            frameHeight = 3840,
            durationUs = durationUs,
            tracks = tracks,
            people = people,
            cutsUs = longArrayOf(4_000_000L, 9_000_000L),
            framesSeen = 749,
            framesDetected = 375,
        )
    }

    fun edit(visible: Set<Int> = setOf(2)) = EditState(
        appearance = MaskAppearance(style = MaskStyle.MOSAIC, shape = MaskShape.RECT, strength = 0.4f, scale = 1.3f),
        visibleTrackIds = visible,
        personAppearance = mapOf(10 to MaskAppearance(style = MaskStyle.SOLID, solidColor = 0xFF102030.toInt())),
        regions = listOf(
            ManualRegion(
                id = 1,
                appearance = MaskAppearance(shape = MaskShape.OVAL),
                startUs = 0L,
                endUs = 5_000_000L,
                keyframes = listOf(
                    RegionKeyframe(0L, 0.5f, 0.5f, 0.3f, 0.2f),
                    RegionKeyframe(2_000_000L, 0.6f, 0.4f, 0.25f, 0.2f),
                ),
            ),
        ),
    )

    fun sameResult(a: AnalysisResult, b: AnalysisResult): Boolean =
        a.frameWidth == b.frameWidth && a.frameHeight == b.frameHeight && a.durationUs == b.durationUs &&
            a.framesSeen == b.framesSeen && a.framesDetected == b.framesDetected &&
            a.cutsUs.contentEquals(b.cutsUs) &&
            a.tracks.size == b.tracks.size && a.tracks.indices.all { sameTrack(a.tracks[it], b.tracks[it]) } &&
            a.people.size == b.people.size && a.people.indices.all { samePerson(a.people[it], b.people[it]) }

    // Bit patterns, not ==, because unknown eye positions are NaN and NaN never equals itself.
    private fun sameFloats(a: FloatArray, b: FloatArray): Boolean =
        a.size == b.size && a.indices.all { a[it].toRawBits() == b[it].toRawBits() }

    private fun sameTrack(a: FaceTrack, b: FaceTrack): Boolean =
        a.id == b.id && a.personId == b.personId && a.timesUs.contentEquals(b.timesUs) &&
            sameFloats(a.boxes, b.boxes) && sameFloats(a.eyes, b.eyes) &&
            a.sampleKinds.contentEquals(b.sampleKinds) && a.thumbnailHandle == b.thumbnailHandle &&
            a.meanScore == b.meanScore

    private fun samePerson(a: Person, b: Person): Boolean =
        a.id == b.id && a.trackIds.contentEquals(b.trackIds) && a.thumbnailHandle == b.thumbnailHandle &&
            a.firstSeenUs == b.firstSeenUs && a.visibleUs == b.visibleUs
}

internal class FakeResultCodec : AnalysisResultCodec {
    override fun write(result: AnalysisResult, out: OutputStream) {
        DataOutputStream(out).run {
            writeInt(MAGIC)
            writeInt(result.frameWidth)
            writeInt(result.frameHeight)
            writeLong(result.durationUs)
            writeInt(result.framesSeen)
            writeInt(result.framesDetected)
            writeInt(result.cutsUs.size)
            result.cutsUs.forEach(::writeLong)
            writeInt(result.tracks.size)
            for (track in result.tracks) {
                writeInt(track.id)
                writeInt(track.personId)
                writeInt(track.sampleCount)
                track.timesUs.forEach(::writeLong)
                track.boxes.forEach(::writeFloat)
                track.eyes.forEach(::writeFloat)
                write(track.sampleKinds)
                writeInt(track.thumbnailHandle)
                writeFloat(track.meanScore)
            }
            writeInt(result.people.size)
            for (person in result.people) {
                writeInt(person.id)
                writeInt(person.trackIds.size)
                person.trackIds.forEach(::writeInt)
                writeInt(person.thumbnailHandle)
                writeLong(person.firstSeenUs)
                writeLong(person.visibleUs)
            }
            flush()
        }
    }

    override fun read(input: InputStream): AnalysisResult {
        val data = DataInputStream(input)
        if (data.readInt() != MAGIC) throw IOException("Not a fake result")
        val frameWidth = data.readInt()
        val frameHeight = data.readInt()
        val durationUs = data.readLong()
        val framesSeen = data.readInt()
        val framesDetected = data.readInt()
        val cuts = LongArray(data.count()) { data.readLong() }
        val tracks = List(data.count()) {
            val id = data.readInt()
            val personId = data.readInt()
            val samples = data.count()
            FaceTrack(
                id = id,
                personId = personId,
                timesUs = LongArray(samples) { data.readLong() },
                boxes = FloatArray(samples * 4) { data.readFloat() },
                eyes = FloatArray(samples * 4) { data.readFloat() },
                sampleKinds = ByteArray(samples).also(data::readFully),
                thumbnailHandle = data.readInt(),
                meanScore = data.readFloat(),
            )
        }
        val people = List(data.count()) {
            Person(
                id = data.readInt(),
                trackIds = IntArray(data.count()) { data.readInt() },
                thumbnailHandle = data.readInt(),
                firstSeenUs = data.readLong(),
                visibleUs = data.readLong(),
            )
        }
        return AnalysisResult(frameWidth, frameHeight, durationUs, tracks, people, cuts, framesSeen, framesDetected)
    }

    private companion object {
        const val MAGIC = 0x46524553
    }
}

internal class FakeEditCodec : EditStateCodec {
    override fun write(state: EditState, out: OutputStream) {
        DataOutputStream(out).run {
            writeInt(MAGIC)
            writeAppearance(state.appearance)
            writeInt(state.visibleTrackIds.size)
            state.visibleTrackIds.sorted().forEach(::writeInt)
            writeInt(state.personAppearance.size)
            for ((personId, appearance) in state.personAppearance.toSortedMap()) {
                writeInt(personId)
                writeAppearance(appearance)
            }
            writeInt(state.regions.size)
            for (region in state.regions) {
                writeInt(region.id)
                writeAppearance(region.appearance)
                writeLong(region.startUs)
                writeLong(region.endUs)
                writeInt(region.keyframes.size)
                for (key in region.keyframes) {
                    writeLong(key.timeUs)
                    writeFloat(key.cx)
                    writeFloat(key.cy)
                    writeFloat(key.w)
                    writeFloat(key.h)
                }
            }
            flush()
        }
    }

    override fun read(input: InputStream): EditState {
        val data = DataInputStream(input)
        if (data.readInt() != MAGIC) throw IOException("Not a fake edit")
        val appearance = data.readAppearance()
        val visible = List(data.count()) { data.readInt() }.toSet()
        val perPerson = List(data.count()) { data.readInt() to data.readAppearance() }.toMap()
        val regions = List(data.count()) {
            ManualRegion(
                id = data.readInt(),
                appearance = data.readAppearance(),
                startUs = data.readLong(),
                endUs = data.readLong(),
                keyframes = List(data.count()) {
                    RegionKeyframe(data.readLong(), data.readFloat(), data.readFloat(), data.readFloat(), data.readFloat())
                },
            )
        }
        return EditState(appearance, visible, perPerson, regions)
    }

    private fun DataOutputStream.writeAppearance(appearance: MaskAppearance) {
        writeInt(appearance.style.ordinal)
        writeInt(appearance.shape.ordinal)
        writeFloat(appearance.strength)
        writeFloat(appearance.scale)
        writeInt(appearance.solidColor)
    }

    private fun DataInputStream.readAppearance() = MaskAppearance(
        style = MaskStyle.entries[readInt()],
        shape = MaskShape.entries[readInt()],
        strength = readFloat(),
        scale = readFloat(),
        solidColor = readInt(),
    )

    private companion object {
        const val MAGIC = 0x46454454
    }
}

private fun DataInputStream.count(): Int {
    val count = readInt()
    if (count !in 0..100_000) throw IOException("Implausible count $count")
    return count
}
