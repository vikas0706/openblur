package io.github.vikas0706.openblur.editing

import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.MaskAppearance
import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.MaskStyle
import io.github.vikas0706.openblur.core.MaskTimeline
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Every field is written once in the constructor and never changed, which is what makes [sample] safe
 * from several threads. Track sample arrays are shared with the scan result, not copied.
 */
internal class SnapshotMaskTimeline(result: AnalysisResult, edit: EditState) : MaskTimeline {
    override val durationUs: Long = result.durationUs
    override val isEmpty: Boolean

    /** Frame width over height: turns a fraction of the width into a fraction of the height. */
    private val aspect: Float =
        if (result.frameWidth > 0 && result.frameHeight > 0) result.frameWidth.toFloat() / result.frameHeight else 1f

    /** In the order of [AnalysisResult.tracks], so overlapping masks keep the same draw order on every frame. */
    private val faces: Array<Face>
    private val faceStartsUs: LongArray
    private val faceEndsUs: LongArray
    private val faceIndex: TimeBuckets
    private val regions: Array<Region>

    init {
        val keptFaces = ArrayList<Face>(result.tracks.size)
        for (track in result.tracks) {
            val sampleCount = minOf(track.timesUs.size, track.boxes.size / 4)
            // A track with no samples, or one that ends before it starts, has no moment at which it could be drawn.
            if (sampleCount == 0 || track.timesUs[sampleCount - 1] < track.timesUs[0]) continue
            val look = edit.appearanceFor(track.personId)
            keptFaces += Face(
                id = track.id,
                timesUs = track.timesUs,
                boxes = track.boxes,
                eyes = track.eyes.takeIf { it.size >= sampleCount * 4 },
                sampleCount = sampleCount,
                hidden = track.id in edit.visibleTrackIds,
                style = look.style,
                shape = look.shape,
                strength = safeStrength(look.strength),
                scale = safeScale(look.scale),
                color = look.solidColor,
            )
        }
        faces = keptFaces.toTypedArray()
        faceStartsUs = LongArray(faces.size) { faces[it].timesUs[0] }
        faceEndsUs = LongArray(faces.size) { faces[it].timesUs[faces[it].sampleCount - 1] }
        faceIndex = TimeBuckets(faceStartsUs, faceEndsUs)

        val keptRegions = ArrayList<Region>(edit.regions.size)
        for (region in edit.regions) {
            val frames = region.keyframes
            if (frames.isEmpty()) continue
            val rects = FloatArray(frames.size * 4)
            for (i in frames.indices) {
                val frame = frames[i]
                rects[i * 4] = frame.cx
                rects[i * 4 + 1] = frame.cy
                rects[i * 4 + 2] = frame.w
                rects[i * 4 + 3] = frame.h
            }
            keptRegions += Region(
                id = region.id,
                startUs = region.startUs,
                endUs = region.endUs,
                timesUs = LongArray(frames.size) { frames[it].timeUs },
                rects = rects,
                style = region.appearance.style,
                // The eye band needs a face; an area that asks for it is drawn as the rectangle EditOps would store.
                shape = if (region.appearance.shape == MaskShape.EYES) MaskShape.RECT else region.appearance.shape,
                strength = safeStrength(region.appearance.strength),
                color = region.appearance.solidColor,
            )
        }
        regions = keptRegions.toTypedArray()

        isEmpty = faces.none { !it.hidden } && regions.none { it.startUs <= it.endUs }
    }

    override fun sample(timeUs: Long, out: MaskBatch, includeHidden: Boolean) {
        out.clear()
        val index = faceIndex
        if (timeUs >= index.firstUs && timeUs <= index.lastUs) {
            val bucket = index.bucketOf(timeUs)
            val items = index.items
            var at = index.offsets[bucket]
            val end = index.offsets[bucket + 1]
            while (at < end) {
                val i = items[at++]
                if (timeUs < faceStartsUs[i] || timeUs > faceEndsUs[i]) continue
                val face = faces[i]
                if (face.hidden && !includeHidden) continue
                addFace(face, timeUs, out)
            }
        }
        for (i in regions.indices) {
            val region = regions[i]
            if (timeUs >= region.startUs && timeUs <= region.endUs) addRegion(region, timeUs, out)
        }
    }

    /** How many tracks [sample] compares with [timeUs] before it knows which are active. For tests. */
    internal fun candidateCount(timeUs: Long): Int {
        val index = faceIndex
        if (timeUs < index.firstUs || timeUs > index.lastUs) return 0
        val bucket = index.bucketOf(timeUs)
        return index.offsets[bucket + 1] - index.offsets[bucket]
    }

    private fun addFace(face: Face, timeUs: Long, out: MaskBatch) {
        val times = face.timesUs
        var lo = 0
        var hi = face.sampleCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (times[mid] <= timeUs) lo = mid else hi = mid - 1
        }

        val boxes = face.boxes
        val eyes = face.eyes
        val a = lo * 4
        var cx = boxes[a]
        var cy = boxes[a + 1]
        var w = boxes[a + 2]
        var h = boxes[a + 3]
        var rightX = Float.NaN
        var rightY = Float.NaN
        var leftX = Float.NaN
        var leftY = Float.NaN
        if (eyes != null) {
            rightX = eyes[a]
            rightY = eyes[a + 1]
            leftX = eyes[a + 2]
            leftY = eyes[a + 3]
        }
        if (lo < face.sampleCount - 1) {
            val into = timeUs - times[lo]
            val span = times[lo + 1] - times[lo]
            // Exactly on a sample the next one is left out, so its unknown eyes cannot erase known ones.
            if (into > 0 && span > 0) {
                val f = into.toFloat() / span.toFloat()
                val b = a + 4
                cx += (boxes[b] - cx) * f
                cy += (boxes[b + 1] - cy) * f
                w += (boxes[b + 2] - w) * f
                h += (boxes[b + 3] - h) * f
                if (eyes != null) {
                    rightX += (eyes[b] - rightX) * f
                    rightY += (eyes[b + 1] - rightY) * f
                    leftX += (eyes[b + 2] - leftX) * f
                    leftY += (eyes[b + 3] - leftY) * f
                }
            }
        }

        val scale = face.scale
        val widthInHeights = w * aspect
        var maskCx = cx
        var maskCy = cy - FACE_LIFT * h
        var halfW = 0f
        var halfH = 0f
        var rotation = 0f
        when (face.shape) {
            MaskShape.OVAL -> {
                halfW = OVAL_HALF_W * widthInHeights * scale
                halfH = OVAL_HALF_H * h * scale
            }
            MaskShape.RECT -> {
                halfW = RECT_HALF_W * widthInHeights * scale
                halfH = RECT_HALF_H * h * scale
            }
            MaskShape.EYES -> {
                val dx = (leftX - rightX) * aspect
                val dy = leftY - rightY
                val eyeDistance = sqrt(dx * dx + dy * dy)
                // Unknown eyes are NaN and fail this test. So do eyes so close together, as on a head turned
                // far aside, that a band sized from them would be too thin to hide them.
                if (eyeDistance > MIN_EYE_DISTANCE * minOf(widthInHeights, h)) {
                    maskCx = (rightX + leftX) * 0.5f
                    maskCy = (rightY + leftY) * 0.5f
                    halfW = EYE_BAND_HALF_W * eyeDistance * scale
                    halfH = EYE_BAND_HALF_H * eyeDistance * scale
                    rotation = atan2(dy, dx)
                } else {
                    maskCy = cy + (BOX_BAND_CENTRE - 0.5f) * h
                    halfW = BOX_BAND_HALF_W * widthInHeights * scale
                    halfH = BOX_BAND_HALF_H * h * scale
                }
            }
        }
        out.add(
            maskCx, maskCy, halfW, halfH, rotation,
            face.style, face.shape, face.strength, face.color,
            MaskBatch.KIND_FACE, face.id, face.hidden,
        )
    }

    /** Same arithmetic as EditOps.regionAt, on flat arrays so nothing is allocated per frame. */
    private fun addRegion(region: Region, timeUs: Long, out: MaskBatch) {
        val times = region.timesUs
        val rects = region.rects
        val last = times.size - 1
        val cx: Float
        val cy: Float
        val w: Float
        val h: Float
        if (timeUs <= times[0] || timeUs >= times[last]) {
            val k = if (timeUs <= times[0]) 0 else last * 4
            cx = rects[k]
            cy = rects[k + 1]
            w = rects[k + 2]
            h = rects[k + 3]
        } else {
            var lo = 1
            var hi = last
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (times[mid] < timeUs) lo = mid + 1 else hi = mid
            }
            val a = (lo - 1) * 4
            val b = lo * 4
            val span = times[lo] - times[lo - 1]
            val t = if (span > 0) (timeUs - times[lo - 1]).toFloat() / span.toFloat() else 1f
            cx = rects[a] + (rects[b] - rects[a]) * t
            cy = rects[a + 1] + (rects[b + 1] - rects[a + 1]) * t
            w = rects[a + 2] + (rects[b + 2] - rects[a + 2]) * t
            h = rects[a + 3] + (rects[b + 3] - rects[a + 3]) * t
        }
        out.add(
            cx, cy, w * 0.5f * aspect, h * 0.5f, 0f,
            region.style, region.shape, region.strength, region.color,
            MaskBatch.KIND_REGION, region.id, false,
        )
    }

    private class Face(
        val id: Int,
        val timesUs: LongArray,
        /** Four floats per sample: cx, cy, w, h. */
        val boxes: FloatArray,
        /** Four floats per sample, or null when the track carries no eye positions. */
        val eyes: FloatArray?,
        val sampleCount: Int,
        val hidden: Boolean,
        val style: MaskStyle,
        val shape: MaskShape,
        val strength: Float,
        val scale: Float,
        val color: Int,
    )

    private class Region(
        val id: Int,
        val startUs: Long,
        val endUs: Long,
        val timesUs: LongArray,
        /** Four floats per keyframe: cx, cy, w, h. */
        val rects: FloatArray,
        val style: MaskStyle,
        val shape: MaskShape,
        val strength: Float,
        val color: Int,
    )

    private companion object {
        /** Face masks sit this fraction of the box height above the box centre, towards the hair. */
        const val FACE_LIFT = 0.05f

        const val OVAL_HALF_W = 0.70f
        const val OVAL_HALF_H = 0.80f
        const val RECT_HALF_W = 0.62f
        const val RECT_HALF_H = 0.72f

        /** Multiples of the distance between the eyes. */
        const val EYE_BAND_HALF_W = 1.35f
        const val EYE_BAND_HALF_H = 0.45f

        /** The band used without eye positions: its centre from the top of the box, and its half sizes, in box sizes. */
        const val BOX_BAND_CENTRE = 0.40f
        const val BOX_BAND_HALF_W = 0.60f
        const val BOX_BAND_HALF_H = 0.16f

        /** Of the box's short side. Eyes facing the camera are about 0.4 apart; below half of that the box band is used. */
        const val MIN_EYE_DISTANCE = 0.2f

        // A NaN strength or scale would give the renderer a mask it cannot draw, which would leave the face visible.
        fun safeStrength(value: Float): Float = if (value.isNaN()) 1f else value.coerceIn(0f, 1f)

        fun safeScale(value: Float): Float =
            if (value.isNaN()) 1f else value.coerceIn(MaskAppearance.MIN_SCALE, MaskAppearance.MAX_SCALE)
    }
}
