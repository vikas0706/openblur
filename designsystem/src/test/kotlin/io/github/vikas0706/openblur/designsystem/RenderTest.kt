package io.github.vikas0706.openblur.designsystem

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Draws the components with real Skia under Robolectric, checks the pixels that carry meaning, and
 * leaves a PNG of each group in build/renders for a person to look at.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class RenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val renders = File("build/renders").apply { mkdirs() }

    private fun show(raised: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
        rule.setContent {
            OpenBlurTheme {
                Column(
                    modifier = Modifier
                        .testTag("frame")
                        .background(if (raised) OpenBlurTheme.colors.surfaceRaised else OpenBlurTheme.colors.surface)
                        .padding(OpenBlurDimens.ScreenPadding),
                    verticalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap),
                    content = content,
                )
            }
        }
    }

    private fun SemanticsNodeInteraction.pixels(saveAs: String? = null): Bitmap {
        val bitmap = captureToImage().asAndroidBitmap()
        if (saveAs != null) {
            File(renders, "$saveAs.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    private fun frame(saveAs: String): Bitmap = rule.onNodeWithTag("frame").pixels(saveAs)

    private fun near(pixel: Int, argb: Long, tolerance: Int = 10): Boolean {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return abs(((pixel shr 16) and 0xFF) - r) <= tolerance &&
            abs(((pixel shr 8) and 0xFF) - g) <= tolerance &&
            abs((pixel and 0xFF) - b) <= tolerance
    }

    private fun Bitmap.count(argb: Long): Int {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.count { near(it, argb) }
    }

    private fun Bitmap.share(argb: Long): Float = count(argb).toFloat() / (width * height)

    private fun sampleImage(): ImageBitmap {
        val image = ImageBitmap(96, 96)
        val canvas = Canvas(image)
        canvas.drawRect(Rect(0f, 0f, 96f, 96f), Paint().apply { color = Color(0xFF5B6470) })
        canvas.drawCircle(Offset(48f, 44f), 26f, Paint().apply { color = Color(0xFFB9A48F) })
        return image
    }

    @Test
    fun buttons() {
        show {
            PrimaryButton(text = "Save a copy", onClick = {}, modifier = Modifier.fillMaxWidth())
            PrimaryButton(text = "Choose a video", onClick = {}, icon = OpenBlurIcons.Video)
            PrimaryButton(text = "Save disabled", onClick = {}, enabled = false)
            SecondaryButton(text = "Scan again", onClick = {}, icon = OpenBlurIcons.Rescan)
            SecondaryButton(text = "Delete scan", onClick = {}, destructive = true)
            SecondaryButton(text = "Scan disabled", onClick = {}, enabled = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction(text = "Choose another video", onClick = {})
                TextAction(text = "Cancel", onClick = {}, enabled = false)
            }
            Row {
                IconAction(icon = OpenBlurIcons.Undo, contentDescription = "Undo", onClick = {})
                IconAction(icon = OpenBlurIcons.Redo, contentDescription = "Redo", onClick = {}, enabled = false)
                IconAction(icon = OpenBlurIcons.Blurred, contentDescription = "Blur", onClick = {}, tint = OpenBlurTheme.colors.amber)
            }
        }
        frame("buttons")
        assertTrue("the main action is filled with amber", rule.onNodeWithText("Save a copy").pixels().share(Palette.AMBER) > 0.7f)
        assertEquals("a disabled main action is not amber", 0, rule.onNodeWithText("Save disabled").pixels().count(Palette.AMBER))
        assertEquals("a secondary action is not amber", 0, rule.onNodeWithText("Scan again").pixels().count(Palette.AMBER))
        assertTrue("a destructive action is drawn in danger", rule.onNodeWithText("Delete scan").pixels().count(Palette.DANGER) > 0)
        assertEquals("a disabled icon is not near-white", 0, rule.onNodeWithContentDescription("Redo").pixels().count(Palette.TEXT))
    }

    @Test
    fun focusRingAppearsOnKeyboardFocus() {
        var inputMode: InputModeManager? = null
        show {
            inputMode = LocalInputModeManager.current
            PrimaryButton(text = "Save a copy", onClick = {})
            SecondaryButton(text = "Scan again", onClick = {})
            IconAction(icon = OpenBlurIcons.Play, contentDescription = "Play", onClick = {})
            TextAction(text = "Cancel", onClick = {})
            SegmentedChoice(options = listOf("Blur", "Mosaic", "Solid"), selectedIndex = 0, onSelect = {}, controlsBlur = true)
            ToggleChip(text = "Blur everyone", selected = true, onSelectedChange = {}, controlsBlur = true)
            SwitchRow(title = "Remove location", checked = true, onCheckedChange = {})
            LabeledSlider(label = "Strength", value = 0.5f, onValueChange = {}, valueText = "50%", controlsBlur = true)
            FaceThumbnail(image = null, blurState = BlurState.Blurred, contentDescription = "Person 1", selected = true, onClick = {})
        }
        val button = rule.onNodeWithText("Save a copy")
        assertEquals("no near-white on an unfocused amber button", 0, button.pixels().count(Palette.TEXT))

        // Clickable things only take focus once the window has left touch mode, as it does on the first key press.
        rule.runOnUiThread { inputMode!!.requestInputMode(InputMode.Keyboard) }
        button.requestFocus().assertIsFocused()
        val focused = button.pixels()
        assertTrue("near-white dashes on the focused amber button", focused.count(Palette.TEXT) > 100)
        assertTrue("dark line under the dashes", focused.count(Palette.SURFACE) > 100)
        frame("focus-primary")

        val play = rule.onNodeWithContentDescription("Play")
        val before = play.pixels().count(Palette.TEXT)
        play.requestFocus().assertIsFocused()
        assertTrue("dashes around the focused icon action", play.pixels().count(Palette.TEXT) > before + 100)
        assertEquals("the ring left the button when focus moved on", 0, button.pixels().count(Palette.TEXT))

        // Each of these is drawn without near-white dashes until it has focus; text and marks are solid.
        for (label in listOf("Scan again", "Cancel", "Mosaic", "Blur everyone", "Remove location")) {
            val node = rule.onNodeWithText(label)
            val unfocused = node.pixels().count(Palette.TEXT)
            node.requestFocus().assertIsFocused()
            val gained = node.pixels().count(Palette.TEXT) - unfocused
            assertTrue("'$label' shows the focus ring ($gained more near-white pixels)", gained > 100)
        }
        frame("focus-switch")

        val slider = rule.onNodeWithContentDescription("Strength")
        val sliderBefore = slider.pixels().count(Palette.TEXT)
        slider.requestFocus().assertIsFocused()
        assertTrue("dashes around the focused slider thumb", slider.pixels().count(Palette.TEXT) > sliderBefore + 50)
        frame("focus-slider")

        rule.onNodeWithContentDescription("Person 1").requestFocus().assertIsFocused()
        frame("focus-thumbnail")
    }

    @Test
    fun pressedStateShowsWhileHeld() {
        show {
            PrimaryButton(text = "Save a copy", onClick = {})
            SecondaryButton(text = "Scan again", onClick = {})
        }
        val primary = rule.onNodeWithText("Save a copy")
        primary.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(200L)
        val held = primary.pixels("pressed-primary")
        assertEquals("amber is darkened while pressed", 0, held.count(Palette.AMBER))
        assertTrue("pressed amber fills the button", held.share(0xFFDA971B) > 0.7f)
        primary.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500L)
        assertTrue("amber returns after release", primary.pixels().share(Palette.AMBER) > 0.7f)

        val secondary = rule.onNodeWithText("Scan again")
        assertTrue("screen colour inside an idle secondary button", secondary.pixels().share(Palette.SURFACE) > 0.5f)
        secondary.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(200L)
        assertTrue("lighter fill while pressed", secondary.pixels("pressed-secondary").share(0xFF202123) > 0.5f)
        secondary.performTouchInput { up() }
    }

    @Test
    fun selectionControls() {
        show {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip(text = "Off chip", selected = false, onSelectedChange = {})
                ToggleChip(text = "On chip", selected = true, onSelectedChange = {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip(text = "Blur chip", selected = true, onSelectedChange = {}, controlsBlur = true)
                ToggleChip(text = "Disabled", selected = true, onSelectedChange = {}, enabled = false)
            }
            SegmentedChoice(options = listOf("Match source", "Maximum"), selectedIndex = 0, onSelect = {})
            SegmentedChoice(options = listOf("Blur", "Mosaic", "Solid"), selectedIndex = 1, onSelect = {}, controlsBlur = true)
            SegmentedChoice(options = listOf("Oval", "Box", "Eyes", "Off"), selectedIndex = 3, onSelect = {}, enabled = false)
            SwitchRow(title = "Remove location", checked = false, onCheckedChange = {})
            SwitchRow(
                title = "Remove location",
                description = "The saved copy will not say where it was filmed.",
                checked = true,
                onCheckedChange = {},
            )
            SwitchRow(title = "Blur new faces", checked = true, onCheckedChange = {}, controlsBlur = true)
            SwitchRow(title = "Disabled on", checked = true, onCheckedChange = {}, enabled = false)
        }
        frame("selection")
        assertEquals("a neutral chip is never amber", 0, rule.onNodeWithText("On chip").pixels().count(Palette.AMBER))
        assertTrue("a blur chip is marked in amber", rule.onNodeWithText("Blur chip").pixels().count(Palette.AMBER) > 100)
        assertEquals("a disabled chip is not amber", 0, rule.onNodeWithText("Disabled").pixels().count(Palette.AMBER))
        assertTrue("the selected blur segment is framed in amber", rule.onNodeWithText("Mosaic").pixels().count(Palette.AMBER) > 50)
        assertEquals("an unselected segment has no amber", 0, rule.onNodeWithText("Solid").pixels().count(Palette.AMBER))
        assertTrue("a blur switch that is on has an amber track", rule.onNodeWithText("Blur new faces").pixels().count(Palette.AMBER) > 500)
    }

    @Test
    fun sliders() {
        show(raised = true) {
            LabeledSlider(label = "Strength", value = 0f, onValueChange = {}, valueText = "0%", controlsBlur = true)
            LabeledSlider(label = "Strength", value = 0.7f, onValueChange = {}, valueText = "70%", controlsBlur = true)
            LabeledSlider(label = "Size", value = 1.2f, onValueChange = {}, valueText = "120%", valueRange = 0.8f..1.8f, steps = 4)
            LabeledSlider(label = "Disabled", value = 0.4f, onValueChange = {}, valueText = "40%", enabled = false)
        }
        frame("sliders")
    }

    @Test
    fun mosaicProgressFillsFromTheStart() {
        show {
            MosaicProgress(progress = 0f, modifier = Modifier.testTag("empty"))
            MosaicProgress(progress = 0.5f, modifier = Modifier.testTag("half"))
            MosaicProgress(progress = 1f, modifier = Modifier.testTag("full"))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MosaicProgress(progress = 0.25f, modifier = Modifier.testTag("rtl"))
            }
            MosaicProgress(progress = 0.5f, modifier = Modifier.width(120.dp).testTag("small"), tileCount = 6)
        }
        frame("progress")
        fun tilesLit(tag: String, tiles: Int = 12): List<Boolean> {
            val bitmap = rule.onNodeWithTag(tag).pixels()
            val pitch = bitmap.width / (tiles + (tiles - 1) * 0.25f) * 1.25f
            return List(tiles) { near(bitmap.getPixel((it * pitch + bitmap.height / 2f).toInt(), bitmap.height / 2), Palette.AMBER) }
        }
        assertEquals(List(12) { false }, tilesLit("empty"))
        assertEquals(List(12) { it < 6 }, tilesLit("half"))
        assertEquals(List(12) { true }, tilesLit("full"))
        assertEquals("right to left fills from the right", List(12) { it >= 9 }, tilesLit("rtl"))
        assertEquals(List(6) { it < 3 }, tilesLit("small", tiles = 6))
        assertTrue("empty tiles are outlined", rule.onNodeWithTag("empty").pixels().count(Palette.OUTLINE_STRONG) > 200)
    }

    @Test
    fun faceThumbnails() {
        val image = sampleImage()
        show(raised = true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FaceThumbnail(image = image, blurState = BlurState.Blurred, contentDescription = "Blurred", onClick = {})
                FaceThumbnail(image = image, blurState = BlurState.Visible, contentDescription = "Visible", onClick = {})
                FaceThumbnail(image = image, blurState = BlurState.Mixed, contentDescription = "Mixed", onClick = {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FaceThumbnail(image = image, blurState = BlurState.Blurred, contentDescription = "Selected", selected = true)
                FaceThumbnail(image = null, blurState = BlurState.Visible, contentDescription = "No picture", selected = true)
                FaceThumbnail(image = image, blurState = BlurState.Blurred, contentDescription = "Disabled", enabled = false)
                FaceThumbnail(image = null, blurState = BlurState.Mixed, contentDescription = "Small", size = 48.dp)
            }
        }
        frame("thumbnails")
        val blurred = rule.onNodeWithContentDescription("Blurred").pixels().count(Palette.AMBER)
        val mixed = rule.onNodeWithContentDescription("Mixed").pixels().count(Palette.AMBER)
        assertTrue("a blurred face has an amber border and mark", blurred > 500)
        assertEquals("a visible face has no amber", 0, rule.onNodeWithContentDescription("Visible").pixels().count(Palette.AMBER))
        assertTrue("a mixed face has less amber border than a blurred one", mixed in 200 until blurred)
        val unselected = rule.onNodeWithContentDescription("Blurred").pixels().count(Palette.TEXT)
        val selected = rule.onNodeWithContentDescription("Selected").pixels().count(Palette.TEXT)
        assertTrue("the selected ring is near-white", selected > unselected + 500)
    }

    @Test
    fun contentRows() {
        show {
            Column {
                SectionHeading(text = "Source")
                InfoRow(label = "Resolution", value = "1920 x 1080")
                InfoRow(label = "Frame rate", value = "29.97 fps")
                InfoRow(label = "Audio", value = "AAC, copied without re-encoding")
            }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TimeText(timeUs = 65_000_000L)
                TimeText(timeUs = 3_725_000_000L)
                TimeText(timeUs = 754_000_000L, style = MaterialTheme.typography.headlineMedium, color = OpenBlurTheme.colors.text)
            }
        }
        val all = frame("content")
        assertEquals("nothing here is amber", 0, all.count(Palette.AMBER))
        assertTrue("the error notice is marked in danger", all.count(Palette.DANGER) > 200)
    }

    @Test
    fun containers() {
        rule.setContent {
            OpenBlurTheme {
                Column(modifier = Modifier.testTag("frame").background(OpenBlurTheme.colors.surface)) {
                    TopBar(title = "OpenBlur")
                    TopBar(
                        title = "Birthday at the park, second take.mp4",
                        onBack = {},
                        actions = {
                            IconAction(icon = OpenBlurIcons.Undo, contentDescription = "Undo", onClick = {})
                            IconAction(icon = OpenBlurIcons.More, contentDescription = "More options", onClick = {})
                        },
                    )
                    Box(modifier = Modifier.fillMaxWidth().weight(1f))
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
        }
        frame("containers")
    }

    @Test
    fun confirmDialog() {
        rule.setContent {
            OpenBlurTheme {
                Box(modifier = Modifier.fillMaxSize().background(OpenBlurTheme.colors.surface))
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
        val dialog = rule.onNode(isDialog()).pixels("dialog")
        assertTrue("the dialog sits on the raised surface", dialog.share(Palette.SURFACE_RAISED) > 0.5f)
        assertTrue("the destructive action is drawn in danger", dialog.count(Palette.DANGER) > 100)
        assertEquals("a dialog is never amber", 0, dialog.count(Palette.AMBER))
    }

    @Test
    fun icons() {
        val icons = with(OpenBlurIcons) {
            listOf(
                Back, Close, Play, Pause, StepBack, StepForward, Add, Delete, Check, More, Undo, Redo, Share,
                OpenExternal, Info, Warning, Error, Visible, Blurred, PartlyBlurred, Face, Area, Keyframe, Rescan,
                Video, VolumeOn, VolumeOff,
            )
        }
        show {
            icons.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { Icon(imageVector = it, contentDescription = it.name, modifier = Modifier.size(44.dp)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                icons.take(12).forEach { Icon(imageVector = it, contentDescription = null, modifier = Modifier.size(16.dp)) }
            }
        }
        frame("icons")
        for (icon in icons) {
            val drawn = rule.onNodeWithContentDescription(icon.name).pixels().count(Palette.TEXT)
            assertTrue("${icon.name} draws something", drawn > 150)
        }
        assertEquals("every icon has its own name", icons.size, icons.map { it.name }.toSet().size)
    }

    @Test
    fun largestFontSize() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                OpenBlurTheme {
                    Column(
                        modifier = Modifier.testTag("frame").background(OpenBlurTheme.colors.surface).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
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
            }
        }
        frame("large-font")
    }

    @Test
    fun rightToLeft() {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                OpenBlurTheme {
                    Column(
                        modifier = Modifier.testTag("frame").background(OpenBlurTheme.colors.surface).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TopBar(title = "OpenBlur", onBack = {}, actions = {
                            IconAction(icon = OpenBlurIcons.Undo, contentDescription = "Undo", onClick = {})
                        })
                        SwitchRow(title = "Remove location", checked = true, onCheckedChange = {})
                        LabeledSlider(label = "Strength", value = 0.3f, onValueChange = {}, valueText = "30%", controlsBlur = true)
                        ToggleChip(text = "Blur everyone", selected = true, onSelectedChange = {}, controlsBlur = true)
                        FaceThumbnail(image = null, blurState = BlurState.Mixed, contentDescription = "Person 1")
                        Notice(
                            tone = NoticeTone.Warning,
                            text = "The copy is converted to standard colours.",
                            actionLabel = "Details",
                            onAction = {},
                        )
                        Text(text = "Text", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        frame("rtl")
    }
}
