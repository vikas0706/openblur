package io.github.vikas0706.openblur.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp

/** Whether a person's face is blurred in the export. [Mixed] is a person blurred in some shots only. */
enum class BlurState { Blurred, Visible, Mixed }

private val RingSpace = 4.dp
private val ThumbnailIndication = StateIndication(RoundedCornerShape(12.dp))

/**
 * A face picture with its blur state as a border and a corner mark: amber and mosaic when blurred,
 * grey and an eye when visible, half amber and a half-filled tile when mixed.
 * A null [image] shows a face outline. [contentDescription] names the person; screen readers add the state.
 * [size] includes 4 dp on each side kept free for the [selected] ring. Without [onClick] it only displays.
 */
@Composable
fun FaceThumbnail(
    image: ImageBitmap?,
    blurState: BlurState,
    contentDescription: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 72.dp,
    onClick: (() -> Unit)? = null,
) {
    val colors = OpenBlurTheme.colors
    val stateText = stringResource(
        when (blurState) {
            BlurState.Blurred -> R.string.designsystem_state_blurred
            BlurState.Visible -> R.string.designsystem_state_visible
            BlurState.Mixed -> R.string.designsystem_state_mixed
        },
    )
    val ring = if (enabled) colors.text else colors.textDisabled
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = ThumbnailIndication,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                stateDescription = stateText
                this.selected = selected
            }
            .drawBehind {
                if (selected) {
                    val stroke = 2.dp.toPx()
                    drawRoundRect(
                        color = ring,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(this.size.width - stroke, this.size.height - stroke),
                        cornerRadius = CornerRadius(12.dp.toPx() - stroke / 2),
                        style = Stroke(stroke),
                    )
                }
            },
    ) {
        Box(
            modifier = Modifier
                .padding(RingSpace)
                .fillMaxSize()
                .clip(OpenBlurShapes.Control)
                .background(colors.surfaceHigh)
                .drawWithContent {
                    drawContent()
                    drawStateBorder(blurState, colors)
                },
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = if (enabled) 1f else 0.4f,
                )
            } else {
                Icon(
                    imageVector = OpenBlurIcons.Face,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.5f),
                    tint = if (enabled) colors.textMuted else colors.textDisabled,
                )
            }
            StateMark(
                blurState = blurState,
                size = (size * 0.28f).coerceIn(16.dp, 24.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            )
        }
    }
}

private fun DrawScope.drawStateBorder(blurState: BlurState, colors: OpenBlurColors) {
    val corner = 8.dp.toPx()
    fun border(color: Color, width: Float) = drawRoundRect(
        color = color,
        topLeft = Offset(width / 2, width / 2),
        size = Size(size.width - width, size.height - width),
        cornerRadius = CornerRadius(corner - width / 2),
        style = Stroke(width),
    )
    when (blurState) {
        BlurState.Blurred -> border(colors.amber, 2.dp.toPx())
        BlurState.Visible -> border(colors.outlineStrong, 1.dp.toPx())
        BlurState.Mixed -> {
            border(colors.outlineStrong, 2.dp.toPx())
            val half = size.width / 2
            val rtl = layoutDirection == LayoutDirection.Rtl
            clipRect(left = if (rtl) half else 0f, right = if (rtl) size.width else half) {
                border(colors.amber, 2.dp.toPx())
            }
        }
    }
}

/** The corner tile that names the state by its glyph, so the border colour is never the only sign. */
@Composable
private fun StateMark(blurState: BlurState, size: Dp, modifier: Modifier) {
    val colors = OpenBlurTheme.colors
    val visible = blurState == BlurState.Visible
    Box(
        modifier = modifier
            .size(size)
            .background(if (visible) colors.surface else colors.amber, OpenBlurShapes.Tile)
            .then(if (visible) Modifier.border(1.dp, colors.outlineStrong, OpenBlurShapes.Tile) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = when (blurState) {
                BlurState.Blurred -> OpenBlurIcons.Blurred
                BlurState.Visible -> OpenBlurIcons.Visible
                BlurState.Mixed -> OpenBlurIcons.PartlyBlurred
            },
            contentDescription = null,
            modifier = Modifier.size(size * 0.72f),
            tint = if (visible) colors.text else colors.onAmber,
        )
    }
}
