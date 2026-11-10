package io.github.vikas0706.openblur.editing

import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.FaceTrack
import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.MaskStyle
import io.github.vikas0706.openblur.core.MaskTimeline
import io.github.vikas0706.openblur.core.Person
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal const val TOLERANCE = 1e-5f

/** One track sample. [eyes] is right eye x, y, left eye x, y, or null when unknown. */
internal class Sample(
    val timeUs: Long,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val eyes: FloatArray? = null,
)

internal fun track(id: Int, vararg samples: Sample, personId: Int = id): FaceTrack {
    val boxes = FloatArray(samples.size * 4)
    val eyes = FloatArray(samples.size * 4) { Float.NaN }
    samples.forEachIndexed { i, s ->
        boxes[i * 4] = s.cx
        boxes[i * 4 + 1] = s.cy
        boxes[i * 4 + 2] = s.w
        boxes[i * 4 + 3] = s.h
        s.eyes?.copyInto(eyes, i * 4)
    }
    return FaceTrack(
        id = id,
        personId = personId,
        timesUs = LongArray(samples.size) { samples[it].timeUs },
        boxes = boxes,
        eyes = eyes,
        sampleKinds = ByteArray(samples.size) { FaceTrack.SAMPLE_DETECTED },
        thumbnailHandle = -1,
        meanScore = 0.9f,
    )
}

/** A face that stays at one box from [startUs] to [endUs]. */
internal fun stillTrack(
    id: Int,
    startUs: Long,
    endUs: Long,
    cx: Float = 0.5f,
    cy: Float = 0.5f,
    w: Float = 0.2f,
    h: Float = 0.3f,
    personId: Int = id,
    eyes: FloatArray? = null,
): FaceTrack = track(id, Sample(startUs, cx, cy, w, h, eyes), Sample(endUs, cx, cy, w, h, eyes), personId = personId)

internal fun scan(
    tracks: List<FaceTrack>,
    frameWidth: Int = 1920,
    frameHeight: Int = 1080,
    durationUs: Long = 10_000_000L,
): AnalysisResult {
    val people = tracks.filter { it.sampleCount > 0 }.groupBy { it.personId }.map { (personId, own) ->
        Person(
            id = personId,
            trackIds = IntArray(own.size) { own[it].id },
            thumbnailHandle = -1,
            firstSeenUs = own.minOf { it.startUs },
            visibleUs = own.sumOf { it.endUs - it.startUs },
        )
    }.sortedBy { it.firstSeenUs }
    return AnalysisResult(
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        durationUs = durationUs,
        tracks = tracks,
        people = people,
        cutsUs = LongArray(0),
        framesSeen = 0,
        framesDetected = 0,
    )
}

/** One mask copied out of a batch, so a test can keep it after the batch is reused. */
internal data class Mask(
    val cx: Float,
    val cy: Float,
    val halfW: Float,
    val halfH: Float,
    val rotation: Float,
    val style: MaskStyle,
    val shape: MaskShape,
    val strength: Float,
    val color: Int,
    val kind: Int,
    val sourceId: Int,
    val hidden: Boolean,
) {
    /** The inside test from the MaskBatch documentation, for a normalized frame point. */
    fun contains(px: Float, py: Float, frameWidth: Int, frameHeight: Int): Boolean {
        val x = (px - cx) * frameWidth / frameHeight
        val y = py - cy
        val c = cos(-rotation)
        val s = sin(-rotation)
        val u = (x * c - y * s) / halfW
        val v = (x * s + y * c) / halfH
        return if (shape == MaskShape.OVAL) u * u + v * v <= 1f else abs(u) <= 1f && abs(v) <= 1f
    }
}

internal fun MaskBatch.masks(): List<Mask> = List(count) { i ->
    val g = i * MaskBatch.GEOMETRY_STRIDE
    Mask(
        cx = geometry[g],
        cy = geometry[g + 1],
        halfW = geometry[g + 2],
        halfH = geometry[g + 3],
        rotation = geometry[g + 4],
        style = MaskStyle.entries[style[i]],
        shape = MaskShape.entries[shape[i]],
        strength = strength[i],
        color = color[i],
        kind = kind[i],
        sourceId = sourceId[i],
        hidden = hidden[i],
    )
}

internal fun MaskTimeline.at(timeUs: Long, includeHidden: Boolean = false): List<Mask> {
    val batch = MaskBatch()
    sample(timeUs, batch, includeHidden)
    return batch.masks()
}

internal fun timeline(
    tracks: List<FaceTrack>,
    edit: EditState = EditState(),
    frameWidth: Int = 1920,
    frameHeight: Int = 1080,
    durationUs: Long = 10_000_000L,
): MaskTimeline = DefaultMaskTimelineFactory().build(scan(tracks, frameWidth, frameHeight, durationUs), edit)
