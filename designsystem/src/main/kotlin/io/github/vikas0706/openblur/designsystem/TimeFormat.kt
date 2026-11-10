package io.github.vikas0706.openblur.designsystem

internal class TimeParts(val hours: Long, val minutes: Int, val seconds: Int)

/** Whole seconds only, rounded down, so a playhead at 0:59.9 does not read 1:00 before it gets there. */
internal fun timeParts(timeUs: Long): TimeParts {
    val totalSeconds = if (timeUs <= 0L) 0L else timeUs / 1_000_000L
    return TimeParts(
        hours = totalSeconds / 3600L,
        minutes = ((totalSeconds % 3600L) / 60L).toInt(),
        seconds = (totalSeconds % 60L).toInt(),
    )
}

/**
 * Microseconds as `m:ss`, or `h:mm:ss` from one hour up. Fractions of a second are dropped and
 * negative times read as zero. Digits are always ASCII so the width matches the tabular figures.
 */
fun formatTimeUs(timeUs: Long): String {
    val parts = timeParts(timeUs)
    val seconds = twoDigits(parts.seconds)
    return if (parts.hours > 0L) {
        "${parts.hours}:${twoDigits(parts.minutes)}:$seconds"
    } else {
        "${parts.minutes}:$seconds"
    }
}

private fun twoDigits(value: Int): String = if (value < 10) "0$value" else value.toString()
