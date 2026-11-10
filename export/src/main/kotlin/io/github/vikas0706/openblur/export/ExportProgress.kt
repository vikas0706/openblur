package io.github.vikas0706.openblur.export

import io.github.vikas0706.openblur.api.ExportPhase

/** Maps the progress of each phase onto the single 0..1 value the UI and the notification show. */
internal object ExportProgress {
    /** Share of the bar given to encoding; the rest covers copying the file into the shared Movies folder. */
    const val ENCODE_SHARE = 0.96f

    // Mirror Transformer.PROGRESS_STATE_*; kept here so this file needs no Media3 class at test time.
    const val STATE_NOT_STARTED = 0
    const val STATE_WAITING = 1
    const val STATE_AVAILABLE = 2
    const val STATE_UNAVAILABLE = 3

    /** Fraction of the encode that is done, or null while the Transformer has not reported any progress yet. */
    fun encodeFraction(progressState: Int, percent: Int): Float? = when (progressState) {
        STATE_AVAILABLE -> (percent / 100f).coerceIn(0f, 1f)
        else -> null
    }

    fun overall(phase: ExportPhase, phaseFraction: Float): Float {
        val fraction = if (phaseFraction.isNaN()) 0f else phaseFraction.coerceIn(0f, 1f)
        return when (phase) {
            ExportPhase.PREPARING -> 0f
            ExportPhase.ENCODING -> fraction * ENCODE_SHARE
            ExportPhase.SAVING -> ENCODE_SHARE + fraction * (1f - ENCODE_SHARE)
        }
    }

    /** Progress never moves backwards inside one attempt, even if a poll reports a smaller number. */
    fun advance(previous: Float, next: Float): Float = maxOf(previous, next).coerceIn(0f, 1f)

    fun percent(overall: Float): Int = (overall.coerceIn(0f, 1f) * 100f).toInt()
}
