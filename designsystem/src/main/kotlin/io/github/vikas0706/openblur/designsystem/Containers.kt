package io.github.vikas0706.openblur.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val PanelTopShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)

/**
 * The raised container for controls at the bottom of a screen. Its background runs under the
 * navigation bar while the content stays clear of it, of side cutouts and of the keyboard.
 * Content is capped at 640 dp wide and centred, so controls do not stretch across a tablet.
 * Children are stacked 12 dp apart unless [verticalArrangement] says otherwise.
 */
@Composable
fun BottomPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(OpenBlurDimens.ScreenPadding),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(OpenBlurDimens.ItemGap),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = OpenBlurTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised, PanelTopShape)
            // Taps on the panel's empty areas must not reach the video preview behind it.
            .pointerInput(Unit) { }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        contentAlignment = Alignment.TopCenter,
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.text) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(contentPadding),
                verticalArrangement = verticalArrangement,
                content = content,
            )
        }
    }
}

/**
 * The bar at the top of a screen, clear of the status bar and side cutouts.
 * [onBack] adds the back arrow; put [IconAction] or [TextAction] items in [actions].
 */
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = OpenBlurTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconAction(
                icon = OpenBlurIcons.Back,
                contentDescription = stringResource(R.string.designsystem_back),
                onClick = onBack,
            )
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
                .semantics { heading() },
            color = colors.text,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        CompositionLocalProvider(LocalContentColor provides colors.text) {
            actions()
        }
    }
}

/**
 * Asks before something that cannot be taken back. Back, Escape and a tap outside call [onDismiss].
 * [destructive] marks a confirm action that deletes or discards.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = OpenBlurTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            SecondaryButton(text = confirmLabel, onClick = onConfirm, destructive = destructive)
        },
        dismissButton = {
            TextAction(text = dismissLabel, onClick = onDismiss)
        },
        // The scrim leaves surfaceRaised at 1.15:1 against the screen, so a hairline marks the dialog's edge.
        modifier = Modifier.border(1.dp, colors.outline, OpenBlurShapes.Panel),
        title = {
            Text(text = title, modifier = Modifier.semantics { heading() })
        },
        text = {
            // Scrolls so a long message at a large font size is never cut off.
            Text(text = text, modifier = Modifier.verticalScroll(rememberScrollState()))
        },
        shape = OpenBlurShapes.Panel,
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        textContentColor = colors.textMuted,
        tonalElevation = 0.dp,
    )
}
