package io.github.vikas0706.openblur.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** The gap between tiles as a fraction of a tile's side. */
private const val GAP = 0.25f

/** How many tiles the travelling highlight covers when the amount of work is unknown. */
private const val SWEEP_TILES = 4

/** How full tile [index] of [count] is, 0..1, for a [progress] of 0..1. Tiles fill one after another. */
internal fun tileFill(progress: Float, index: Int, count: Int): Float =
    (progress.coerceIn(0f, 1f) * count - index).coerceIn(0f, 1f)

/**
 * How lit tile [index] of [count] is, 0..1, at [phase] 0..1 of one pass of the travelling highlight.
 * A tile brightens as the head reaches it and fades over the next tiles; every tile is dark at phase 0
 * and 1, so the pass repeats without a jump.
 */
internal fun sweepFill(phase: Float, index: Int, count: Int): Float {
    val behind = phase.coerceIn(0f, 1f) * (count + SWEEP_TILES) - index
    return when {
        behind <= 0f || behind >= SWEEP_TILES -> 0f
        behind < 1f -> behind
        else -> 1f - (behind - 1f) / (SWEEP_TILES - 1)
    }
}

/**
 * The app's progress indicator: a row of square tiles that fill from the start for a [progress] of 0..1.
 * Pass null while the amount of work is unknown; a highlight then travels along the row, or every other
 * tile is lit when the system "Remove animations" setting is on.
 * [contentDescription] names the work for screen readers ("Scanning the video"); the percentage is added by them.
 */
@Composable
fun MosaicProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tileCount: Int = 12,
) {
    require(tileCount >= 2) { "MosaicProgress needs at least 2 tiles, got $tileCount" }
    val colors = OpenBlurTheme.colors
    // 0 / 0 from a caller's division arrives as NaN, which the progress semantics reject with an exception.
    val known: Float? = progress?.let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
    val shown: State<Float>? = if (known != null) {
        animateFloatAsState(
            targetValue = known,
            animationSpec = tween(durationMillis = 200, easing = LinearEasing),
            label = "progress",
        )
    } else {
        null
    }
    val sweep: State<Float>? = if (known == null && !OpenBlurTheme.reducedMotion) {
        rememberInfiniteTransition(label = "sweep").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing), RepeatMode.Restart),
            label = "sweep",
        )
    } else {
        null
    }
    Canvas(
        modifier = modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .aspectRatio(tileCount + (tileCount - 1) * GAP)
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .then(if (known != null) Modifier.progressSemantics(known) else Modifier.progressSemantics()),
    ) {
        val side = size.height
        val pitch = side * (1f + GAP)
        val corner = CornerRadius(minOf(4.dp.toPx(), side / 4))
        val stroke = 1.dp.toPx()
        val tile = Size(side, side)
        for (index in 0 until tileCount) {
            val fill = when {
                shown != null -> tileFill(shown.value, index, tileCount)
                sweep != null -> sweepFill(sweep.value, index, tileCount)
                else -> if (index % 2 == 0) 1f else 0f
            }
            val slot = if (layoutDirection == LayoutDirection.Rtl) tileCount - 1 - index else index
            val origin = Offset(slot * pitch, 0f)
            if (fill < 1f) {
                // An empty tile is outlined at 4.6:1 or better, so the length of the whole row stays visible.
                drawRoundRect(
                    color = colors.outlineStrong,
                    topLeft = origin + Offset(stroke / 2, stroke / 2),
                    size = Size(side - stroke, side - stroke),
                    cornerRadius = corner,
                    style = Stroke(stroke),
                )
            }
            if (fill > 0f) {
                drawRoundRect(colors.amber, origin, tile, corner, alpha = fill)
            }
        }
    }
}
