package io.github.vikas0706.openblur.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentsTest {
    @get:Rule
    val rule = createComposeRule()

    private val minTarget = 48.dp

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { OpenBlurTheme { content() } }
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun theme_allowsMotionUnlessTheSystemRemovesIt() {
        var reduced = true
        show { reduced = OpenBlurTheme.reducedMotion }
        rule.runOnIdle { assertFalse(reduced) }
    }

    @Test
    fun primaryButton_clicksWhenEnabledOnly() {
        var clicks = 0
        var enabled by mutableStateOf(true)
        show { PrimaryButton(text = "Save a copy", onClick = { clicks++ }, enabled = enabled) }

        rule.onNodeWithText("Save a copy")
            .assert(hasRole(Role.Button))
            .assertIsEnabled()
            .assertHasClickAction()
            .assertHeightIsAtLeast(minTarget)
            .performClick()
        rule.runOnIdle { assertEquals(1, clicks) }

        enabled = false
        rule.onNodeWithText("Save a copy").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun secondaryButtonAndTextAction_areButtonsWithFullTouchTargets() {
        var clicks = 0
        show {
            Column {
                SecondaryButton(text = "Scan again", onClick = { clicks++ })
                SecondaryButton(text = "Delete scan", onClick = { clicks++ }, destructive = true)
                TextAction(text = "Cancel", onClick = { clicks++ })
                TextAction(text = "Later", onClick = { clicks++ }, enabled = false)
            }
        }
        for (label in listOf("Scan again", "Delete scan", "Cancel")) {
            rule.onNodeWithText(label)
                .assert(hasRole(Role.Button))
                .assertHeightIsAtLeast(minTarget)
                .assertWidthIsAtLeast(minTarget)
                .performClick()
        }
        rule.onNodeWithText("Later").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(3, clicks) }
    }

    @Test
    fun iconAction_isNamedAndFortyEightDp() {
        var clicks = 0
        show {
            Column {
                IconAction(icon = OpenBlurIcons.Undo, contentDescription = "Undo", onClick = { clicks++ })
                IconAction(icon = OpenBlurIcons.Redo, contentDescription = "Redo", onClick = { clicks++ }, enabled = false)
            }
        }
        rule.onNodeWithContentDescription("Undo")
            .assert(hasRole(Role.Button))
            .assertHeightIsAtLeast(minTarget)
            .assertWidthIsAtLeast(minTarget)
            .performClick()
        rule.onNodeWithContentDescription("Redo").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun toggleChip_reportsAndFlipsItsState() {
        var selected by mutableStateOf(false)
        show { ToggleChip(text = "Remove location", selected = selected, onSelectedChange = { selected = it }) }

        rule.onNodeWithText("Remove location")
            .assert(hasRole(Role.Checkbox))
            .assertIsOff()
            .assertHeightIsAtLeast(minTarget)
            .performClick()
        rule.onNodeWithText("Remove location").assertIsOn().performClick()
        rule.onNodeWithText("Remove location").assertIsOff()
    }

    @Test
    fun toggleChip_disabledDoesNotChange() {
        var changes = 0
        show { ToggleChip(text = "Remove location", selected = true, onSelectedChange = { changes++ }, enabled = false) }
        rule.onNodeWithText("Remove location").assertIsNotEnabled().assertIsOn().performClick()
        rule.runOnIdle { assertEquals(0, changes) }
    }

    @Test
    fun segmentedChoice_selectsOneOption() {
        var index by mutableIntStateOf(0)
        show {
            SegmentedChoice(
                options = listOf("Blur", "Mosaic", "Solid"),
                selectedIndex = index,
                onSelect = { index = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        rule.onNodeWithText("Blur").assert(hasRole(Role.RadioButton)).assertIsSelected().assertHeightIsAtLeast(minTarget)
        rule.onNodeWithText("Mosaic").assertIsNotSelected().assertWidthIsAtLeast(minTarget)
        rule.onNodeWithText("Solid").assertIsNotSelected().performClick()
        rule.onNodeWithText("Solid").assertIsSelected()
        rule.onNodeWithText("Blur").assertIsNotSelected()
        rule.runOnIdle { assertEquals(2, index) }
    }

    @Test
    fun switchRow_isOneSwitchForTheWholeRow() {
        var checked by mutableStateOf(false)
        show {
            SwitchRow(
                title = "Remove location",
                description = "The saved copy will not say where it was filmed.",
                checked = checked,
                onCheckedChange = { checked = it },
            )
        }
        val row = hasText("Remove location") and hasText("The saved copy will not say where it was filmed.")
        rule.onNode(row).assert(hasRole(Role.Switch)).assertIsOff().assertHeightIsAtLeast(minTarget).performClick()
        rule.onNode(row).assertIsOn()
        rule.runOnIdle { assertTrue(checked) }
    }

    @Test
    fun labeledSlider_isNamedOnceAndSetsItsValue() {
        var value by mutableFloatStateOf(0.7f)
        show {
            LabeledSlider(
                label = "Strength",
                value = value,
                onValueChange = { value = it },
                valueText = "${(value * 100).toInt()}%",
                controlsBlur = true,
            )
        }
        val slider = rule.onNodeWithContentDescription("Strength")
        slider.assert(hasStateDescription("70%")).assertHeightIsAtLeast(minTarget)
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(0.7f, 0f..1f, 0))
        // The visible label and value are not separate stops for a screen reader: the slider speaks both.
        rule.onAllNodesWithText("Strength").assertCountEquals(0)
        rule.onAllNodesWithText("70%").assertCountEquals(0)

        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        rule.runOnIdle { assertEquals(0.25f, value, 1e-4f) }
        rule.onNodeWithContentDescription("Strength").assert(hasStateDescription("25%"))
    }

    @Test
    fun labeledSlider_survivesNotANumber() {
        show { LabeledSlider(label = "Strength", value = Float.NaN, onValueChange = {}, valueText = "") }
        rule.onNodeWithContentDescription("Strength").assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f, 0))
    }

    @Test
    fun mosaicProgress_isAProgressBar() {
        var progress by mutableStateOf<Float?>(0.38f)
        show { MosaicProgress(progress = progress, contentDescription = "Scanning the video") }

        val bar = rule.onNodeWithContentDescription("Scanning the video")
        bar.assertRangeInfoEquals(ProgressBarRangeInfo(0.38f, 0f..1f, 0))

        progress = 2f
        bar.assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..1f, 0))

        progress = Float.NaN
        bar.assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f, 0))

        // The sweep never goes idle, so the clock is stepped by hand from here on.
        rule.mainClock.autoAdvance = false
        progress = null
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500L)
        bar.assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
    }

    @Test
    fun faceThumbnail_saysWhoAndWhichState() {
        var state by mutableStateOf(BlurState.Blurred)
        var selected by mutableStateOf(false)
        var clicks = 0
        show {
            FaceThumbnail(
                image = null,
                blurState = state,
                contentDescription = "Person 1",
                selected = selected,
                onClick = { clicks++ },
            )
        }
        val thumbnail = rule.onNodeWithContentDescription("Person 1")
        thumbnail
            .assert(hasStateDescription("Blurred"))
            .assert(hasRole(Role.Button))
            .assertIsNotSelected()
            .assertHeightIsAtLeast(minTarget)
            .assertWidthIsAtLeast(minTarget)
            .performClick()
        rule.runOnIdle { assertEquals(1, clicks) }

        state = BlurState.Visible
        thumbnail.assert(hasStateDescription("Not blurred"))
        state = BlurState.Mixed
        thumbnail.assert(hasStateDescription("Blurred in some shots"))
        selected = true
        thumbnail.assertIsSelected()
    }

    @Test
    fun faceThumbnail_withoutOnClickOnlyDisplays() {
        show { FaceThumbnail(image = null, blurState = BlurState.Visible, contentDescription = "Person 2") }
        rule.onNodeWithContentDescription("Person 2").assertHasNoClickAction().assertIsDisplayed()
    }

    @Test
    fun notice_namesItsToneAndOffersItsAction() {
        var retries = 0
        show {
            Column {
                Notice(text = "Faces are found on this phone.")
                Notice(text = "The copy is converted to standard colours.", tone = NoticeTone.Warning)
                Notice(
                    text = "There is not enough free space.",
                    tone = NoticeTone.Error,
                    title = "The copy was not saved",
                    actionLabel = "Try again",
                    onAction = { retries++ },
                )
            }
        }
        rule.onNode(hasText("The copy is converted to standard colours.") and hasContentDescription("Warning")).assertIsDisplayed()
        val error = rule.onNode(
            hasText("There is not enough free space.") and hasText("The copy was not saved") and hasContentDescription("Error"),
        )
        error.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNode(hasText("Faces are found on this phone.") and hasContentDescription("Warning")).assertDoesNotExist()

        rule.onNodeWithText("Try again").assert(hasRole(Role.Button)).assertHeightIsAtLeast(minTarget).performClick()
        rule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun topBar_hasAHeadingAndAnOptionalBackAction() {
        var backs = 0
        var canGoBack by mutableStateOf(true)
        show {
            TopBar(
                title = "Holiday.mp4",
                onBack = if (canGoBack) ({ backs++ }) else null,
                actions = { IconAction(icon = OpenBlurIcons.More, contentDescription = "More options", onClick = {}) },
            )
        }
        rule.onNodeWithText("Holiday.mp4").assert(isHeading())
        rule.onNodeWithContentDescription("More options").assertHasClickAction()
        rule.onNodeWithContentDescription("Back").assertHeightIsAtLeast(minTarget).performClick()
        rule.runOnIdle { assertEquals(1, backs) }

        canGoBack = false
        rule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun sectionHeadingAndInfoRow_readAsHeadingAndOneLine() {
        show {
            Column {
                SectionHeading(text = "Source")
                InfoRow(label = "Resolution", value = "1920 x 1080")
            }
        }
        rule.onNodeWithText("Source").assert(isHeading())
        rule.onNode(hasText("Resolution") and hasText("1920 x 1080")).assertIsDisplayed()
    }

    @Test
    fun confirmDialog_confirmsAndDismisses() {
        var confirmed = 0
        var dismissed = 0
        show {
            ConfirmDialog(
                title = "Delete this scan?",
                text = "Your video is not changed.",
                confirmLabel = "Delete scan",
                dismissLabel = "Keep it",
                onConfirm = { confirmed++ },
                onDismiss = { dismissed++ },
                destructive = true,
            )
        }
        rule.onNodeWithText("Delete this scan?").assert(isHeading())
        rule.onNodeWithText("Your video is not changed.").assertIsDisplayed()
        rule.onNodeWithText("Delete scan").assertHeightIsAtLeast(minTarget).performClick()
        rule.onNodeWithText("Keep it").assertHeightIsAtLeast(minTarget).performClick()
        rule.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun timeText_showsDigitsAndSpeaksWords() {
        var timeUs by mutableStateOf(65_000_000L)
        show { TimeText(timeUs = timeUs) }

        rule.onNodeWithText("1:05").assertContentDescriptionEquals("1 minute 5 seconds")
        timeUs = 0L
        rule.onNodeWithText("0:00").assertContentDescriptionEquals("0 seconds")
        timeUs = 3_725_000_000L
        rule.onNodeWithText("1:02:05").assertContentDescriptionEquals("1 hour 2 minutes 5 seconds")
        timeUs = 120_000_000L
        rule.onNodeWithText("2:00").assertContentDescriptionEquals("2 minutes")
    }

    @Test
    fun rightToLeftLayout_keepsEachTextInItsOwnDirection() {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                OpenBlurTheme {
                    Column {
                        Notice(text = "The copy is converted to standard colours.", tone = NoticeTone.Warning)
                        SectionHeading(text = "Delete this scan?")
                        SectionHeading(text = "מקור")
                    }
                }
            }
        }
        fun layout(text: String): TextLayoutResult {
            val node = rule.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            return results.single()
        }
        // The app ships English only, so an RTL locale must not move a sentence's full stop or question mark to the left.
        for (text in listOf("The copy is converted to standard colours.", "Delete this scan?")) {
            val result = layout(text)
            assertEquals(text, ResolvedTextDirection.Ltr, result.getParagraphDirection(0))
            assertTrue(text, result.getBoundingBox(text.length - 1).left > result.getBoundingBox(0).left)
        }
        assertEquals(ResolvedTextDirection.Rtl, layout("מקור").getParagraphDirection(0))
    }

    @Test
    fun largestFontSize_wrapsInsteadOfClipping() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                OpenBlurTheme {
                    // Scrolls so a short test screen does not squeeze the last rows and fake a cut-off.
                    Column(modifier = Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                        PrimaryButton(text = "Save a copy of this video", onClick = {}, modifier = Modifier.fillMaxWidth())
                        SecondaryButton(text = "Choose another video", onClick = {})
                        SegmentedChoice(options = listOf("Blur", "Mosaic", "Solid"), selectedIndex = 0, onSelect = {})
                        ToggleChip(text = "Remove location from the copy", selected = true, onSelectedChange = {})
                        SwitchRow(
                            title = "Remove location",
                            description = "The copy will not say where it was filmed.",
                            checked = true,
                            onCheckedChange = {},
                        )
                        InfoRow(label = "Resolution", value = "1920 x 1080")
                        Notice(
                            text = "The copy is converted to standard colours.",
                            tone = NoticeTone.Warning,
                            title = "Colours will change",
                        )
                        SectionHeading(text = "Source")
                    }
                }
            }
        }
        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("expected the components' texts in the tree", texts.size >= 12)
        for (node in texts) {
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val layout = results.firstOrNull() ?: continue
            val name = "'${layout.layoutInput.text}'"
            // didOverflowWidth is not checked: it is also true for any text narrower than the room it was given.
            assertFalse("$name is cut off at the bottom", layout.didOverflowHeight)
            assertFalse("$name ends in an ellipsis", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        }
    }
}
