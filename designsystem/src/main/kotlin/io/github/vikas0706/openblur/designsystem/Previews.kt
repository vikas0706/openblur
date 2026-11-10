package io.github.vikas0706.openblur.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

private const val SURFACE = 0xFF0E0F11

@Composable
private fun PreviewColumn(raised: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    OpenBlurTheme {
        Column(
            modifier = Modifier
                .background(if (raised) OpenBlurTheme.colors.surfaceRaised else OpenBlurTheme.colors.surface)
                .padding(OpenBlurDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap),
            content = content,
        )
    }
}

/** Draws the keyboard-focus ring over [content]; previews cannot press Tab. */
@Composable
private fun Focused(shape: Shape = OpenBlurShapes.Control, content: @Composable () -> Unit) {
    val ring = remember { FocusRing() }
    Box(
        modifier = Modifier.drawWithContent {
            drawContent()
            with(ring) { draw(shape) }
        },
    ) { content() }
}

/** Draws the pressed overlay over [content]; previews cannot hold a finger down. */
@Composable
private fun Pressed(overlay: Color, alpha: Float, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.drawWithContent {
            drawContent()
            drawOutline(OpenBlurShapes.Control.createOutline(size, layoutDirection, this), overlay, alpha)
        },
    ) { content() }
}

/** A stand-in picture: two flat tones, clearly not a real face. */
private fun sampleImage(): ImageBitmap {
    val image = ImageBitmap(96, 96)
    val canvas = Canvas(image)
    canvas.drawRect(Rect(0f, 0f, 96f, 96f), Paint().apply { color = Color(0xFF5B6470) })
    canvas.drawCircle(Offset(48f, 44f), 26f, Paint().apply { color = Color(0xFFB9A48F) })
    return image
}

@Preview(name = "Buttons", showBackground = true, backgroundColor = SURFACE)
@Composable
private fun ButtonsPreview() {
    PreviewColumn {
        PrimaryButton(text = "Save a copy", onClick = {}, modifier = Modifier.fillMaxWidth())
        PrimaryButton(text = "Choose a video", onClick = {}, icon = OpenBlurIcons.Video)
        PrimaryButton(text = "Save a copy", onClick = {}, enabled = false)
        SecondaryButton(text = "Scan again", onClick = {}, icon = OpenBlurIcons.Rescan)
        SecondaryButton(text = "Delete scan", onClick = {}, destructive = true)
        SecondaryButton(text = "Scan again", onClick = {}, enabled = false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction(text = "Choose another video", onClick = {})
            TextAction(text = "Cancel", onClick = {}, enabled = false)
        }
        Row {
            IconAction(icon = OpenBlurIcons.Undo, contentDescription = "Undo", onClick = {})
            IconAction(icon = OpenBlurIcons.Redo, contentDescription = "Redo", onClick = {}, enabled = false)
            IconAction(
                icon = OpenBlurIcons.Blurred,
                contentDescription = "Blur this person",
                onClick = {},
                tint = OpenBlurTheme.colors.amber,
            )
        }
    }
}

@Preview(name = "Pressed and focused", showBackground = true, backgroundColor = SURFACE)
@Composable
private fun InteractionStatesPreview() {
    val colors = OpenBlurTheme.colors
    PreviewColumn {
        Pressed(colors.onAmber, 0.16f) { PrimaryButton(text = "Save a copy", onClick = {}) }
        Focused { PrimaryButton(text = "Save a copy", onClick = {}) }
        Pressed(colors.text, 0.08f) { SecondaryButton(text = "Scan again", onClick = {}) }
        Focused { SecondaryButton(text = "Scan again", onClick = {}) }
        Focused { TextAction(text = "Cancel", onClick = {}) }
        Focused { IconAction(icon = OpenBlurIcons.Play, contentDescription = "Play", onClick = {}) }
        Focused(OpenBlurShapes.Panel) {
            FaceThumbnail(image = null, blurState = BlurState.Blurred, contentDescription = "Person 1", selected = true)
        }
    }
}

@Preview(name = "ToggleChip", showBackground = true, backgroundColor = SURFACE)
@Composable
private fun ToggleChipPreview() {
    PreviewColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(text = "Remove location", selected = false, onSelectedChange = {})
            ToggleChip(text = "Remove location", selected = true, onSelectedChange = {})
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(text = "Blur everyone", selected = true, onSelectedChange = {}, controlsBlur = true)
            ToggleChip(text = "Blur everyone", selected = true, onSelectedChange = {}, enabled = false)
            ToggleChip(text = "Blur everyone", selected = false, onSelectedChange = {}, enabled = false)
        }
    }
}

@Preview(name = "SegmentedChoice", showBackground = true, backgroundColor = SURFACE, widthDp = 360)
@Composable
private fun SegmentedChoicePreview() {
    PreviewColumn {
        SegmentedChoice(options = listOf("Match source", "Maximum"), selectedIndex = 0, onSelect = {})
        SegmentedChoice(
            options = listOf("Blur", "Mosaic", "Solid"),
            selectedIndex = 1,
            onSelect = {},
            controlsBlur = true,
        )
        SegmentedChoice(options = listOf("Oval", "Box", "Eyes", "Off"), selectedIndex = 3, onSelect = {})
        SegmentedChoice(options = listOf("Blur", "Mosaic", "Solid"), selectedIndex = 0, onSelect = {}, enabled = false)
    }
}

@Preview(name = "LabeledSlider", showBackground = true, backgroundColor = 0xFF17191C, widthDp = 360)
@Composable
private fun LabeledSliderPreview() {
    PreviewColumn(raised = true) {
        LabeledSlider(label = "Strength", value = 0.7f, onValueChange = {}, valueText = "70%", controlsBlur = true)
        LabeledSlider(
            label = "Size",
            value = 1.2f,
            onValueChange = {},
            valueText = "120%",
            valueRange = 0.8f..1.8f,
            steps = 4,
            controlsBlur = true,
        )
        LabeledSlider(label = "Preview speed", value = 0.25f, onValueChange = {}, valueText = "0.25x")
        LabeledSlider(label = "Strength", value = 0.4f, onValueChange = {}, valueText = "40%", enabled = false)
    }
}

@Preview(name = "SwitchRow", showBackground = true, backgroundColor = SURFACE, widthDp = 360)
@Composable
private fun SwitchRowPreview() {
    PreviewColumn {
        SwitchRow(title = "Remove location", checked = false, onCheckedChange = {})
        SwitchRow(
            title = "Remove location",
            description = "The saved copy will not say where it was filmed.",
            checked = true,
            onCheckedChange = {},
        )
        SwitchRow(title = "Blur new faces", checked = true, onCheckedChange = {}, controlsBlur = true)
        SwitchRow(title = "Blur new faces", checked = true, onCheckedChange = {}, enabled = false)
        SwitchRow(title = "Blur new faces", checked = false, onCheckedChange = {}, enabled = false)
    }
}

@Preview(name = "SectionHeading and InfoRow", showBackground = true, backgroundColor = SURFACE, widthDp = 360)
@Composable
private fun InfoRowPreview() {
    PreviewColumn {
        Column {
            SectionHeading(text = "Source")
            InfoRow(label = "Resolution", value = "1920 x 1080")
            InfoRow(label = "Frame rate", value = "29.97 fps")
            InfoRow(label = "Codec", value = "H.264")
            InfoRow(label = "Audio", value = "AAC, copied without re-encoding")
        }
    }
}

@Preview(name = "Notice", showBackground = true, backgroundColor = SURFACE, widthDp = 360)
@Composable
private fun NoticePreview() {
    PreviewColumn {
        Notice(text = "Faces are found on this phone. The video is not uploaded.")
        Notice(
            tone = NoticeTone.Warning,
            title = "Colours will change",
            text = "This phone cannot save HDR video, so the copy is converted to standard colours.",
        )
        Notice(
            tone = NoticeTone.Error,
            title = "The copy was not saved",
            text = "There is not enough free space on this phone.",
            actionLabel = "Try again",
            onAction = {},
        )
    }
}

@Preview(name = "MosaicProgress", showBackground = true, backgroundColor = SURFACE, widthDp = 360)
@Composable
private fun MosaicProgressPreview() {
    PreviewColumn {
        MosaicProgress(progress = 0f)
        MosaicProgress(progress = 0.38f)
        MosaicProgress(progress = 1f)
        MosaicProgress(progress = null)
        MosaicProgress(progress = 0.5f, modifier = Modifier.width(120.dp), tileCount = 6)
    }
}

@Preview(name = "FaceThumbnail", showBackground = true, backgroundColor = 0xFF17191C)
@Composable
private fun FaceThumbnailPreview() {
    val image = remember { sampleImage() }
    PreviewColumn(raised = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FaceThumbnail(image = image, blurState = BlurState.Blurred, contentDescription = "Person 1", onClick = {})
            FaceThumbnail(image = image, blurState = BlurState.Visible, contentDescription = "Person 2", onClick = {})
            FaceThumbnail(image = image, blurState = BlurState.Mixed, contentDescription = "Person 3", onClick = {})
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FaceThumbnail(
                image = image,
                blurState = BlurState.Blurred,
                contentDescription = "Person 1",
                selected = true,
                onClick = {},
            )
            FaceThumbnail(image = null, blurState = BlurState.Visible, contentDescription = "Person 2", selected = true)
            FaceThumbnail(image = image, blurState = BlurState.Blurred, contentDescription = "Person 3", enabled = false)
            FaceThumbnail(image = null, blurState = BlurState.Mixed, contentDescription = "Person 4", size = 48.dp)
        }
    }
}

@Preview(name = "TopBar", showBackground = true, backgroundColor = SURFACE, widthDp = 360)
@Composable
private fun TopBarPreview() {
    OpenBlurTheme {
        Column {
            TopBar(title = "OpenBlur")
            TopBar(
                title = "Birthday at the park, second take.mp4",
                onBack = {},
                actions = {
                    IconAction(icon = OpenBlurIcons.Undo, contentDescription = "Undo", onClick = {})
                    IconAction(icon = OpenBlurIcons.More, contentDescription = "More options", onClick = {})
                },
            )
        }
    }
}

@Preview(name = "BottomPanel", showBackground = true, backgroundColor = SURFACE, widthDp = 360)
@Composable
private fun BottomPanelPreview() {
    OpenBlurTheme {
        BottomPanel {
            SectionHeading(text = "Blur")
            SegmentedChoice(
                options = listOf("Blur", "Mosaic", "Solid"),
                selectedIndex = 0,
                onSelect = {},
                modifier = Modifier.fillMaxWidth(),
                controlsBlur = true,
            )
            LabeledSlider(label = "Strength", value = 0.7f, onValueChange = {}, valueText = "70%", controlsBlur = true)
            PrimaryButton(text = "Save a copy", onClick = {}, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Preview(name = "ConfirmDialog", showBackground = true, backgroundColor = SURFACE)
@Composable
private fun ConfirmDialogPreview() {
    OpenBlurTheme {
        ConfirmDialog(
            title = "Delete this scan?",
            text = "The faces found in this video will be forgotten. Your video is not changed.",
            confirmLabel = "Delete scan",
            dismissLabel = "Keep it",
            onConfirm = {},
            onDismiss = {},
            destructive = true,
        )
    }
}

@Preview(name = "TimeText", showBackground = true, backgroundColor = SURFACE)
@Composable
private fun TimeTextPreview() {
    PreviewColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeText(timeUs = 65_000_000L)
            Text(text = "/", color = OpenBlurTheme.colors.textMuted, style = MaterialTheme.typography.bodySmall)
            TimeText(timeUs = 3_725_000_000L)
        }
        TimeText(
            timeUs = 754_000_000L,
            style = MaterialTheme.typography.headlineMedium,
            color = OpenBlurTheme.colors.text,
        )
    }
}

@Preview(name = "Icons", showBackground = true, backgroundColor = SURFACE, widthDp = 360)
@Composable
private fun IconsPreview() {
    val icons: List<Pair<String, ImageVector>> = with(OpenBlurIcons) {
        listOf(
            "Back" to Back, "Close" to Close, "Play" to Play, "Pause" to Pause, "StepBack" to StepBack,
            "StepForward" to StepForward, "Add" to Add, "Delete" to Delete, "Check" to Check, "More" to More,
            "Undo" to Undo, "Redo" to Redo, "Share" to Share, "OpenExternal" to OpenExternal, "Info" to Info,
            "Warning" to Warning, "Error" to Error, "Visible" to Visible, "Blurred" to Blurred,
            "PartlyBlurred" to PartlyBlurred, "Face" to Face, "Area" to Area, "Keyframe" to Keyframe,
            "Rescan" to Rescan, "Video" to Video, "VolumeOn" to VolumeOn, "VolumeOff" to VolumeOff,
        )
    }
    PreviewColumn {
        icons.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { (name, icon) ->
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
                        Text(text = name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Preview(name = "Largest font size", showBackground = true, backgroundColor = SURFACE, widthDp = 360, fontScale = 2f)
@Composable
private fun LargeFontPreview() {
    PreviewColumn {
        TopBar(title = "Birthday at the park.mp4", onBack = {})
        PrimaryButton(text = "Save a copy", onClick = {}, modifier = Modifier.fillMaxWidth(), icon = OpenBlurIcons.Check)
        SegmentedChoice(options = listOf("Blur", "Mosaic", "Solid"), selectedIndex = 1, onSelect = {})
        ToggleChip(text = "Remove location", selected = true, onSelectedChange = {})
        SwitchRow(
            title = "Remove location",
            description = "The copy will not say where it was filmed.",
            checked = true,
            onCheckedChange = {},
        )
        LabeledSlider(label = "Strength", value = 0.7f, onValueChange = {}, valueText = "70%", controlsBlur = true)
        InfoRow(label = "Resolution", value = "1920 x 1080")
        Notice(tone = NoticeTone.Warning, text = "The copy is converted to standard colours.")
    }
}

@Preview(name = "Right to left", showBackground = true, backgroundColor = SURFACE, widthDp = 360, locale = "ar")
@Composable
private fun RightToLeftPreview() {
    PreviewColumn {
        TopBar(title = "OpenBlur", onBack = {})
        MosaicProgress(progress = 0.38f)
        SwitchRow(title = "Remove location", checked = true, onCheckedChange = {})
        LabeledSlider(label = "Strength", value = 0.3f, onValueChange = {}, valueText = "30%", controlsBlur = true)
        FaceThumbnail(image = null, blurState = BlurState.Mixed, contentDescription = "Person 1")
    }
}
