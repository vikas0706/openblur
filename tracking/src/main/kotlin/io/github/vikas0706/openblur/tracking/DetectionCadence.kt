package io.github.vikas0706.openblur.tracking

import kotlin.math.min

/** Picks the frames that run the detector from their timestamps, so runs never exceed a rate for long. */
internal class DetectionCadence(maxPerSecond: Float) {
    private val creditPerUs = maxPerSecond.toDouble() / 1_000_000.0
    private var credit = 0.0
    private var lastPtsUs = 0L
    private var started = false

    fun wouldRun(ptsUs: Long): Boolean = !started || creditAt(ptsUs) >= RUN_THRESHOLD

    /** Records the frame at [ptsUs] and returns whether it runs the detector. */
    fun advance(ptsUs: Long): Boolean {
        if (!started) {
            started = true
            lastPtsUs = ptsUs
            credit = 0.0
            return true
        }
        val available = creditAt(ptsUs)
        lastPtsUs = ptsUs
        val run = available >= RUN_THRESHOLD
        credit = if (run) available - 1.0 else available
        return run
    }

    private fun creditAt(ptsUs: Long): Double = min(CREDIT_CAP, credit + (ptsUs - lastPtsUs) * creditPerUs)

    private companion object {
        // A frame up to 0.3 of an interval early still runs and the debt is carried, so jitter costs no run.
        // 0.7 also stays clear of the credit steps common frame rates land on, where rounding would decide.
        const val RUN_THRESHOLD = 0.7
        const val CREDIT_CAP = 1.3
    }
}
