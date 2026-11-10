package io.github.vikas0706.openblur.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the components the way they run with the system "Remove animations" setting on. */
@RunWith(AndroidJUnit4::class)
class ReducedMotionTest {
    private val removeAnimations = object : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }

    @get:Rule
    val rule = createComposeRule(effectContext = removeAnimations)

    private fun isAmber(pixel: Int): Boolean =
        abs(((pixel shr 16) and 0xFF) - 0xFF) <= 10 && abs(((pixel shr 8) and 0xFF) - 0xB0) <= 10 && abs((pixel and 0xFF) - 0x20) <= 10

    @Test
    fun theThemeReportsTheSetting() {
        var reduced = false
        rule.setContent { OpenBlurTheme { reduced = OpenBlurTheme.reducedMotion } }
        rule.runOnIdle { assertTrue(reduced) }
    }

    @Test
    fun unknownProgressStandsStillWithEveryOtherTileLit() {
        rule.setContent { OpenBlurTheme { MosaicProgress(progress = null, contentDescription = "Scanning the video") } }

        // The rule only reaches idle here because nothing is animating.
        val bar = rule.onNodeWithContentDescription("Scanning the video")
        bar.assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)

        val bitmap = bar.captureToImage().asAndroidBitmap()
        val pitch = bitmap.width / (12 + 11 * 0.25f) * 1.25f
        val lit = List(12) { isAmber(bitmap.getPixel((it * pitch + bitmap.height / 2f).toInt(), bitmap.height / 2)) }
        assertEquals(List(12) { it % 2 == 0 }, lit)
    }

    @Test
    fun aSwitchStillChangesState() {
        var checked by mutableStateOf(false)
        rule.setContent {
            OpenBlurTheme { SwitchRow(title = "Remove location", checked = checked, onCheckedChange = { checked = it }) }
        }
        rule.onNodeWithText("Remove location").assertIsOff().performClick()
        rule.onNodeWithText("Remove location").assertIsOn()
        rule.runOnIdle { assertTrue(checked) }
    }

    @Test
    fun aPressStillRegisters() {
        var clicks = 0
        rule.setContent { OpenBlurTheme { PrimaryButton(text = "Save a copy", onClick = { clicks++ }) } }
        rule.onNodeWithText("Save a copy").performClick()
        rule.runOnIdle { assertEquals(1, clicks) }
    }
}
