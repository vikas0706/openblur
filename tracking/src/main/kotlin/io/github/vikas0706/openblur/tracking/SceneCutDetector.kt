package io.github.vikas0706.openblur.tracking

import java.nio.Buffer
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds scene cuts from a small luma picture of each detector run.
 * A cut is confirmed two runs late, because it has to stand out from the runs after it as well as before it.
 */
internal class SceneCutDetector(contentWidth: Int, contentHeight: Int) {
    private val gridWidth: Int
    private val gridHeight: Int
    private val samplesX: Int
    private val samplesY: Int

    /** Byte offset inside a row of each sampled pixel, [samplesX] per cell. */
    private val sampleOffsets: IntArray

    /** Row of each sampled line, [samplesY] per cell. */
    private val sampleRows: IntArray
    private val rowBytes = contentWidth * 4
    private val row = ByteArray(rowBytes)
    private val cellSums: IntArray
    private val images: Array<ByteArray>
    private val scores = FloatArray(SCORE_HISTORY)

    /** Run of the last confirmed cut. Nothing at or before it says anything about the shot after it. */
    private var lastCutRun = 0
    private val maxShiftX: Int
    private val maxShiftY: Int

    init {
        val longSide = max(contentWidth, contentHeight)
        gridWidth = (GRID_LONG_SIDE.toDouble() * contentWidth / longSide).roundToInt().coerceIn(1, min(GRID_LONG_SIDE, contentWidth))
        gridHeight = (GRID_LONG_SIDE.toDouble() * contentHeight / longSide).roundToInt().coerceIn(1, min(GRID_LONG_SIDE, contentHeight))
        samplesX = min(CELL_SAMPLES, contentWidth / gridWidth)
        samplesY = min(CELL_SAMPLES, contentHeight / gridHeight)
        sampleOffsets = IntArray(gridWidth * samplesX) { i ->
            val x = ((i + 0.5) * contentWidth / (gridWidth * samplesX)).toInt().coerceAtMost(contentWidth - 1)
            x * 4
        }
        sampleRows = IntArray(gridHeight * samplesY) { i ->
            ((i + 0.5) * contentHeight / (gridHeight * samplesY)).toInt().coerceAtMost(contentHeight - 1)
        }
        cellSums = IntArray(gridWidth)
        images = Array(IMAGE_HISTORY) { ByteArray(gridWidth * gridHeight) }
        maxShiftX = min(MAX_SHIFT, gridWidth / 8)
        maxShiftY = min(MAX_SHIFT, gridHeight / 8)
    }

    /**
     * Samples the picture of [run] from [rgba] (rows [rowStride] bytes apart, starting at its position)
     * and returns how much it differs from the run before. The buffer's position is left unchanged.
     */
    fun ingest(run: Int, rgba: ByteBuffer, rowStride: Int): Float {
        val image = images[run and IMAGE_MASK]
        // Moved through the Buffer type: ByteBuffer's own position(int) does not exist on older Android runtimes.
        val cursor: Buffer = rgba
        val base = cursor.position()
        val divisor = samplesX * samplesY * 256
        try {
            for (gy in 0 until gridHeight) {
                cellSums.fill(0)
                for (sy in 0 until samplesY) {
                    // One bulk copy per sampled row: single byte reads from a direct buffer are far slower.
                    cursor.position(base + sampleRows[gy * samplesY + sy] * rowStride)
                    rgba.get(row, 0, rowBytes)
                    var s = 0
                    for (gx in 0 until gridWidth) {
                        var sum = cellSums[gx]
                        for (sx in 0 until samplesX) {
                            val o = sampleOffsets[s++]
                            sum += (row[o].toInt() and 0xFF) * 77 + (row[o + 1].toInt() and 0xFF) * 150 + (row[o + 2].toInt() and 0xFF) * 29
                        }
                        cellSums[gx] = sum
                    }
                }
                val at = gy * gridWidth
                for (gx in 0 until gridWidth) image[at + gx] = ((cellSums[gx] + divisor / 2) / divisor).toByte()
            }
        } finally {
            cursor.position(base)
        }
        val score = if (run == 0) 0f else difference(image, images[(run - 1) and IMAGE_MASK])
        scores[run and SCORE_MASK] = score
        return score
    }

    /** True when [run], the newest run, could still turn out to be a cut once later runs are known. */
    fun isCandidate(run: Int): Boolean {
        if (run < 1) return false
        val score = scores[run and SCORE_MASK]
        if (score < Tuning.CUT_MIN_SCORE) return false
        val flashJustEnded = flashEndsAt(run - 1)
        var before = 0f
        if (run - 1 > lastCutRun && !flashJustEnded) before += scores[(run - 1) and SCORE_MASK]
        if (run - 2 > lastCutRun && !flashJustEnded && !flashEndsAt(run - 2)) before += scores[(run - 2) and SCORE_MASK]
        // With both later scores at zero the neighbor mean is before / 4, so this is the weakest case that can pass.
        return score * 4f >= Tuning.CUT_NEIGHBOR_RATIO * before
    }

    /**
     * Decides a candidate. Call when [newestRun] is [run] + 2, or earlier when the video has ended.
     * Later than that the pictures it compares are gone.
     */
    fun confirm(run: Int, newestRun: Int): Boolean {
        val score = scores[run and SCORE_MASK]
        val flashJustEnded = flashEndsAt(run - 1)
        val flashEarlier = flashJustEnded || flashEndsAt(run - 2)
        val flashAfter = newestRun >= run + 2 && flashEndsAt(run + 2)
        var sum = 0f
        var count = 0
        for (r in run - 2..run + 2) {
            // Run 0 has no score, and the score of a cut measures the cut, not the shot that follows it.
            if (r == run || r <= lastCutRun || r > newestRun) continue
            val flash = if (r == run - 2) flashEarlier else if (r == run - 1) flashJustEnded else flashAfter
            if (flash) continue
            sum += scores[r and SCORE_MASK]
            count++
        }
        if (score * count < Tuning.CUT_NEIGHBOR_RATIO * sum) return false
        val lastAfter = min(run + 2, newestRun)
        if (lastAfter <= run) return false
        // After a flash, or something passing the lens, the picture is back to what it was a few runs earlier.
        // That holds for the frame where the flash ends too, so more than the one run before is compared.
        var persisted = Float.MAX_VALUE
        for (after in run + 1..lastAfter) {
            for (before in max(lastCutRun, run - RETURN_LOOKBACK) until run) {
                persisted = min(persisted, difference(images[after and IMAGE_MASK], images[before and IMAGE_MASK]))
            }
        }
        if (persisted < max(Tuning.CUT_MIN_SCORE, Tuning.CUT_PERSIST_FRACTION * score)) return false
        lastCutRun = run
        return true
    }

    /**
     * True when the two runs ending at [end] were a flash: both changed the picture, and after them it is back
     * to what it was. Their scores say nothing about how calm the shot is and would hide a cut next to them.
     */
    private fun flashEndsAt(end: Int): Boolean {
        val start = end - 1
        if (start <= lastCutRun) return false
        val on = scores[start and SCORE_MASK]
        val off = scores[end and SCORE_MASK]
        if (on < Tuning.CUT_MIN_SCORE || off < Tuning.CUT_MIN_SCORE) return false
        val returned = difference(images[end and IMAGE_MASK], images[(start - 1) and IMAGE_MASK])
        return returned < Tuning.CUT_PERSIST_FRACTION * min(on, off)
    }

    /** Mean absolute luma difference, minimized over small shifts so a pan or a jolt does not look like a cut. */
    private fun difference(a: ByteArray, b: ByteArray): Float {
        var best = Float.MAX_VALUE
        for (dy in -maxShiftY..maxShiftY) {
            val y0 = max(0, -dy)
            val y1 = min(gridHeight, gridHeight - dy)
            for (dx in -maxShiftX..maxShiftX) {
                val x0 = max(0, -dx)
                val x1 = min(gridWidth, gridWidth - dx)
                var sum = 0
                for (y in y0 until y1) {
                    var ia = (y + dy) * gridWidth + x0 + dx
                    var ib = y * gridWidth + x0
                    for (x in x0 until x1) {
                        sum += abs((a[ia++].toInt() and 0xFF) - (b[ib++].toInt() and 0xFF))
                    }
                }
                val mean = sum.toFloat() / ((x1 - x0) * (y1 - y0))
                if (mean < best) best = mean
            }
        }
        return best
    }

    private companion object {
        const val GRID_LONG_SIDE = 32
        const val CELL_SAMPLES = 4
        const val MAX_SHIFT = 3

        const val RETURN_LOOKBACK = 5

        // A candidate at run c is decided when run c + 2 arrives and needs the pictures back to run c - 5.
        const val IMAGE_HISTORY = 8
        const val IMAGE_MASK = IMAGE_HISTORY - 1
        const val SCORE_HISTORY = 8
        const val SCORE_MASK = SCORE_HISTORY - 1
    }
}
