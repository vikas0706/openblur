package io.github.vikas0706.openblur.editing

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeBucketsTest {
    /** The lookup the timeline does: read one bucket, then keep the intervals that contain the time. */
    private fun lookup(index: TimeBuckets, startsUs: LongArray, endsUs: LongArray, timeUs: Long): List<Int> {
        if (timeUs < index.firstUs || timeUs > index.lastUs) return emptyList()
        val bucket = index.bucketOf(timeUs)
        return (index.offsets[bucket] until index.offsets[bucket + 1])
            .map { index.items[it] }
            .filter { timeUs >= startsUs[it] && timeUs <= endsUs[it] }
    }

    private fun bruteForce(startsUs: LongArray, endsUs: LongArray, timeUs: Long): List<Int> =
        startsUs.indices.filter { timeUs >= startsUs[it] && timeUs <= endsUs[it] }

    @Test
    fun anEmptyIndexContainsNoTime() {
        val index = TimeBuckets(LongArray(0), LongArray(0))

        for (timeUs in longArrayOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
            assertTrue(timeUs < index.firstUs || timeUs > index.lastUs)
        }
        assertEquals(0, index.items.size)
    }

    @Test
    fun aSingleInstantIsFound() {
        val starts = longArrayOf(1_000L)
        val ends = longArrayOf(1_000L)
        val index = TimeBuckets(starts, ends)

        assertEquals(listOf(0), lookup(index, starts, ends, 1_000L))
        assertEquals(emptyList<Int>(), lookup(index, starts, ends, 999L))
        assertEquals(emptyList<Int>(), lookup(index, starts, ends, 1_001L))
    }

    @Test
    fun lookupsAgreeWithAScanOfEveryInterval() {
        val random = Random(11)
        for (round in 0 until 60) {
            val n = random.nextInt(1, 400)
            val horizon = random.nextLong(1L, 4_000_000_000L)
            val longest = random.nextLong(1L, horizon + 1)
            val starts = LongArray(n) { random.nextLong(-horizon, horizon) }
            val ends = LongArray(n) { starts[it] + random.nextLong(0L, longest) }
            val index = TimeBuckets(starts, ends)

            val probes = ArrayList<Long>()
            repeat(200) { probes += random.nextLong(-horizon - 10, horizon + longest + 10) }
            for (i in 0 until minOf(n, 40)) {
                probes += listOf(starts[i] - 1, starts[i], starts[i] + 1, ends[i] - 1, ends[i], ends[i] + 1)
            }
            for (timeUs in probes) {
                assertEquals("round $round at $timeUs", bruteForce(starts, ends, timeUs), lookup(index, starts, ends, timeUs))
            }
        }
    }

    @Test
    fun eachBucketListsIntervalsInAscendingOrder() {
        val random = Random(5)
        val starts = LongArray(2_000) { random.nextLong(0L, 600_000_000L) }
        val ends = LongArray(2_000) { starts[it] + random.nextLong(0L, 30_000_000L) }
        val index = TimeBuckets(starts, ends)

        for (bucket in 0 until index.offsets.size - 1) {
            for (at in index.offsets[bucket] + 1 until index.offsets[bucket + 1]) {
                assertTrue(index.items[at - 1] < index.items[at])
            }
        }
    }

    @Test
    fun intervalsThatAllSpanTheWholeRangeDoNotMultiplyTheIndex() {
        val n = 5_000
        val starts = LongArray(n) { it.toLong() }
        val ends = LongArray(n) { 3_600_000_000L - it }
        val index = TimeBuckets(starts, ends)

        assertTrue("index holds ${index.items.size} entries", index.items.size <= n * 8 + 64)
        assertEquals(n, lookup(index, starts, ends, 1_800_000_000L).size)
        assertEquals(listOf(0), lookup(index, starts, ends, 0L))
        assertEquals(listOf(0, 1, 2), lookup(index, starts, ends, 3_600_000_000L - 2))
    }

    @Test
    fun manyShortIntervalsNextToAFewLongOnesStayCheapToLookUp() {
        val random = Random(3)
        val n = 5_000
        val starts = LongArray(n) { if (it < 10) 0L else random.nextLong(0L, 3_600_000_000L) }
        val ends = LongArray(n) { if (it < 10) 3_600_000_000L else starts[it] + 200_000L }
        val index = TimeBuckets(starts, ends)

        var worst = 0
        for (bucket in 0 until index.offsets.size - 1) {
            worst = maxOf(worst, index.offsets[bucket + 1] - index.offsets[bucket])
        }
        assertTrue("index holds ${index.items.size} entries", index.items.size <= n * 8 + 64)
        assertTrue("largest bucket holds $worst of $n intervals", worst < 100)
    }

    @Test
    fun aSpanTooWideForALongStillFindsEverything() {
        val starts = longArrayOf(Long.MIN_VALUE + 1, 0L, 100L, -50L)
        val ends = longArrayOf(5L, 50L, Long.MAX_VALUE, -50L)
        val index = TimeBuckets(starts, ends)

        for (timeUs in longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -50L, -1L, 0L, 5L, 6L, 50L, 99L, 100L, Long.MAX_VALUE)) {
            assertEquals("at $timeUs", bruteForce(starts, ends, timeUs), lookup(index, starts, ends, timeUs))
        }
    }

    @Test
    fun identicalIntervalsShareOneBucket() {
        val starts = LongArray(50) { 7L }
        val ends = LongArray(50) { 7L }
        val index = TimeBuckets(starts, ends)

        assertEquals(List(50) { it }, lookup(index, starts, ends, 7L))
        assertEquals(50, index.items.size)
    }
}
