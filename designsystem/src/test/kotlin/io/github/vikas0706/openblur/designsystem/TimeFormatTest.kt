package io.github.vikas0706.openblur.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    private val second = 1_000_000L

    @Test
    fun zeroIsZeroMinutes() {
        assertEquals("0:00", formatTimeUs(0L))
    }

    @Test
    fun fractionsOfASecondAreDropped() {
        assertEquals("0:00", formatTimeUs(999_999L))
        assertEquals("0:01", formatTimeUs(1_000_000L))
        assertEquals("0:01", formatTimeUs(1_999_999L))
        assertEquals("0:59", formatTimeUs(59_999_999L))
    }

    @Test
    fun secondsArePaddedMinutesAreNot() {
        assertEquals("0:05", formatTimeUs(5 * second))
        assertEquals("1:00", formatTimeUs(60 * second))
        assertEquals("1:05", formatTimeUs(65 * second))
        assertEquals("9:59", formatTimeUs(599 * second))
        assertEquals("10:00", formatTimeUs(600 * second))
        assertEquals("59:59", formatTimeUs(3600 * second - 1))
    }

    @Test
    fun fromOneHourMinutesArePaddedToo() {
        assertEquals("1:00:00", formatTimeUs(3600 * second))
        assertEquals("1:02:05", formatTimeUs(3725 * second))
        assertEquals("1:59:59", formatTimeUs(7200 * second - 1))
        assertEquals("10:00:00", formatTimeUs(36_000 * second))
        assertEquals("100:00:00", formatTimeUs(360_000 * second))
    }

    @Test
    fun negativeTimesReadAsZero() {
        assertEquals("0:00", formatTimeUs(-1L))
        assertEquals("0:00", formatTimeUs(-65 * second))
        assertEquals("0:00", formatTimeUs(Long.MIN_VALUE))
    }

    @Test
    fun theLargestTimeDoesNotOverflow() {
        assertEquals("2562047788:00:54", formatTimeUs(Long.MAX_VALUE))
    }

    @Test
    fun partsMatchTheFormattedText() {
        val parts = timeParts(3725 * second + 999_999L)
        assertEquals(1L, parts.hours)
        assertEquals(2, parts.minutes)
        assertEquals(5, parts.seconds)
    }
}
