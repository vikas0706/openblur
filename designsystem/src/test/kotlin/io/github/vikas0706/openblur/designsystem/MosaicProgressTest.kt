package io.github.vikas0706.openblur.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MosaicProgressTest {
    private val tiles = 12

    @Test
    fun noProgressLeavesEveryTileEmpty() {
        for (i in 0 until tiles) assertEquals(0f, tileFill(0f, i, tiles), 0f)
    }

    @Test
    fun fullProgressFillsEveryTile() {
        for (i in 0 until tiles) assertEquals(1f, tileFill(1f, i, tiles), 0f)
    }

    @Test
    fun tilesFillOneAfterAnotherFromTheStart() {
        assertEquals(1f, tileFill(0.5f, 5, tiles), 0f)
        assertEquals(0f, tileFill(0.5f, 6, tiles), 0f)
        assertEquals(0.5f, tileFill(0.375f, 4, tiles), 1e-5f)
        for (step in 0..100) {
            val progress = step / 100f
            for (i in 1 until tiles) {
                assertTrue(tileFill(progress, i, tiles) <= tileFill(progress, i - 1, tiles))
            }
        }
    }

    @Test
    fun filledTilesAddUpToTheProgress() {
        for (step in 0..100) {
            val progress = step / 100f
            val sum = (0 until tiles).sumOf { tileFill(progress, it, tiles).toDouble() }
            assertEquals(progress * tiles.toDouble(), sum, 1e-4)
        }
    }

    @Test
    fun progressOutsideTheRangeIsClamped() {
        for (i in 0 until tiles) {
            assertEquals(0f, tileFill(-0.5f, i, tiles), 0f)
            assertEquals(1f, tileFill(1.5f, i, tiles), 0f)
        }
    }

    @Test
    fun theSweepStartsAndEndsDarkSoItRepeatsWithoutAJump() {
        for (i in 0 until tiles) {
            assertEquals(0f, sweepFill(0f, i, tiles), 0f)
            assertEquals(0f, sweepFill(1f, i, tiles), 0f)
        }
    }

    @Test
    fun theSweepStaysInRangeAndLightsAFewNeighbouringTiles() {
        for (step in 0..1000) {
            val phase = step / 1000f
            val lit = (0 until tiles).filter { sweepFill(phase, it, tiles) > 0f }
            assertTrue("at most 4 tiles lit at $phase", lit.size <= 4)
            if (lit.isNotEmpty()) assertEquals("lit tiles are neighbours at $phase", lit.size - 1, lit.last() - lit.first())
            for (i in 0 until tiles) assertTrue(sweepFill(phase, i, tiles) in 0f..1f)
        }
    }

    @Test
    fun theSweepReachesEveryTileAtFullBrightness() {
        for (i in 0 until tiles) {
            val peak = (0..2000).maxOf { sweepFill(it / 2000f, i, tiles) }
            assertTrue("tile $i peaks at $peak", peak > 0.95f)
        }
    }

    @Test
    fun theSweepChangesSmoothly() {
        for (i in 0 until tiles) {
            var previous = sweepFill(0f, i, tiles)
            for (step in 1..1000) {
                val current = sweepFill(step / 1000f, i, tiles)
                assertTrue("tile $i jumps at step $step", kotlin.math.abs(current - previous) < 0.05f)
                previous = current
            }
        }
    }
}
