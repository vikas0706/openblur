package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.Detection
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min

/** One detector run copied into flat arrays, without the detections nothing can be done with. */
internal class DetectionBatch(private val frameWidth: Int, private val frameHeight: Int) {
    var count = 0
        private set

    /** cx, cy, w, h per detection, normalized, as the detector reported them. */
    var box = FloatArray(INITIAL * 4)
        private set
    var score = FloatArray(INITIAL)
        private set

    /** Ten normalized coordinates per detection, meaningful only where [hasLandmarks] is set. */
    var landmarks = FloatArray(INITIAL * 10)
        private set
    var hasLandmarks = BooleanArray(INITIAL)
        private set

    /** How good a picture of the face this detection would make, 0..1. Zero for weak detections. */
    var quality = FloatArray(INITIAL)
        private set
    var eyeDistancePx = FloatArray(INITIAL)
        private set

    fun load(detections: List<Detection>) {
        count = 0
        val total = detections.size
        var i = 0
        while (i < total && count < Tuning.MAX_DETECTIONS) {
            val d = detections[i++]
            if (!usable(d)) continue
            if (count == score.size) grow()
            val at = count++
            box[at * 4] = d.cx
            box[at * 4 + 1] = d.cy
            box[at * 4 + 2] = d.w
            box[at * 4 + 3] = d.h
            val s = min(d.score, 1f)
            score[at] = s
            val known = copyLandmarks(d.landmarks, at)
            hasLandmarks[at] = known
            eyeDistancePx[at] = 0f
            quality[at] = 0f
            if (s >= Tuning.STRONG_SCORE) rate(at, s, known)
        }
    }

    /** True when the detection is sharp and large enough for its aligned crop to be worth embedding. */
    fun embeddable(index: Int): Boolean =
        hasLandmarks[index] && eyeDistancePx[index] >= Tuning.MIN_EMBED_EYE_DISTANCE_PX && quality[index] > 0f

    private fun usable(d: Detection): Boolean {
        val w = d.w
        val h = d.h
        // Written so that NaN fails every test.
        if (!(w > 0f && w < Float.MAX_VALUE && h > 0f && h < Float.MAX_VALUE)) return false
        if (!(d.score >= 0f && d.score < Float.MAX_VALUE)) return false
        if (!d.cx.isFinite() || !d.cy.isFinite()) return false
        return d.cx + w * 0.5f > 0f && d.cx - w * 0.5f < 1f && d.cy + h * 0.5f > 0f && d.cy - h * 0.5f < 1f
    }

    private fun copyLandmarks(source: FloatArray, at: Int): Boolean {
        if (source.size < 10) return false
        val to = at * 10
        for (k in 0 until 10) {
            val value = source[k]
            if (!value.isFinite()) return false
            landmarks[to + k] = value
        }
        return true
    }

    private fun rate(at: Int, s: Float, known: Boolean) {
        if (!known) {
            // Without landmarks the box stands in for the eyes and the pose counts as the worst case.
            val side = min(box[at * 4 + 2] * frameWidth, box[at * 4 + 3] * frameHeight)
            quality[at] = s * min(1f, 0.4f * side / Tuning.FULL_DETAIL_EYE_DISTANCE_PX) * MIN_FRONTAL
            return
        }
        val l = at * 10
        val rex = landmarks[l] * frameWidth
        val rey = landmarks[l + 1] * frameHeight
        val lex = landmarks[l + 2] * frameWidth
        val ley = landmarks[l + 3] * frameHeight
        val nx = landmarks[l + 4] * frameWidth
        val ny = landmarks[l + 5] * frameHeight
        val mx = (landmarks[l + 6] + landmarks[l + 8]) * 0.5f * frameWidth
        val my = (landmarks[l + 7] + landmarks[l + 9]) * 0.5f * frameHeight
        val eyeDistance = hypot(lex - rex, ley - rey)
        eyeDistancePx[at] = eyeDistance
        val ex = (rex + lex) * 0.5f
        val ey = (rey + ley) * 0.5f
        val ax = mx - ex
        val ay = my - ey
        val axis = hypot(ax, ay)
        var frontal = MIN_FRONTAL
        if (eyeDistance > 1e-3f && axis > 1e-3f) {
            // Turning the head moves the nose off the line from between the eyes to the mouth
            // and changes how long that line is against the eye distance.
            val noseOff = abs((nx - ex) * ay - (ny - ey) * ax) / (axis * eyeDistance)
            val yaw = (1f - noseOff / NOSE_OFF_LIMIT).coerceIn(0f, 1f)
            val stretch = abs(ln(axis / eyeDistance / FRONTAL_AXIS_RATIO))
            val shape = (1f - stretch / STRETCH_LIMIT).coerceIn(0f, 1f)
            frontal = MIN_FRONTAL + (1f - MIN_FRONTAL) * yaw * shape
        }
        quality[at] = s * min(1f, eyeDistance / Tuning.FULL_DETAIL_EYE_DISTANCE_PX) * frontal
    }

    private fun grow() {
        val n = score.size * 2
        box = box.copyOf(n * 4)
        score = score.copyOf(n)
        landmarks = landmarks.copyOf(n * 10)
        hasLandmarks = hasLandmarks.copyOf(n)
        quality = quality.copyOf(n)
        eyeDistancePx = eyeDistancePx.copyOf(n)
    }

    private companion object {
        const val INITIAL = 32

        /** Kept above zero so profile faces still rank by score and size. */
        const val MIN_FRONTAL = 0.1f

        /** Eye-to-mouth distance over eye distance for a frontal face (ArcFace template: 40.7 / 35.2). */
        const val FRONTAL_AXIS_RATIO = 1.15f
        const val NOSE_OFF_LIMIT = 0.5f
        const val STRETCH_LIMIT = 0.6f
    }
}
