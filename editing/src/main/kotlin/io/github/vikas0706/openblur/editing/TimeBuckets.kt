package io.github.vikas0706.openblur.editing

/**
 * Time cut into equal buckets, each listing in ascending order the closed intervals that touch it.
 * A lookup reads one bucket and still compares each listed interval, since a bucket is wider than an instant.
 */
internal class TimeBuckets(startsUs: LongArray, endsUs: LongArray) {
    val firstUs: Long
    val lastUs: Long

    /** Bucket b owns items[offsets[b]] until items[offsets[b + 1]]. */
    val offsets: IntArray
    val items: IntArray

    private val shift: Int
    private val lastBucket: Int

    init {
        val n = startsUs.size
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        for (i in 0 until n) {
            if (startsUs[i] < first) first = startsUs[i]
            if (endsUs[i] > last) last = endsUs[i]
        }
        firstUs = first
        lastUs = last

        val range = last - first
        var width = 0
        if (n == 0 || range < 0) {
            // No intervals, or a span too wide for a Long: one bucket holds everything.
            width = 63
        } else {
            val bucketLimit = (Integer.highestOneBit(n).toLong() * 4).coerceIn(MIN_BUCKETS, MAX_BUCKETS)
            while ((range ushr width) >= bucketLimit) width++
            // A long interval is listed in every bucket it crosses, so buckets widen until the lists fit the budget.
            val budget = n * ENTRIES_PER_INTERVAL + ENTRY_SLACK
            while (entryCount(startsUs, endsUs, first, width) > budget) width++
        }
        shift = width
        lastBucket = if (n == 0 || range < 0) 0 else (range ushr width).toInt()

        val counts = IntArray(lastBucket + 2)
        for (i in 0 until n) {
            for (b in bucketOf(startsUs[i])..bucketOf(endsUs[i])) counts[b + 1]++
        }
        for (b in 1 until counts.size) counts[b] += counts[b - 1]
        offsets = counts
        val filled = counts.copyOf(lastBucket + 1)
        val entries = IntArray(counts[lastBucket + 1])
        for (i in 0 until n) {
            for (b in bucketOf(startsUs[i])..bucketOf(endsUs[i])) entries[filled[b]++] = i
        }
        items = entries
    }

    /** Valid for times inside [firstUs]..[lastUs]. The clamp only matters when the span overflowed. */
    fun bucketOf(timeUs: Long): Int = minOf((timeUs - firstUs) ushr shift, lastBucket.toLong()).toInt()

    private fun entryCount(startsUs: LongArray, endsUs: LongArray, first: Long, width: Int): Long {
        var total = 0L
        for (i in startsUs.indices) {
            total += ((endsUs[i] - first) ushr width) - ((startsUs[i] - first) ushr width) + 1
        }
        return total
    }

    private companion object {
        const val MIN_BUCKETS = 16L
        const val MAX_BUCKETS = 65_536L
        const val ENTRIES_PER_INTERVAL = 8L
        const val ENTRY_SLACK = 64L
    }
}
