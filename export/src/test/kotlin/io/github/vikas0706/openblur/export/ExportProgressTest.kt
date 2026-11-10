package io.github.vikas0706.openblur.export

import androidx.media3.transformer.Transformer
import io.github.vikas0706.openblur.api.ExportPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportProgressTest {
    private val tolerance = 1e-6f

    @Test
    fun progressStatesMatchTransformer() {
        assertEquals(Transformer.PROGRESS_STATE_NOT_STARTED, ExportProgress.STATE_NOT_STARTED)
        assertEquals(Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY, ExportProgress.STATE_WAITING)
        assertEquals(Transformer.PROGRESS_STATE_AVAILABLE, ExportProgress.STATE_AVAILABLE)
        assertEquals(Transformer.PROGRESS_STATE_UNAVAILABLE, ExportProgress.STATE_UNAVAILABLE)
    }

    @Test
    fun encodeFractionIsKnownOnlyWhileTransformerReportsAPercentage() {
        assertEquals(0f, ExportProgress.encodeFraction(ExportProgress.STATE_AVAILABLE, 0)!!, tolerance)
        assertEquals(0.5f, ExportProgress.encodeFraction(ExportProgress.STATE_AVAILABLE, 50)!!, tolerance)
        assertEquals(1f, ExportProgress.encodeFraction(ExportProgress.STATE_AVAILABLE, 100)!!, tolerance)
        assertNull(ExportProgress.encodeFraction(ExportProgress.STATE_NOT_STARTED, 50))
        assertNull(ExportProgress.encodeFraction(ExportProgress.STATE_WAITING, 50))
        assertNull(ExportProgress.encodeFraction(ExportProgress.STATE_UNAVAILABLE, 50))
    }

    @Test
    fun percentagesOutsideTheRangeAreClamped() {
        assertEquals(1f, ExportProgress.encodeFraction(ExportProgress.STATE_AVAILABLE, 140)!!, tolerance)
        assertEquals(0f, ExportProgress.encodeFraction(ExportProgress.STATE_AVAILABLE, -3)!!, tolerance)
    }

    @Test
    fun preparingIsAlwaysZero() {
        assertEquals(0f, ExportProgress.overall(ExportPhase.PREPARING, 0f), tolerance)
        assertEquals(0f, ExportProgress.overall(ExportPhase.PREPARING, 0.8f), tolerance)
    }

    @Test
    fun encodingFillsTheBarUpToItsShare() {
        assertEquals(0f, ExportProgress.overall(ExportPhase.ENCODING, 0f), tolerance)
        assertEquals(0.48f, ExportProgress.overall(ExportPhase.ENCODING, 0.5f), tolerance)
        assertEquals(ExportProgress.ENCODE_SHARE, ExportProgress.overall(ExportPhase.ENCODING, 1f), tolerance)
    }

    @Test
    fun savingFillsTheRest() {
        assertEquals(ExportProgress.ENCODE_SHARE, ExportProgress.overall(ExportPhase.SAVING, 0f), tolerance)
        assertEquals(0.98f, ExportProgress.overall(ExportPhase.SAVING, 0.5f), tolerance)
        assertEquals(1f, ExportProgress.overall(ExportPhase.SAVING, 1f), tolerance)
    }

    @Test
    fun badPhaseFractionsAreClamped() {
        assertEquals(0f, ExportProgress.overall(ExportPhase.ENCODING, Float.NaN), tolerance)
        assertEquals(0f, ExportProgress.overall(ExportPhase.ENCODING, -1f), tolerance)
        assertEquals(1f, ExportProgress.overall(ExportPhase.SAVING, 7f), tolerance)
    }

    @Test
    fun progressNeverMovesBackwardsWithinAnAttempt() {
        var shown = 0f
        val reports = listOf(
            ExportPhase.ENCODING to 0.10f,
            ExportPhase.ENCODING to 0.40f,
            ExportPhase.ENCODING to 0.35f,
            ExportPhase.ENCODING to 1.00f,
            ExportPhase.SAVING to 0.00f,
            ExportPhase.SAVING to 0.50f,
            ExportPhase.SAVING to 1.00f,
        )
        for ((phase, fraction) in reports) {
            val next = ExportProgress.advance(shown, ExportProgress.overall(phase, fraction))
            assertTrue("$phase $fraction went backwards", next >= shown)
            shown = next
        }
        assertEquals(1f, shown, tolerance)
        assertEquals(0.5f, ExportProgress.advance(0.5f, 0.2f), tolerance)
        assertEquals(1f, ExportProgress.advance(0.5f, 3f), tolerance)
    }

    @Test
    fun percentRoundsDownSoOneHundredMeansDone() {
        assertEquals(0, ExportProgress.percent(0f))
        assertEquals(48, ExportProgress.percent(0.489f))
        assertEquals(99, ExportProgress.percent(0.999f))
        assertEquals(100, ExportProgress.percent(1f))
        assertEquals(0, ExportProgress.percent(-0.5f))
        assertEquals(100, ExportProgress.percent(2f))
    }
}
