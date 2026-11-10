package io.github.vikas0706.openblur.core

import java.io.InputStream
import java.io.OutputStream

enum class MaskStyle { BLUR, MOSAIC, SOLID }

/** [EYES] covers a band across both eyes and applies to faces only; manual areas use [OVAL] or [RECT]. */
enum class MaskShape { OVAL, RECT, EYES }

data class MaskAppearance(
    val style: MaskStyle = MaskStyle.BLUR,
    val shape: MaskShape = MaskShape.OVAL,
    /** 0..1. Even 0 must leave a face unrecognizable; higher values remove more detail. */
    val strength: Float = 0.7f,
    /** Mask size relative to the default padding around a face, [MIN_SCALE]..[MAX_SCALE]. Ignored for manual areas. */
    val scale: Float = 1f,
    /** Opaque ARGB fill for [MaskStyle.SOLID]. */
    val solidColor: Int = 0xFF000000.toInt(),
) {
    companion object {
        const val MIN_SCALE = 0.8f
        const val MAX_SCALE = 1.8f
    }
}

/** Where a manual area is at one moment. Sizes are fractions of the frame width and height. */
data class RegionKeyframe(
    val timeUs: Long,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
)

/**
 * A user-drawn area active on [startUs]..[endUs]. Between keyframes the rectangle moves linearly;
 * before the first and after the last keyframe it holds that keyframe's rectangle.
 */
data class ManualRegion(
    val id: Int,
    val appearance: MaskAppearance,
    val startUs: Long,
    val endUs: Long,
    /** Never empty, sorted by time, one keyframe per time. */
    val keyframes: List<RegionKeyframe>,
)

/** Everything the user chose on top of a scan. Immutable; every edit produces a new value. */
data class EditState(
    val appearance: MaskAppearance = MaskAppearance(),
    /** Tracks left unblurred. Every other track is blurred, so a face nobody looked at fails safe. */
    val visibleTrackIds: Set<Int> = emptySet(),
    /** Per-person overrides of [appearance], keyed by [Person.id]. */
    val personAppearance: Map<Int, MaskAppearance> = emptyMap(),
    val regions: List<ManualRegion> = emptyList(),
) {
    fun isBlurred(trackId: Int): Boolean = trackId !in visibleTrackIds

    fun appearanceFor(personId: Int): MaskAppearance = personAppearance[personId] ?: appearance
}

interface EditStateCodec {
    fun write(state: EditState, out: OutputStream)

    /** Throws [java.io.IOException] when the data is truncated, corrupt or from an unknown version. */
    fun read(input: InputStream): EditState
}

/**
 * Masks active at one instant, in struct-of-arrays form so the render thread can reuse one instance.
 *
 * Geometry is five floats per mask: cx, cy, halfW, halfH, rotation. The centre is normalized to the frame
 * (x right, y down). Half sizes are fractions of the frame HEIGHT measured along the mask's own axes, and
 * rotation is in radians, clockwise on screen, in aspect-correct space. A frame point p is inside the mask's
 * unit shape when, with q = ((p.x - cx) * frameWidth / frameHeight, p.y - cy) rotated by -rotation,
 * (q.x / halfW, q.y / halfH) is inside the unit circle ([MaskShape.OVAL]) or the unit square (other shapes).
 */
class MaskBatch(initialCapacity: Int = 32) {
    var count: Int = 0
        private set
    var geometry: FloatArray = FloatArray(initialCapacity * GEOMETRY_STRIDE)
        private set
    /** [MaskStyle] ordinal per mask. */
    var style: IntArray = IntArray(initialCapacity)
        private set
    /** [MaskShape] ordinal per mask. */
    var shape: IntArray = IntArray(initialCapacity)
        private set
    var strength: FloatArray = FloatArray(initialCapacity)
        private set
    /** ARGB per mask, meaningful for [MaskStyle.SOLID]. */
    var color: IntArray = IntArray(initialCapacity)
        private set
    /** [KIND_FACE] or [KIND_REGION] per mask. */
    var kind: IntArray = IntArray(initialCapacity)
        private set
    /** [FaceTrack.id] for faces, [ManualRegion.id] for areas. */
    var sourceId: IntArray = IntArray(initialCapacity)
        private set
    /** True for a face the user left visible. Only present when hidden faces were asked for; never drawn. */
    var hidden: BooleanArray = BooleanArray(initialCapacity)
        private set

    fun clear() {
        count = 0
    }

    /** Appends one mask, growing the arrays when full so no mask is ever dropped. Returns its index. */
    fun add(
        cx: Float,
        cy: Float,
        halfW: Float,
        halfH: Float,
        rotation: Float,
        style: MaskStyle,
        shape: MaskShape,
        strength: Float,
        color: Int,
        kind: Int,
        sourceId: Int,
        hidden: Boolean,
    ): Int {
        if (count == this.style.size) grow()
        val i = count++
        val g = i * GEOMETRY_STRIDE
        geometry[g] = cx
        geometry[g + 1] = cy
        geometry[g + 2] = halfW
        geometry[g + 3] = halfH
        geometry[g + 4] = rotation
        this.style[i] = style.ordinal
        this.shape[i] = shape.ordinal
        this.strength[i] = strength
        this.color[i] = color
        this.kind[i] = kind
        this.sourceId[i] = sourceId
        this.hidden[i] = hidden
        return i
    }

    private fun grow() {
        val n = maxOf(8, style.size * 2)
        geometry = geometry.copyOf(n * GEOMETRY_STRIDE)
        style = style.copyOf(n)
        shape = shape.copyOf(n)
        strength = strength.copyOf(n)
        color = color.copyOf(n)
        kind = kind.copyOf(n)
        sourceId = sourceId.copyOf(n)
        hidden = hidden.copyOf(n)
    }

    companion object {
        const val GEOMETRY_STRIDE = 5
        const val KIND_FACE = 0
        const val KIND_REGION = 1
    }
}

/** An immutable snapshot of a scan plus the user's choices. Safe to read from any thread. */
interface MaskTimeline {
    val durationUs: Long

    /**
     * Clears [out] and fills it with the masks at [timeUs] (source presentation time), faces first, then areas.
     * With [includeHidden] false only masks that must be drawn are returned; with true, faces the user left
     * visible are added with [MaskBatch.hidden] set, for hit-testing and outlines. Allocates only when [out] grows.
     */
    fun sample(timeUs: Long, out: MaskBatch, includeHidden: Boolean = false)

    /** True when no mask is drawn at any time, so an export would only re-encode. */
    val isEmpty: Boolean
}

interface MaskTimelineFactory {
    fun build(result: AnalysisResult, edit: EditState): MaskTimeline

    /** The rectangle of [region] at [timeUs], whether or not the area is active then; for editing handles. */
    fun regionAt(region: ManualRegion, timeUs: Long): RegionKeyframe
}
