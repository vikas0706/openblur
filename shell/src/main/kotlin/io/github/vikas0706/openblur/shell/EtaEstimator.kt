package io.github.vikas0706.openblur.shell

import kotlin.math.abs

/**
 * Estimates the time an export still needs from how fast its progress really moves.
 * The speed is measured over a sliding window, so a slow start does not skew the estimate for long.
 * Not thread-safe.
 */
internal class EtaEstimator(
    private val windowMs: Long = 20_000L,
    private val minSpanMs: Long = 5_000L,
    private val minProgress: Float = 0.03f,
) {
    private val times = ArrayDeque<Long>()
    private val fractions = ArrayDeque<Float>()
    // The expected total duration is smoothed, not the time left: the total holds still at a steady speed,
    // so averaging it does not lag the way averaging a shrinking number would.
    private var smoothedTotalMs = Double.NaN
    private var agreeing = 0
    private var shown = false

    fun reset() {
        times.clear()
        fractions.clear()
        smoothedTotalMs = Double.NaN
        agreeing = 0
        shown = false
    }

    /**
     * Feeds one progress report and returns the estimated milliseconds left, or null while the
     * estimate is not stable enough to show.
     */
    fun update(elapsedMs: Long, progress: Float): Long? {
        if (progress.isNaN()) return current()
        // Progress or time going backwards means a new attempt, for example a retry with a fallback encoder.
        if (times.isNotEmpty() && (elapsedMs < times.last() || progress < fractions.last())) reset()
        if (times.isNotEmpty() && elapsedMs == times.last()) return current()
        times.addLast(elapsedMs)
        fractions.addLast(progress)
        while (times.size > 2 && times.first() < elapsedMs - windowMs) {
            times.removeFirst()
            fractions.removeFirst()
        }
        val spanMs = elapsedMs - times.first()
        val gained = progress - fractions.first()
        if (progress < minProgress || spanMs < minSpanMs || gained <= 0f) return current()

        val rawTotalMs = elapsedMs + (1.0 - progress) / (gained / spanMs.toDouble())
        if (smoothedTotalMs.isNaN()) {
            smoothedTotalMs = rawTotalMs
            return null
        }
        // Once shown, the estimate is only withdrawn for a large jump, so it does not flicker.
        val tolerance = if (shown) 0.5 else 0.25
        val smoothedLeftMs = (smoothedTotalMs - elapsedMs).coerceAtLeast(MIN_COMPARE_MS)
        if (abs(rawTotalMs - smoothedTotalMs) <= tolerance * smoothedLeftMs) agreeing++ else agreeing = 0
        smoothedTotalMs = smoothedTotalMs * 0.7 + rawTotalMs * 0.3
        shown = agreeing >= STABLE_REPORTS
        return current()
    }

    private fun current(): Long? {
        if (!shown || times.isEmpty()) return null
        return (smoothedTotalMs - times.last()).toLong().coerceAtLeast(0L)
    }

    private companion object {
        const val STABLE_REPORTS = 3

        // Near the end a few seconds of jitter is a large share of what is left; it must not hide the estimate.
        const val MIN_COMPARE_MS = 10_000.0
    }
}
