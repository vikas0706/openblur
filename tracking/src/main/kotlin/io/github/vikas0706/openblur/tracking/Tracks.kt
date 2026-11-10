package io.github.vikas0706.openblur.tracking

import kotlin.math.max

/** A face being followed. Holds every detection matched to it and the filter that predicts the next one. */
internal class LiveTrack(val serial: Int, val shot: Int) {
    var size = 0
        private set

    /** Detector run index per sample; times are looked up from the run clock when the scan ends. */
    var runs = IntArray(INITIAL_SAMPLES)
        private set
    var boxes = FloatArray(INITIAL_SAMPLES * 4)
        private set
    var eyes = FloatArray(INITIAL_SAMPLES * 4)
        private set
    var scoreSum = 0.0
        private set
    var maxScore = 0f
        private set
    var lastRun = -1
        private set
    var lastPtsUs = 0L
        private set
    var matched = false

    /** cx, cy, w, h and their rates of change per second. */
    val state = DoubleArray(8)

    /** Per coordinate: variance of the value, covariance with its rate, variance of the rate. */
    private val cov = DoubleArray(12)

    var thumbHandle = -1
    var thumbQuality = 0f

    /**
     * The thumbnail that was current when the track last passed an identity check, kept while a newer one
     * is unconfirmed: if the track turns out to have drifted to someone else, this is the picture to go back to.
     */
    var heldThumb = -1
    var heldThumbQuality = 0f

    /** The embedding matches are checked against, and the quality of the crop it came from. */
    var reference: FloatArray? = null
    var referenceQuality = 0f

    /** The detection this track was last checked against, so a repeated assignment is not checked twice. */
    var checkedRun = -1
    var checkedDetection = -1

    /** When the track last passed an identity check, and how many it has failed in a row since. */
    var confirmedPtsUs = 0L
    var strikes = 0

    /** Set when the track turned out to be following someone else; it is closed at the end of the run. */
    var retired = false

    val sampleCrops = arrayOfNulls<ByteArray>(Tuning.MAX_SAMPLES_PER_TRACK)
    val sampleQuality = FloatArray(Tuning.MAX_SAMPLES_PER_TRACK)
    val sampleTimeUs = LongArray(Tuning.MAX_SAMPLES_PER_TRACK)
    var sampleCount = 0

    fun append(run: Int, ptsUs: Long, box: FloatArray, boxAt: Int, eyeSource: FloatArray?, eyeAt: Int, score: Float) {
        if (size == runs.size) {
            val n = size * 2
            runs = runs.copyOf(n)
            boxes = boxes.copyOf(n * 4)
            eyes = eyes.copyOf(n * 4)
        }
        val at = size * 4
        runs[size] = run
        for (k in 0 until 4) {
            boxes[at + k] = box[boxAt + k]
            eyes[at + k] = if (eyeSource == null) Float.NaN else eyeSource[eyeAt + k]
        }
        size++
        scoreSum += score
        maxScore = max(maxScore, score)
        lastRun = run
        lastPtsUs = ptsUs
    }

    fun startFilter(cx: Float, cy: Float, w: Float, h: Float) {
        state[0] = cx.toDouble()
        state[1] = cy.toDouble()
        state[2] = w.toDouble()
        state[3] = h.toDouble()
        state.fill(0.0, 4, 8)
        for (k in 0 until 4) {
            val scale = if (k and 1 == 0) w else h
            cov[k * 3] = square(START_POSITION_STD * scale)
            cov[k * 3 + 1] = 0.0
            cov[k * 3 + 2] = square(START_RATE_STD * scale)
        }
    }

    /**
     * Moves the estimate [seconds] ahead. A face that was missed keeps drifting but slows down by [damping]
     * and holds its size, because a stale rate carried for many runs sends the box somewhere arbitrary.
     */
    fun predict(seconds: Double, missed: Boolean, damping: Double) {
        if (missed) {
            state[4] *= damping
            state[5] *= damping
            state[6] = 0.0
            state[7] = 0.0
        }
        val frames = seconds * REFERENCE_RATE
        val w = state[2]
        val h = state[3]
        for (k in 0 until 4) {
            val scale = if (k and 1 == 0) w else h
            state[k] += state[k + 4] * seconds
            val i = k * 3
            val covariance = cov[i + 1]
            val rateVariance = cov[i + 2]
            cov[i] += seconds * (2.0 * covariance + seconds * rateVariance) + square(POSITION_STD * scale) * frames
            cov[i + 1] = covariance + seconds * rateVariance
            cov[i + 2] = rateVariance + square(RATE_STD * scale) * frames
        }
        if (state[2] < MIN_SIZE) state[2] = MIN_SIZE
        if (state[3] < MIN_SIZE) state[3] = MIN_SIZE
    }

    fun correct(cx: Float, cy: Float, w: Float, h: Float, noiseScale: Double) {
        val sw = state[2]
        val sh = state[3]
        correct(0, cx.toDouble(), sw, noiseScale)
        correct(1, cy.toDouble(), sh, noiseScale)
        correct(2, w.toDouble(), sw, noiseScale)
        correct(3, h.toDouble(), sh, noiseScale)
        if (state[2] < MIN_SIZE) state[2] = MIN_SIZE
        if (state[3] < MIN_SIZE) state[3] = MIN_SIZE
    }

    private fun correct(k: Int, measured: Double, scale: Double, noiseScale: Double) {
        val i = k * 3
        val variance = cov[i]
        val covariance = cov[i + 1]
        val inverse = 1.0 / (variance + square(MEASUREMENT_STD * scale) * noiseScale)
        val gain = variance * inverse
        val rateGain = covariance * inverse
        val error = measured - state[k]
        state[k] += gain * error
        state[k + 4] += rateGain * error
        cov[i] = variance - gain * variance
        cov[i + 1] = covariance - gain * covariance
        cov[i + 2] -= rateGain * covariance
    }

    private fun square(x: Double) = x * x

    private companion object {
        const val INITIAL_SAMPLES = 16
        const val MIN_SIZE = 1e-5

        // Noise is in units of the face size, per frame at 30 fps, as in ByteTrack (1/20 and 1/160), except the
        // rate noise: a face in handheld footage changes speed far faster than a walker seen from a fixed camera.
        const val REFERENCE_RATE = 30.0
        const val POSITION_STD = 1.0 / 20.0
        const val MEASUREMENT_STD = 1.0 / 20.0
        const val RATE_STD = REFERENCE_RATE / 25.0
        const val START_POSITION_STD = 2.0 / 20.0
        const val START_RATE_STD = 10.0 * REFERENCE_RATE / 160.0
    }
}

/**
 * A finished track, reduced to what the end of the scan needs. The arrays hold [size] samples, may be
 * longer than that, and are dropped once the track is built.
 */
internal class ClosedTrack(
    val serial: Int,
    val shot: Int,
    val size: Int,
    var runs: IntArray?,
    var boxes: FloatArray?,
    var eyes: FloatArray?,
    val meanScore: Float,
    var thumbHandle: Int,
    val thumbQuality: Float,
    /** Unit mean of the track's embeddings, or null when it has none. */
    val centroid: FloatArray?,
    val centroidWeight: Float,
)

/** Detections of recent runs that no track took, so a new track can claim the ones that led up to it. */
internal class LeftoverRing {
    private val slotRun = IntArray(RUNS) { -1 }
    private val slotPtsUs = LongArray(RUNS)
    private val slotCount = IntArray(RUNS)
    val box = FloatArray(RUNS * PER_RUN * 4)
    val eyes = FloatArray(RUNS * PER_RUN * 4)
    val score = FloatArray(RUNS * PER_RUN)
    val hasEyes = BooleanArray(RUNS * PER_RUN)
    private val taken = BooleanArray(RUNS * PER_RUN)
    private var current = 0

    fun clear() {
        slotRun.fill(-1)
    }

    fun begin(run: Int, ptsUs: Long) {
        current = run % RUNS
        slotRun[current] = run
        slotPtsUs[current] = ptsUs
        slotCount[current] = 0
    }

    fun add(batch: DetectionBatch, index: Int) {
        val n = slotCount[current]
        if (n == PER_RUN) return
        val entry = current * PER_RUN + n
        for (k in 0 until 4) {
            box[entry * 4 + k] = batch.box[index * 4 + k]
            // Landmarks start with the right eye, then the left eye.
            eyes[entry * 4 + k] = batch.landmarks[index * 10 + k]
        }
        score[entry] = batch.score[index]
        hasEyes[entry] = batch.hasLandmarks[index]
        taken[entry] = false
        slotCount[current] = n + 1
    }

    /** True when [run] is still held; false once it was overwritten or cleared at a cut. */
    fun holds(run: Int): Boolean = run >= 0 && slotRun[run % RUNS] == run

    fun ptsUs(run: Int): Long = slotPtsUs[run % RUNS]

    fun firstEntry(run: Int): Int = (run % RUNS) * PER_RUN

    fun entryCount(run: Int): Int = slotCount[run % RUNS]

    fun isTaken(entry: Int): Boolean = taken[entry]

    fun take(entry: Int) {
        taken[entry] = true
    }

    companion object {
        const val RUNS = 64
        const val PER_RUN = 24
    }
}
