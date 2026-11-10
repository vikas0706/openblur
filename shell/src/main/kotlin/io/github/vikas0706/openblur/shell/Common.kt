package io.github.vikas0706.openblur.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.vikas0706.openblur.designsystem.OpenBlurDimens
import io.github.vikas0706.openblur.designsystem.OpenBlurShapes
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme
import io.github.vikas0706.openblur.designsystem.TopBar

/** Widest a column of text and controls gets, so lines stay readable on tablets and in landscape. */
internal val ContentMaxWidth: Dp = 640.dp

/**
 * A screen with a top bar and one scrolling column of content, clear of system bars and cutouts.
 * Scrolling keeps every control reachable in landscape, in split-screen and at large font sizes.
 */
@Composable
internal fun ScreenFrame(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(OpenBlurTheme.colors.surface)) {
        TopBar(title = title, onBack = onBack, actions = actions)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(OpenBlurDimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap),
                content = content,
            )
        }
    }
}

/** Stops the display from sleeping while this is on screen, for work the user is waiting on. */
@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
internal fun BodyText(text: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    Text(
        text = text,
        modifier = modifier,
        color = if (muted) OpenBlurTheme.colors.textMuted else OpenBlurTheme.colors.text,
        style = if (muted) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
    )
}

/** The mosaic motif at rest: three tiles, one of them amber. Decoration only, hidden from screen readers. */
@Composable
internal fun MosaicMark(modifier: Modifier = Modifier) {
    val colors = OpenBlurTheme.colors
    Row(
        modifier = modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(16.dp).background(colors.surfaceHigh, OpenBlurShapes.Tile))
        Box(Modifier.size(16.dp).background(colors.amber, OpenBlurShapes.Tile))
        Box(Modifier.size(16.dp).background(colors.surfaceHigh, OpenBlurShapes.Tile))
    }
}
