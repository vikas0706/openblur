// Material's Slider only takes a custom thumb and track through its experimental overload.
@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.vikas0706.openblur.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private val ThumbSize = 20.dp
private val TrackHeight = 6.dp

/**
 * A slider under its label, with the current value in words at the end of the label row.
 * [valueText] is what the user reads and what a screen reader says, for example "70%".
 * [steps] is the number of stops strictly between the two ends; 0 means continuous.
 * Set [controlsBlur] when the slider changes how strong or how large a blur is.
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueText: String,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    controlsBlur: Boolean = false,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = OpenBlurTheme.colors
    val on = onStateColor(controlsBlur, enabled)
    val tickOnActive = onStateContentColor(controlsBlur, enabled)
    val inactiveBorder = if (enabled) colors.outlineStrong else colors.outline
    val interactions = remember { MutableInteractionSource() }
    Column(modifier = modifier) {
        // The slider below carries the label and value for screen readers, so this row is not read twice.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = if (enabled) colors.textMuted else colors.textDisabled,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = valueText,
                color = if (enabled) colors.text else colors.textDisabled,
                style = MaterialTheme.typography.bodyMedium.tabularFigures(),
            )
        }
        Slider(
            // NaN would throw inside the slider's range semantics; show the start of the range instead.
            value = if (value.isNaN()) valueRange.start else value,
            onValueChange = onValueChange,
            // Merging here makes the 48 dp touch area the bounds screen readers see; Material's own
            // semantics sit inside it and report only the 20 dp thumb height.
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = valueText
                },
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            interactionSource = interactions,
            steps = steps,
            thumb = { SliderThumb(interactions, on) },
            track = { state -> SliderTrack(state, on, tickOnActive, colors.surfaceHigh, inactiveBorder) },
            valueRange = valueRange,
        )
    }
}

@Composable
private fun SliderThumb(interactions: InteractionSource, color: Color) {
    val focused by interactions.collectIsFocusedAsState()
    val dragged by interactions.collectIsDraggedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val focusRing = remember { FocusRing() }
    Box(
        modifier = Modifier
            .size(ThumbSize)
            .drawBehind {
                if (dragged || hovered) {
                    val halo = 6.dp.toPx()
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(-halo, -halo),
                        size = Size(size.width + 2 * halo, size.height + 2 * halo),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                        alpha = if (dragged) 0.24f else 0.12f,
                    )
                }
                drawRoundRect(color, cornerRadius = CornerRadius(4.dp.toPx()))
                if (focused) {
                    inset(-6.dp.toPx()) { with(focusRing) { draw(OpenBlurShapes.Control) } }
                }
            },
    )
}

@Composable
private fun SliderTrack(state: SliderState, active: Color, tickOnActive: Color, inactive: Color, inactiveBorder: Color) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(TrackHeight),
    ) {
        val range = state.valueRange
        val span = range.endInclusive - range.start
        val fraction = if (span > 0f) ((state.value - range.start) / span).coerceIn(0f, 1f) else 0f
        val corner = CornerRadius(2.dp.toPx())
        // The slider lays this track out between the two thumb centres, so the thumb sits at width * fraction.
        val thumbX = size.width * fraction
        val gap = ThumbSize.toPx() / 2 + 4.dp.toPx()
        scale(scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, scaleY = 1f) {
            val activeEnd = thumbX - gap
            if (activeEnd > 0f) {
                drawRoundRect(active, size = Size(activeEnd, size.height), cornerRadius = corner)
            }
            val inactiveStart = thumbX + gap
            if (inactiveStart < size.width) {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = inactive,
                    topLeft = Offset(inactiveStart, 0f),
                    size = Size(size.width - inactiveStart, size.height),
                    cornerRadius = corner,
                )
                // surfaceHigh alone is 1.2:1 against a panel; the border is what marks where the range ends.
                drawRoundRect(
                    color = inactiveBorder,
                    topLeft = Offset(inactiveStart + stroke / 2, stroke / 2),
                    size = Size(size.width - inactiveStart - stroke, size.height - stroke),
                    cornerRadius = corner,
                    style = Stroke(stroke),
                )
            }
            if (state.steps > 0) {
                val tick = 2.dp.toPx()
                val edge = size.height / 2
                for (i in 0..state.steps + 1) {
                    val x = size.width * i / (state.steps + 1)
                    if (abs(x - thumbX) < gap) continue
                    drawRect(
                        color = if (x < thumbX) tickOnActive else inactiveBorder,
                        topLeft = Offset(x.coerceIn(edge, size.width - edge) - tick / 2, (size.height - tick) / 2),
                        size = Size(tick, tick),
                    )
                }
            }
        }
    }
}
