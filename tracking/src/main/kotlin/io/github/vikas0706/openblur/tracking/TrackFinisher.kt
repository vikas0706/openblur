package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.FaceTrack
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** The samples of one finished track, before it has an id or a person. */
internal class BuiltTrack(
    val serial: Int,
    val timesUs: LongArray,
    val boxes: FloatArray,
    val eyes: FloatArray,
    val kinds: ByteArray,
    val thumbHandle: Int,
    val thumbQuality: Float,
    val meanScore: Float,
    /** First and last detection, before pre-roll and post-roll. Two tracks that share a moment here are two people. */
    val detectedStartUs: Long,
    val detectedEndUs: Long,
    val centroid: FloatArray?,
    val centroidWeight: Float,
)

/**
 * Turns a track's raw detections into its stored samples: smoothed boxes that still cover every detection,
 * samples for the runs that missed the face, and an extrapolated lead-in and lead-out. Linear in the sample count.
 */
internal class TrackFinisher(
    private val runPtsUs: LongArray,
    private val cutPtsUs: LongArray,
    private val cutEarliestUs: LongArray,
    private val cutCount: Int,
    private val rangeStartUs: Long,
    private val rangeEndUs: Long,
) {
    private val decayTable = FloatArray(DECAY_STEPS + 1) { i ->
        if (i == DECAY_STEPS) 0f else exp(-i.toDouble() * DECAY_STEP_US / Tuning.CENTER_SMOOTH_TAU_US).toFloat()
    }

    private var capacity = 0
    private var t = LongArray(0)

    /** Weight carried from the previous sample to this one, and the time between them in units of the time constant. */
    private var decay = FloatArray(0)
    private var step = DoubleArray(0)

    // Sums of weight, weight * offset and weight * offset squared around each sample: over all samples,
    // and over the samples whose eyes are known when some are not.
    private var sum0 = DoubleArray(0)
    private var sum1 = DoubleArray(0)
    private var sum2 = DoubleArray(0)
    private var eyeSum0 = DoubleArray(0)
    private var eyeSum1 = DoubleArray(0)
    private var eyeSum2 = DoubleArray(0)
    private var forward0 = DoubleArray(0)
    private var forward1 = DoubleArray(0)

    // Edges of the detection clipped to the frame; overwritten with the final edges once those are known.
    private var left = FloatArray(0)
    private var top = FloatArray(0)
    private var right = FloatArray(0)
    private var bottom = FloatArray(0)
    private var smoothX = FloatArray(0)
    private var smoothY = FloatArray(0)
    private var smoothEyes = FloatArray(0)

    // Half size each sample needs to cover its detection from the smoothed center, later the smoothed half size.
    private var halfW = FloatArray(0)
    private var halfH = FloatArray(0)
    private var peak = FloatArray(0)
    private var prefix = DoubleArray(0)
    private var deque = IntArray(0)

    // What one block of a pass hands to the next.
    private var carry0 = 0.0
    private var carry1 = 0.0
    private var carry2 = 0.0
    private var dequeHead = 0
    private var dequeTail = 0
    private var dequeNext = 0
    private var windowLow = 0
    private var windowHigh = 0
    private var outAt = 0
    private var eyesMissing = false

    /** Sizes the working arrays for the longest track, so building many tracks allocates only their results. */
    fun reserve(samples: Int) {
        if (samples <= capacity) return
        capacity = samples
        t = LongArray(samples)
        decay = FloatArray(samples)
        step = DoubleArray(samples)
        sum0 = DoubleArray(samples)
        sum1 = DoubleArray(samples)
        sum2 = DoubleArray(samples)
        forward0 = DoubleArray(samples)
        forward1 = DoubleArray(samples)
        left = FloatArray(samples)
        top = FloatArray(samples)
        right = FloatArray(samples)
        bottom = FloatArray(samples)
        smoothX = FloatArray(samples)
        smoothY = FloatArray(samples)
        smoothEyes = FloatArray(samples * 4)
        halfW = FloatArray(samples)
        halfH = FloatArray(samples)
        peak = FloatArray(samples)
        prefix = DoubleArray(samples + 1)
        deque = IntArray(samples)
    }

    fun build(track: ClosedTrack): BuiltTrack {
        val runs = track.runs!!
        val boxes = track.boxes!!
        val rawEyes = track.eyes!!
        val n = track.size
        reserve(n)
        eyesMissing = false
        forward(n) { from, to -> prepare(from, to, runs, boxes, rawEyes) }

        weightSums(n, null, sum0, sum1, sum2)
        fitLine(n, boxes, 0, sum0, sum1, sum2, smoothX, 0, 1)
        fitLine(n, boxes, 1, sum0, sum1, sum2, smoothY, 0, 1)
        var e0 = sum0
        var e1 = sum1
        var e2 = sum2
        if (eyesMissing) {
            if (eyeSum0.size < n) {
                eyeSum0 = DoubleArray(capacity)
                eyeSum1 = DoubleArray(capacity)
                eyeSum2 = DoubleArray(capacity)
            }
            e0 = eyeSum0
            e1 = eyeSum1
            e2 = eyeSum2
            weightSums(n, rawEyes, e0, e1, e2)
        }
        for (e in 0 until 4) fitLine(n, rawEyes, e, e0, e1, e2, smoothEyes, e, 4)

        forward(n) { from, to -> neededSizes(from, to) }
        steadySize(n, halfW)
        steadySize(n, halfH)
        forward(n) { from, to -> finalEdges(from, to) }

        val firstUs = t[0]
        val lastUs = t[n - 1]
        val lower = if (track.shot == 0) rangeStartUs else max(rangeStartUs, cutEarliestUs[track.shot - 1])
        // A shot ends one microsecond before the frame that showed the cut.
        val upper = if (track.shot == cutCount) rangeEndUs else min(rangeEndUs, cutPtsUs[track.shot] - 1)
        val preUs = max(firstUs - Tuning.ROLL_US, lower)
        var postUs = min(lastUs + Tuning.ROLL_US, upper)
        val hasPre = preUs < firstUs
        var hasPost = postUs > lastUs
        if (n == 1 && !hasPre && !hasPost) {
            // A track needs two samples; with no room on either side the second one goes just after the first.
            postUs = lastUs + 1
            hasPost = true
        }
        val missed = runs[n - 1] - runs[0] + 1 - n
        val total = n + missed + (if (hasPre) 1 else 0) + (if (hasPost) 1 else 0)

        val outTimes = LongArray(total)
        val outBoxes = FloatArray(total * 4)
        val outEyes = FloatArray(total * 4)
        val outKinds = ByteArray(total)
        outAt = 0
        if (hasPre) {
            extrapolate(0, min(ROLL_VELOCITY_SPAN, n - 1), preUs, outBoxes, outEyes, 0)
            outTimes[0] = preUs
            outKinds[0] = FaceTrack.SAMPLE_EXTRAPOLATED
            outAt = 1
        }
        forward(n) { from, to -> emit(from, to, n, runs, outTimes, outBoxes, outEyes, outKinds) }
        if (hasPost) {
            extrapolate(n - 1, max(0, n - 1 - ROLL_VELOCITY_SPAN), postUs, outBoxes, outEyes, outAt)
            outTimes[outAt] = postUs
            outKinds[outAt] = FaceTrack.SAMPLE_EXTRAPOLATED
        }

        track.runs = null
        track.boxes = null
        track.eyes = null
        return BuiltTrack(
            serial = track.serial,
            timesUs = outTimes,
            boxes = outBoxes,
            eyes = outEyes,
            kinds = outKinds,
            thumbHandle = track.thumbHandle,
            thumbQuality = track.thumbQuality,
            meanScore = track.meanScore,
            detectedStartUs = firstUs,
            detectedEndUs = lastUs,
            centroid = track.centroid,
            centroidWeight = track.centroidWeight,
        )
    }

    // Every pass runs in short blocks through a small method of its own. A scan ends with one call, and a
    // runtime compiles a method that is called often far sooner than a single long loop it enters once.
    private inline fun forward(n: Int, pass: (from: Int, to: Int) -> Unit) {
        var from = 0
        while (from < n) {
            val to = min(n, from + BLOCK)
            pass(from, to)
            from = to
        }
    }

    private inline fun backward(n: Int, pass: (from: Int, to: Int) -> Unit) {
        var to = n
        while (to > 0) {
            val from = max(0, to - BLOCK)
            pass(from, to)
            to = from
        }
    }

    private fun prepare(from: Int, to: Int, runs: IntArray, boxes: FloatArray, rawEyes: FloatArray) {
        for (i in from until to) {
            t[i] = runPtsUs[runs[i]]
            if (i == 0) {
                decay[0] = 0f
                step[0] = 0.0
            } else {
                val gapUs = t[i] - t[i - 1]
                decay[i] = decayTable[min(gapUs / DECAY_STEP_US, DECAY_STEPS.toLong()).toInt()]
                step[i] = gapUs.toDouble() / Tuning.CENTER_SMOOTH_TAU_US
            }
            clipAxis(boxes[i * 4], boxes[i * 4 + 2], left, right, i)
            clipAxis(boxes[i * 4 + 1], boxes[i * 4 + 3], top, bottom, i)
            if (rawEyes[i * 4].isNaN()) eyesMissing = true
        }
    }

    private fun clipAxis(center: Float, size: Float, low: FloatArray, high: FloatArray, i: Int) {
        var lo = (center - size * 0.5f).coerceIn(0f, 1f)
        var hi = (center + size * 0.5f).coerceIn(0f, 1f)
        if (hi - lo < MIN_SIZE) {
            val mid = ((lo + hi) * 0.5f).coerceIn(MIN_SIZE * 0.5f, 1f - MIN_SIZE * 0.5f)
            lo = mid - MIN_SIZE * 0.5f
            hi = mid + MIN_SIZE * 0.5f
        }
        low[i] = lo
        high[i] = hi
    }

    /**
     * For every sample, the sums over all samples of w, w * u and w * u * u, where u is the time offset from
     * that sample and w falls off exponentially with it. With [eyes] given, samples with unknown eyes are left out.
     */
    private fun weightSums(n: Int, eyes: FloatArray?, out0: DoubleArray, out1: DoubleArray, out2: DoubleArray) {
        carry0 = 0.0
        carry1 = 0.0
        carry2 = 0.0
        forward(n) { from, to -> sumsUpTo(from, to, eyes, out0, out1, out2) }
        carry0 = 0.0
        carry1 = 0.0
        carry2 = 0.0
        backward(n) { from, to -> sumsBeyond(from, to, eyes, out0, out1, out2) }
    }

    private fun sumsUpTo(from: Int, to: Int, eyes: FloatArray?, out0: DoubleArray, out1: DoubleArray, out2: DoubleArray) {
        var p0 = carry0
        var p1 = carry1
        var p2 = carry2
        for (i in from until to) {
            // Moving the origin forward by h turns every offset u into u - h.
            val d = decay[i].toDouble()
            val h = step[i]
            p2 = d * (p2 - 2.0 * h * p1 + h * h * p0)
            p1 = d * (p1 - h * p0)
            p0 = d * p0
            if (eyes == null || !eyes[i * 4].isNaN()) p0 += 1.0
            out0[i] = p0
            out1[i] = p1
            out2[i] = p2
        }
        carry0 = p0
        carry1 = p1
        carry2 = p2
    }

    private fun sumsBeyond(from: Int, to: Int, eyes: FloatArray?, out0: DoubleArray, out1: DoubleArray, out2: DoubleArray) {
        var q0 = carry0
        var q1 = carry1
        var q2 = carry2
        for (i in to - 1 downTo from) {
            out0[i] += q0
            out1[i] += q1
            out2[i] += q2
            // Moving the origin back by h turns every offset u into u + h; this sample joins at offset zero.
            val d = decay[i].toDouble()
            val h = step[i]
            val c0 = if (eyes == null || !eyes[i * 4].isNaN()) q0 + 1.0 else q0
            q2 = d * (q2 + 2.0 * h * q1 + h * h * c0)
            q1 = d * (q1 + h * c0)
            q0 = d * c0
        }
        carry0 = q0
        carry1 = q1
        carry2 = q2
    }

    /**
     * Smooths the value at [sourceAt] of every four in [source] with a line fitted through its neighbors in time.
     * A weighted mean lags where the neighbors are lopsided, as at a track's ends; the line does not. NaN stays NaN.
     */
    private fun fitLine(
        n: Int,
        source: FloatArray,
        sourceAt: Int,
        s0: DoubleArray,
        s1: DoubleArray,
        s2: DoubleArray,
        out: FloatArray,
        outOffset: Int,
        outStride: Int,
    ) {
        carry0 = 0.0
        carry1 = 0.0
        forward(n) { from, to -> lineUpTo(from, to, source, sourceAt) }
        carry0 = 0.0
        carry1 = 0.0
        backward(n) { from, to -> lineThrough(from, to, source, sourceAt, s0, s1, s2, out, outOffset, outStride) }
    }

    private fun lineUpTo(from: Int, to: Int, source: FloatArray, sourceAt: Int) {
        var p0 = carry0
        var p1 = carry1
        for (i in from until to) {
            val d = decay[i].toDouble()
            p1 = d * (p1 - step[i] * p0)
            p0 = d * p0
            val value = source[i * 4 + sourceAt]
            if (!value.isNaN()) p0 += value
            forward0[i] = p0
            forward1[i] = p1
        }
        carry0 = p0
        carry1 = p1
    }

    private fun lineThrough(
        from: Int,
        to: Int,
        source: FloatArray,
        sourceAt: Int,
        s0: DoubleArray,
        s1: DoubleArray,
        s2: DoubleArray,
        out: FloatArray,
        outOffset: Int,
        outStride: Int,
    ) {
        var q0 = carry0
        var q1 = carry1
        for (i in to - 1 downTo from) {
            val value = source[i * 4 + sourceAt]
            val known = !value.isNaN()
            if (known) {
                val v0 = forward0[i] + q0
                val v1 = forward1[i] + q1
                val spread = s0[i] * s2[i] - s1[i] * s1[i]
                // Without a spread in time there is no slope to fit, and the weighted mean is the answer.
                out[i * outStride + outOffset] = if (spread > MIN_SPREAD * s0[i] * s0[i]) {
                    ((s2[i] * v0 - s1[i] * v1) / spread).toFloat()
                } else {
                    (v0 / s0[i]).toFloat()
                }
            } else {
                out[i * outStride + outOffset] = Float.NaN
            }
            val d = decay[i].toDouble()
            val h = step[i]
            val c0 = if (known) q0 + value else q0
            q1 = d * (q1 + h * c0)
            q0 = d * c0
        }
        carry0 = q0
        carry1 = q1
    }

    private fun neededSizes(from: Int, to: Int) {
        for (i in from until to) {
            halfW[i] = max(smoothX[i] - left[i], right[i] - smoothX[i])
            halfH[i] = max(smoothY[i] - top[i], bottom[i] - smoothY[i])
        }
    }

    /**
     * Replaces each needed half size by the largest one within the smoothing radius, then by the mean of those
     * over the same radius. Every value averaged is at least what the sample needs, so the result is too.
     */
    private fun steadySize(n: Int, size: FloatArray) {
        dequeHead = 0
        dequeTail = 0
        dequeNext = 0
        forward(n) { from, to -> peaks(from, to, n, size) }
        prefix[0] = 0.0
        forward(n) { from, to -> prefixSums(from, to) }
        windowLow = 0
        windowHigh = 0
        forward(n) { from, to -> windowMeans(from, to, n, size) }
    }

    private fun peaks(from: Int, to: Int, n: Int, size: FloatArray) {
        val radius = Tuning.SIZE_SMOOTH_RADIUS_US
        var head = dequeHead
        var tail = dequeTail
        var next = dequeNext
        for (i in from until to) {
            val reach = t[i] + radius
            while (next < n && t[next] <= reach) {
                val value = size[next]
                while (tail > head && size[deque[tail - 1]] <= value) tail--
                deque[tail++] = next++
            }
            val floor = t[i] - radius
            while (t[deque[head]] < floor) head++
            peak[i] = size[deque[head]]
        }
        dequeHead = head
        dequeTail = tail
        dequeNext = next
    }

    private fun prefixSums(from: Int, to: Int) {
        var sum = prefix[from]
        for (i in from until to) {
            sum += peak[i]
            prefix[i + 1] = sum
        }
    }

    private fun windowMeans(from: Int, to: Int, n: Int, size: FloatArray) {
        val radius = Tuning.SIZE_SMOOTH_RADIUS_US
        var lo = windowLow
        var hi = windowHigh
        for (i in from until to) {
            val ti = t[i]
            while (ti - t[lo] > radius) lo++
            if (hi < i) hi = i
            while (hi + 1 < n && t[hi + 1] - ti <= radius) hi++
            size[i] = ((prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)).toFloat()
        }
        windowLow = lo
        windowHigh = hi
    }

    private fun finalEdges(from: Int, to: Int) {
        for (i in from until to) {
            // The explicit union only absorbs rounding: the smoothed size already reaches the detection.
            left[i] = max(0f, min(left[i], smoothX[i] - halfW[i]))
            right[i] = min(1f, max(right[i], smoothX[i] + halfW[i]))
            top[i] = max(0f, min(top[i], smoothY[i] - halfH[i]))
            bottom[i] = min(1f, max(bottom[i], smoothY[i] + halfH[i]))
        }
    }

    private fun emit(
        from: Int,
        to: Int,
        n: Int,
        runs: IntArray,
        outTimes: LongArray,
        outBoxes: FloatArray,
        outEyes: FloatArray,
        outKinds: ByteArray,
    ) {
        var k = outAt
        for (i in from until to) {
            outTimes[k] = t[i]
            outKinds[k] = FaceTrack.SAMPLE_DETECTED
            storeAxis(outBoxes, k * 4, left[i], right[i])
            storeAxis(outBoxes, k * 4 + 1, top[i], bottom[i])
            System.arraycopy(smoothEyes, i * 4, outEyes, k * 4, 4)
            k++
            if (i + 1 < n && runs[i + 1] - runs[i] > 1) k = interpolate(i, runs, outTimes, outBoxes, outEyes, outKinds, k)
        }
        outAt = k
    }

    /** Writes one sample for every detector run between detections [i] and [i] + 1. Returns the next free slot. */
    private fun interpolate(
        i: Int,
        runs: IntArray,
        outTimes: LongArray,
        outBoxes: FloatArray,
        outEyes: FloatArray,
        outKinds: ByteArray,
        start: Int,
    ): Int {
        var k = start
        val span = (t[i + 1] - t[i]).toDouble()
        for (run in runs[i] + 1 until runs[i + 1]) {
            val timeUs = runPtsUs[run]
            val f = ((timeUs - t[i]) / span).toFloat()
            val cx = lerp(left[i] + right[i], left[i + 1] + right[i + 1], f) * 0.5f
            val cy = lerp(top[i] + bottom[i], top[i + 1] + bottom[i + 1], f) * 0.5f
            val hw = lerp(right[i] - left[i], right[i + 1] - left[i + 1], f) * HALF_INFLATED
            val hh = lerp(bottom[i] - top[i], bottom[i + 1] - top[i + 1], f) * HALF_INFLATED
            outTimes[k] = timeUs
            outKinds[k] = FaceTrack.SAMPLE_INTERPOLATED
            storeAxis(outBoxes, k * 4, cx - hw, cx + hw)
            storeAxis(outBoxes, k * 4 + 1, cy - hh, cy + hh)
            for (e in 0 until 4) outEyes[k * 4 + e] = lerp(smoothEyes[i * 4 + e], smoothEyes[(i + 1) * 4 + e], f)
            k++
        }
        return k
    }

    /**
     * Writes the sample for [timeUs] by carrying the end sample [from] along its velocity relative to [toward].
     * The box grows with the distance in time and keeps the end sample's box inside it, so a still face stays covered.
     */
    private fun extrapolate(from: Int, toward: Int, timeUs: Long, outBoxes: FloatArray, outEyes: FloatArray, k: Int) {
        val elapsedUs = timeUs - t[from]
        var dx = 0f
        var dy = 0f
        val spanUs = t[from] - t[toward]
        if (spanUs != 0L) {
            val scale = elapsedUs.toFloat() / spanUs
            val w = right[from] - left[from]
            val h = bottom[from] - top[from]
            // A velocity from two noisy boxes can be wild; the face is assumed to move at most this far.
            dx = ((smoothX[from] - smoothX[toward]) * scale).coerceIn(-MAX_ROLL_SHIFT * w, MAX_ROLL_SHIFT * w)
            dy = ((smoothY[from] - smoothY[toward]) * scale).coerceIn(-MAX_ROLL_SHIFT * h, MAX_ROLL_SHIFT * h)
        }
        val growth = (1.0 + Tuning.ROLL_GROWTH_PER_SECOND * abs(elapsedUs) * 1e-6).toFloat()
        val cx = (left[from] + right[from]) * 0.5f + dx
        val cy = (top[from] + bottom[from]) * 0.5f + dy
        val hw = (right[from] - left[from]) * 0.5f * growth
        val hh = (bottom[from] - top[from]) * 0.5f * growth
        storeAxis(outBoxes, k * 4, min(left[from], cx - hw), max(right[from], cx + hw))
        storeAxis(outBoxes, k * 4 + 1, min(top[from], cy - hh), max(bottom[from], cy + hh))
        outEyes[k * 4] = smoothEyes[from * 4] + dx
        outEyes[k * 4 + 1] = smoothEyes[from * 4 + 1] + dy
        outEyes[k * 4 + 2] = smoothEyes[from * 4 + 2] + dx
        outEyes[k * 4 + 3] = smoothEyes[from * 4 + 3] + dy
    }

    /** Writes center and size for one axis so that center - size / 2 and center + size / 2 stay in 0..1 in float math. */
    private fun storeAxis(out: FloatArray, at: Int, low: Float, high: Float) {
        var lo = low
        var hi = high
        if (lo.isNaN() || hi.isNaN()) {
            // Cannot happen with finite detections; if it ever does, covering the whole axis is the safe answer.
            lo = 0f
            hi = 1f
        }
        lo = lo.coerceIn(0f, 1f)
        hi = hi.coerceIn(0f, 1f)
        if (hi - lo < MIN_SIZE) {
            val mid = ((lo + hi) * 0.5f).coerceIn(MIN_SIZE * 0.5f, 1f - MIN_SIZE * 0.5f)
            lo = mid - MIN_SIZE * 0.5f
            hi = mid + MIN_SIZE * 0.5f
        }
        val center = (lo + hi) * 0.5f
        var size = hi - lo
        val reach = min(center, 1f - center)
        if (size * 0.5f > reach) size = reach * 2f
        out[at] = center
        out[at + 2] = size
    }

    private fun lerp(a: Float, b: Float, f: Float): Float = a + (b - a) * f

    private companion object {
        const val MIN_SIZE = Tuning.MIN_BOX_SIZE.toFloat()
        const val HALF_INFLATED = (Tuning.INTERPOLATED_INFLATION * 0.5).toFloat()
        const val BLOCK = 64

        // Weights are looked up by the time between two samples, in steps of 64 microseconds up to eight
        // time constants, where the weight is taken as zero.
        const val DECAY_STEP_US = 64L
        const val DECAY_STEPS = (8 * Tuning.CENTER_SMOOTH_TAU_US / DECAY_STEP_US).toInt()
        const val MIN_SPREAD = 1e-9

        /** Pre-roll and post-roll take their velocity from this many detections back. */
        const val ROLL_VELOCITY_SPAN = 4
        const val MAX_ROLL_SHIFT = 1.5f
    }
}
