package io.github.vikas0706.openblur.tracking

/** Minimum-cost assignment of every row to its own column (Hungarian method with potentials). */
internal class AssignmentSolver {
    private var u = DoubleArray(0)
    private var v = DoubleArray(0)
    private var minv = DoubleArray(0)
    private var p = IntArray(0)
    private var way = IntArray(0)
    private var used = BooleanArray(0)

    /**
     * [cost] is row-major with [rows] <= [cols]. Writes the column of each row into [rowToCol].
     * Runs in O(rows * rows * cols).
     */
    fun solve(cost: FloatArray, rows: Int, cols: Int, rowToCol: IntArray) {
        require(rows <= cols) { "rows ($rows) must not exceed cols ($cols)" }
        if (u.size <= rows) u = DoubleArray(rows + 1)
        if (v.size <= cols) {
            v = DoubleArray(cols + 1)
            minv = DoubleArray(cols + 1)
            p = IntArray(cols + 1)
            way = IntArray(cols + 1)
            used = BooleanArray(cols + 1)
        }
        u.fill(0.0, 0, rows + 1)
        v.fill(0.0, 0, cols + 1)
        p.fill(0, 0, cols + 1)
        for (i in 1..rows) {
            p[0] = i
            var j0 = 0
            minv.fill(Double.MAX_VALUE, 0, cols + 1)
            used.fill(false, 0, cols + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                val rowAt = (i0 - 1) * cols - 1
                val ui = u[i0]
                var delta = Double.MAX_VALUE
                var j1 = 0
                for (j in 1..cols) {
                    if (used[j]) continue
                    val reduced = cost[rowAt + j] - ui - v[j]
                    if (reduced < minv[j]) {
                        minv[j] = reduced
                        way[j] = j0
                    }
                    if (minv[j] < delta) {
                        delta = minv[j]
                        j1 = j
                    }
                }
                for (j in 0..cols) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        for (j in 1..cols) {
            val i = p[j]
            if (i != 0) rowToCol[i - 1] = j - 1
        }
    }
}

/**
 * Picks the set of allowed pairs with the largest total gain, each row and column used at most once.
 * Pairs outside the gate have gain zero and are never returned.
 */
internal class GatedMatcher {
    private val solver = AssignmentSolver()
    private var rowAllowed = IntArray(0)
    private var rowOnly = IntArray(0)
    private var rowMap = IntArray(0)
    private var colAllowed = IntArray(0)
    private var colMap = IntArray(0)
    private var cost = FloatArray(0)
    private var assigned = IntArray(0)

    /** [gain] is row-major, positive where a pair is allowed. Writes each row's column, or -1, into [rowToCol]. */
    fun match(gain: FloatArray, rows: Int, cols: Int, rowToCol: IntArray) {
        if (rowAllowed.size < rows) {
            rowAllowed = IntArray(rows)
            rowOnly = IntArray(rows)
            rowMap = IntArray(rows)
        }
        if (colAllowed.size < cols) {
            colAllowed = IntArray(cols)
            colMap = IntArray(cols)
        }
        colAllowed.fill(0, 0, cols)
        for (r in 0 until rows) {
            var allowed = 0
            var only = -1
            val at = r * cols
            for (c in 0 until cols) {
                if (gain[at + c] > 0f) {
                    allowed++
                    only = c
                    colAllowed[c]++
                }
            }
            rowAllowed[r] = allowed
            rowOnly[r] = only
            rowToCol[r] = -1
        }

        // A pair that competes with nothing is its own component; only contested rows reach the solver.
        var nr = 0
        for (r in 0 until rows) {
            if (rowAllowed[r] == 0) continue
            val only = rowOnly[r]
            if (rowAllowed[r] == 1 && colAllowed[only] == 1) {
                rowToCol[r] = only
                colAllowed[only] = 0
            } else {
                rowMap[nr++] = r
            }
        }
        if (nr == 0) return
        var nc = 0
        for (c in 0 until cols) if (colAllowed[c] > 0) colMap[nc++] = c

        if (cost.size < nr * nc) cost = FloatArray(nr * nc)
        if (assigned.size < maxOf(nr, nc)) assigned = IntArray(maxOf(nr, nc))
        // A gated pair costs zero, the same as staying unmatched, so the solver never prefers it to a real match.
        if (nr <= nc) {
            for (i in 0 until nr) {
                val from = rowMap[i] * cols
                for (j in 0 until nc) cost[i * nc + j] = -gain[from + colMap[j]]
            }
            solver.solve(cost, nr, nc, assigned)
            for (i in 0 until nr) {
                val c = colMap[assigned[i]]
                if (gain[rowMap[i] * cols + c] > 0f) rowToCol[rowMap[i]] = c
            }
        } else {
            for (j in 0 until nc) {
                val c = colMap[j]
                for (i in 0 until nr) cost[j * nr + i] = -gain[rowMap[i] * cols + c]
            }
            solver.solve(cost, nc, nr, assigned)
            for (j in 0 until nc) {
                val r = rowMap[assigned[j]]
                if (gain[r * cols + colMap[j]] > 0f) rowToCol[r] = colMap[j]
            }
        }
    }
}
