package io.github.vikas0706.openblur.tracking

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssignmentTest {
    /** Cheapest way to give every row its own column, by trying them all. */
    private fun cheapest(cost: FloatArray, rows: Int, cols: Int, row: Int = 0, usedCols: Int = 0): Double {
        if (row == rows) return 0.0
        var best = Double.MAX_VALUE
        for (c in 0 until cols) {
            if (usedCols and (1 shl c) != 0) continue
            best = minOf(best, cost[row * cols + c] + cheapest(cost, rows, cols, row + 1, usedCols or (1 shl c)))
        }
        return best
    }

    /** Largest total gain over all sets of allowed pairs, by trying them all. */
    private fun richest(gain: FloatArray, rows: Int, cols: Int, row: Int = 0, usedCols: Int = 0): Double {
        if (row == rows) return 0.0
        var best = richest(gain, rows, cols, row + 1, usedCols)
        for (c in 0 until cols) {
            val g = gain[row * cols + c]
            if (g <= 0f || usedCols and (1 shl c) != 0) continue
            best = maxOf(best, g + richest(gain, rows, cols, row + 1, usedCols or (1 shl c)))
        }
        return best
    }

    @Test
    fun solverFindsTheCheapestAssignment() {
        val random = Random(3)
        val solver = AssignmentSolver()
        repeat(2000) {
            val rows = 1 + random.nextInt(6)
            val cols = rows + random.nextInt(3)
            val cost = FloatArray(rows * cols) { if (random.nextInt(4) == 0) 0f else random.nextFloat() * 10f - 5f }
            val rowToCol = IntArray(rows) { -1 }
            solver.solve(cost, rows, cols, rowToCol)
            var total = 0.0
            var used = 0
            for (r in 0 until rows) {
                assertTrue(rowToCol[r] in 0 until cols)
                assertEquals("each column is used once", 0, used and (1 shl rowToCol[r]))
                used = used or (1 shl rowToCol[r])
                total += cost[r * cols + rowToCol[r]]
            }
            assertEquals(cheapest(cost, rows, cols), total, 1e-4)
        }
    }

    @Test
    fun matcherFindsTheLargestTotalGainAndNeverUsesAGatedPair() {
        val random = Random(4)
        val matcher = GatedMatcher()
        repeat(3000) {
            val rows = 1 + random.nextInt(7)
            val cols = 1 + random.nextInt(7)
            val density = random.nextFloat()
            val gain = FloatArray(rows * cols) { if (random.nextFloat() < density) 0.01f + random.nextFloat() else 0f }
            val rowToCol = IntArray(rows) { 99 }
            matcher.match(gain, rows, cols, rowToCol)
            var total = 0.0
            var used = 0
            for (r in 0 until rows) {
                val c = rowToCol[r]
                if (c < 0) continue
                assertTrue(c < cols)
                assertTrue("a matched pair is allowed", gain[r * cols + c] > 0f)
                assertEquals("each column is used once", 0, used and (1 shl c))
                used = used or (1 shl c)
                total += gain[r * cols + c]
            }
            assertEquals(richest(gain, rows, cols), total, 1e-4)
        }
    }

    @Test
    fun matcherPrefersTheBetterTotalOverTheBestSinglePair() {
        // Greedy would take row 0 with column 0 (0.9) and leave row 1 with nothing.
        val gain = floatArrayOf(
            0.9f, 0.8f,
            0.7f, 0f,
        )
        val rowToCol = IntArray(2)
        GatedMatcher().match(gain, 2, 2, rowToCol)
        assertEquals(listOf(1, 0), rowToCol.toList())
    }

    @Test
    fun matcherHandlesLargeSparseProblems() {
        val random = Random(5)
        val rows = 300
        val cols = 320
        val gain = FloatArray(rows * cols)
        // Each row competes with its neighbors for three adjacent columns, as faces in a crowd do.
        for (r in 0 until rows) for (c in r..minOf(cols - 1, r + 2)) gain[r * cols + c] = 0.1f + random.nextFloat()
        val rowToCol = IntArray(rows)
        val started = System.nanoTime()
        val matcher = GatedMatcher()
        repeat(20) { matcher.match(gain, rows, cols, rowToCol) }
        val millis = (System.nanoTime() - started) / 1e6 / 20
        println("300 x 320 sparse matching: %.2f ms".format(millis))
        val matched = rowToCol.filter { it >= 0 }
        assertEquals("each column is used once", matched.size, matched.toSet().size)
        // The best total can leave a row out, so not every row has to be matched.
        assertTrue("${matched.size} of $rows rows matched", matched.size > rows * 9 / 10)
        assertTrue("one matching took $millis ms", millis < 200)
    }
}
