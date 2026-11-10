package io.github.vikas0706.openblur.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/** The one main action of a screen. Amber, so use it once per screen at most. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val colors = OpenBlurTheme.colors
    ButtonFrame(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        fill = if (enabled) colors.amber else colors.surfaceHigh,
        border = null,
        content = if (enabled) colors.onAmber else colors.textDisabled,
        textStyle = MaterialTheme.typography.titleMedium,
        indication = AmberIndication,
    )
}

/** An action that matters but is not the main one. [destructive] marks deleting or discarding. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    val colors = OpenBlurTheme.colors
    val content = when {
        !enabled -> colors.textDisabled
        destructive -> colors.danger
        else -> colors.text
    }
    val border = when {
        !enabled -> colors.outline
        destructive -> colors.danger
        else -> colors.outlineStrong
    }
    ButtonFrame(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        fill = Color.Transparent,
        border = BorderStroke(1.dp, border),
        content = content,
        textStyle = MaterialTheme.typography.labelLarge,
        indication = ControlIndication,
    )
}

/** The quietest action: underlined text, because colour is not available to mark it as tappable. */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = OpenBlurTheme.colors
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = OpenBlurDimens.MinTouchTarget, minHeight = OpenBlurDimens.MinTouchTarget)
            .clickable(
                interactionSource = null,
                indication = ControlIndication,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) colors.text else colors.textDisabled,
            style = MaterialTheme.typography.labelLarge,
            textDecoration = if (enabled) TextDecoration.Underline else null,
            textAlign = TextAlign.Center,
        )
    }
}

/** An icon-only action. [contentDescription] is what a screen reader says, for example "Step back one frame". */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = OpenBlurTheme.colors.text,
) {
    Box(
        modifier = modifier
            .size(OpenBlurDimens.MinTouchTarget)
            .clickable(
                interactionSource = null,
                indication = ControlIndication,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (enabled) tint else OpenBlurTheme.colors.textDisabled,
        )
    }
}

@Composable
private fun ButtonFrame(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    icon: ImageVector?,
    fill: Color,
    border: BorderStroke?,
    content: Color,
    textStyle: TextStyle,
    indication: Indication,
) {
    Row(
        modifier = modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = OpenBlurDimens.MinTouchTarget)
            .background(fill, OpenBlurShapes.Control)
            .then(if (border != null) Modifier.border(border, OpenBlurShapes.Control) else Modifier)
            .clickable(
                interactionSource = null,
                indication = indication,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(textScaled(20)), tint = content)
        }
        // Long labels wrap instead of clipping when the system font size is raised.
        Text(
            text = text,
            modifier = Modifier.weight(1f, fill = false),
            color = content,
            style = textStyle,
            textAlign = TextAlign.Center,
        )
    }
}
