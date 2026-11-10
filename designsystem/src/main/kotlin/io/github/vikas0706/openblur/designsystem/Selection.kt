package io.github.vikas0706.openblur.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** The colour of a control's "on" state: amber only when the control changes blur. */
internal fun onStateColor(controlsBlur: Boolean, enabled: Boolean): Color {
    val colors = OpenBlurTheme.colors
    return when {
        !enabled -> colors.textDisabled
        controlsBlur -> colors.amber
        else -> colors.text
    }
}

/** The colour of a glyph drawn on top of [onStateColor]. */
internal fun onStateContentColor(controlsBlur: Boolean, enabled: Boolean): Color {
    val colors = OpenBlurTheme.colors
    return if (controlsBlur && enabled) colors.onAmber else colors.surface
}

/**
 * An option that is on or off on its own. The tile at the start fills and shows a check when selected,
 * so the state never rests on colour. Set [controlsBlur] when the option changes how or what is blurred.
 */
@Composable
fun ToggleChip(
    text: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    controlsBlur: Boolean = false,
) {
    val colors = OpenBlurTheme.colors
    val on = onStateColor(controlsBlur, enabled)
    val interactions = remember { MutableInteractionSource() }
    val border = when {
        selected -> on
        enabled -> colors.outlineStrong
        else -> colors.outline
    }
    // The chip draws 40 dp tall; the box around it is the 48 dp touch target.
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = OpenBlurDimens.MinTouchTarget)
            .toggleable(
                value = selected,
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onSelectedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 40.dp)
                .background(if (selected) colors.surfaceHigh else Color.Transparent, OpenBlurShapes.Control)
                .border(1.dp, border, OpenBlurShapes.Control)
                .indication(interactions, ControlIndication)
                .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(textScaled(18))
                    .background(if (selected) on else Color.Transparent, OpenBlurShapes.Tile)
                    .border(1.5.dp, border, OpenBlurShapes.Tile),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = OpenBlurIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(textScaled(14)),
                        tint = onStateContentColor(controlsBlur, enabled),
                    )
                }
            }
            Text(
                text = text,
                color = if (enabled) colors.text else colors.textDisabled,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * One choice out of two to four. The selected option gets a frame and a heavier label.
 * Set [controlsBlur] when the choice changes how or what is blurred.
 */
@Composable
fun SegmentedChoice(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    controlsBlur: Boolean = false,
) {
    require(options.size in 2..4) { "SegmentedChoice takes 2 to 4 options, got ${options.size}" }
    val colors = OpenBlurTheme.colors
    val on = onStateColor(controlsBlur, enabled)
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .selectableGroup()
            .border(1.dp, if (enabled) colors.outlineStrong else colors.outline, OpenBlurShapes.Control),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .defaultMinSize(minHeight = OpenBlurDimens.MinTouchTarget)
                    .drawBehind {
                        if (selected) {
                            val gap = 3.dp.toPx()
                            val stroke = 1.5.dp.toPx()
                            val corner = CornerRadius(5.dp.toPx())
                            val inner = Size(size.width - 2 * gap, size.height - 2 * gap)
                            drawRoundRect(colors.surfaceHigh, Offset(gap, gap), inner, corner)
                            drawRoundRect(
                                color = on,
                                topLeft = Offset(gap + stroke / 2, gap + stroke / 2),
                                size = Size(inner.width - stroke, inner.height - stroke),
                                cornerRadius = corner,
                                style = Stroke(stroke),
                            )
                        }
                    }
                    .selectable(
                        selected = selected,
                        interactionSource = null,
                        indication = ControlIndication,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = when {
                        !enabled -> colors.textDisabled
                        selected -> colors.text
                        else -> colors.textMuted
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * A setting that is on or off, with the whole row as the touch target.
 * Set [controlsBlur] when the setting changes how or what is blurred.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    controlsBlur: Boolean = false,
) {
    val colors = OpenBlurTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = null,
                indication = RowIndication,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .defaultMinSize(minHeight = 56.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) colors.text else colors.textDisabled,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (description != null) {
                Text(
                    text = description,
                    color = if (enabled) colors.textMuted else colors.textDisabled,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        SwitchMark(checked = checked, enabled = enabled, controlsBlur = controlsBlur)
    }
}

/** The switch drawing: a square thumb, like a mosaic tile, sliding in a rounded track. */
@Composable
private fun SwitchMark(checked: Boolean, enabled: Boolean, controlsBlur: Boolean) {
    val colors = OpenBlurTheme.colors
    val on = onStateColor(controlsBlur, enabled)
    val onThumb = onStateContentColor(controlsBlur, enabled)
    val offBorder = if (enabled) colors.outlineStrong else colors.outline
    val offThumb = if (enabled) colors.outlineStrong else colors.textDisabled
    val position by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "switch",
    )
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .drawBehind {
                val corner = CornerRadius(8.dp.toPx())
                drawRoundRect(lerp(colors.surfaceHigh, on, position), cornerRadius = corner)
                if (position < 1f) {
                    val stroke = 1.dp.toPx()
                    drawRoundRect(
                        color = offBorder,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = corner,
                        alpha = 1f - position,
                        style = Stroke(stroke),
                    )
                }
                val thumb = 18.dp.toPx()
                val inset = (size.height - thumb) / 2
                val travel = size.width - thumb - 2 * inset
                val fromStart = inset + travel * position
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - thumb - fromStart else fromStart
                drawRoundRect(
                    color = lerp(offThumb, onThumb, position),
                    topLeft = Offset(x, inset),
                    size = Size(thumb, thumb),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            },
    )
}
