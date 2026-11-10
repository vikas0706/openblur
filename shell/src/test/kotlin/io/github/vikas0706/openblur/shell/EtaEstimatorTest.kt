package io.github.vikas0706.openblur.shell

import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaEstimatorTest {

    /** Feeds one report per second at a constant speed and returns the estimate after each one. */
    private fun run(estimator: EtaEstimator, fromSecond: Int, toSecond: Int, totalSeconds: Int): List<Long?> =
        (fromSecond..toSecond).map { second ->
            estimator.update(second * 1000L, second.toFloat() / totalSeconds)
        }

    @Test
    fun hiddenDuringTheFirstSeconds() {
        val estimates = run(EtaEstimator(), 0, 5, totalSeconds = 100)
        assertTrue(estimates.all { it == null })
    }

    @Test
    fun steadyProgressGivesTheTrueRemainingTime() {
        val estimator = EtaEstimator()
        val estimates = run(estimator, 0, 30, totalSeconds = 100)
        val last = estimates.last()
        assertNotNull(last)
        // 30 of 100 seconds done at one second of video per second: 70 seconds left.
        assertTrue("was $last", abs(last!! - 70_000L) < 2_000L)
    }

    @Test
    fun hiddenWhileProgressHasNotMoved() {
        val estimator = EtaEstimator()
        for (second in 0..30) assertNull(estimator.update(second * 1000L, 0f))
    }

    @Test
    fun hiddenWhenSpeedKeepsJumping() {
        val estimator = EtaEstimator(windowMs = 6_000L)
        var progress = 0.05f
        var shown = 0
        for (second in 0..40) {
            // Alternates between crawling and sprinting every few seconds.
            progress += if ((second / 4) % 2 == 0) 0.0005f else 0.02f
            if (estimator.update(second * 1000L, progress.coerceAtMost(0.99f)) != null) shown++
        }
        assertTrue("shown $shown times", shown < 10)
    }

    @Test
    fun aSlowStartStopsMatteringOnceTheWindowHasPassed() {
        val estimator = EtaEstimator()
        // 30 seconds stuck near zero, then one percent per second.
        for (second in 0..30) estimator.update(second * 1000L, 0.001f)
        var last: Long? = null
        for (second in 31..70) last = estimator.update(second * 1000L, 0.001f + (second - 30) * 0.01f)
        assertNotNull(last)
        // About 60 percent left at one percent per second.
        assertTrue("was $last", abs(last!! - 60_000L) < 6_000L)
    }

    @Test
    fun progressGoingBackwardsStartsOver() {
        val estimator = EtaEstimator()
        assertNotNull(run(estimator, 0, 30, totalSeconds = 100).last())
        // A retry with another encoder reports progress from zero again.
        assertNull(estimator.update(31_000L, 0f))
        assertNull(estimator.update(32_000L, 0.01f))
    }

    @Test
    fun repeatedReportForTheSameInstantKeepsTheEstimate() {
        val estimator = EtaEstimator()
        val before = run(estimator, 0, 30, totalSeconds = 100).last()
        assertTrue(before == estimator.update(30_000L, 0.30f))
    }

    @Test
    fun resetHidesTheEstimate() {
        val estimator = EtaEstimator()
        run(estimator, 0, 30, totalSeconds = 100)
        estimator.reset()
        assertNull(estimator.update(31_000L, 0.31f))
    }

    @Test
    fun notANumberIsIgnored() {
        val estimator = EtaEstimator()
        assertNull(estimator.update(1_000L, Float.NaN))
        assertNotNull(run(estimator, 0, 30, totalSeconds = 100).last())
    }
}
