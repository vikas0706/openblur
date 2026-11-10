package io.github.vikas0706.openblur.shell

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vikas0706.openblur.api.ExportFailure
import io.github.vikas0706.openblur.api.ExportPhase
import io.github.vikas0706.openblur.api.ExportState
import io.github.vikas0706.openblur.api.ProjectSummary
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.PlanNotice
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws each screen with real Skia under Robolectric at phone, landscape, tablet and large-font sizes,
 * leaves a PNG in build/renders for a person to look at, and checks what a machine can check:
 * no control is cut off at a screen edge and every control is big enough to tap.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class ScreenRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val renders = File("build/renders").apply { mkdirs() }
    private val app: TestShellApp get() = ApplicationProvider.getApplicationContext()

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OpenBlurTheme {
                    Box(Modifier.fillMaxSize().testTag(FRAME)) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun save(name: String, dialog: Boolean = false) {
        val node = if (dialog) rule.onNode(isDialog()) else rule.onNodeWithTag(FRAME)
        val bitmap = node.captureToImage().asAndroidBitmap()
        File(renders, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Every clickable node lies inside the frame's width and offers at least 48 dp to a finger. */
    private fun assertControlsFitAndAreTappable() {
        val frame = rule.onNodeWithTag(FRAME).fetchSemanticsNode()
        val density = frame.layoutInfo.density.density
        val width = frame.boundsInRoot.width
        val clickable = rule.onAllNodes(SemanticsMatcher("clickable") { it.config.contains(SemanticsActions.OnClick) })
            .fetchSemanticsNodes()
        assertTrue("the screen has controls", clickable.isNotEmpty())
        for (node in clickable) {
            val label = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            // Layout position and size, because the clipped bounds are empty for controls scrolled out of view.
            val left = node.positionInRoot.x
            val right = left + node.size.width
            assertTrue("$label starts off screen at $left", left >= -0.5f)
            assertTrue("$label ends off screen at $right of $width", right <= width + 0.5f)
            val touch = node.touchBoundsInRoot
            val tapWidth = maxOf(touch.width, node.size.width.toFloat()) / density
            val tapHeight = maxOf(touch.height, node.size.height.toFloat()) / density
            assertTrue("$label is $tapWidth dp wide", tapWidth >= 47.5f)
            assertTrue("$label is $tapHeight dp tall", tapHeight >= 47.5f)
        }
    }

    private fun check(name: String) {
        save(name)
        assertControlsFitAndAreTappable()
    }

    private val projects = listOf(
        ProjectSummary("p1", "holiday at the lake.mp4", 95_000_000L, 3, null, 0L),
        ProjectSummary("p2", "VID_20261104_181500_a_very_long_camera_file_name_that_wraps.mp4", 3_725_000_000L, 1, null, 0L),
        ProjectSummary("p3", "empty street.mov", 12_000_000L, 0, null, 0L),
    )

    @Composable
    private fun Home(home: HomeUi) {
        HomeScreen(home, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun home_withProjects() {
        show { Home(HomeUi(ProjectListUi.Loaded(projects))) }
        check("home_projects")
    }

    @Test
    fun home_empty() {
        show { Home(HomeUi(ProjectListUi.Loaded(emptyList()))) }
        check("home_empty")
    }

    @Test
    fun home_loadingWithNotice() {
        show { Home(HomeUi(ProjectListUi.Loading, HomeNotice.PROJECT_UNAVAILABLE)) }
        check("home_loading_notice")
    }

    @Test
    fun home_listError() {
        show { Home(HomeUi(ProjectListUi.Failed)) }
        check("home_error")
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun home_landscape() {
        show { Home(HomeUi(ProjectListUi.Loaded(projects))) }
        check("home_landscape")
    }

    @Test
    @Config(qualifiers = TABLET)
    fun home_tablet() {
        show { Home(HomeUi(ProjectListUi.Loaded(projects))) }
        check("home_tablet")
    }

    @Test
    fun home_largeFont() {
        show(fontScale = 2f) { Home(HomeUi(ProjectListUi.Loaded(projects))) }
        check("home_font2x")
    }

    private val scanning = ScanUi(
        thorough = false,
        isRescan = false,
        displayName = "holiday at the lake.mp4",
        info = Samples.info(),
        stage = ScanStage.Detecting(0.42f, 377, 3),
    )

    @Test
    fun scanning_progress() {
        show { ScanningScreen(scanning, busyNotice = false, {}, {}, {}) }
        check("scan_progress")
    }

    @Test
    fun scanning_reading() {
        show { ScanningScreen(scanning.copy(info = null, stage = ScanStage.Reading), busyNotice = true, {}, {}, {}) }
        check("scan_reading_busy")
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun scanning_landscape() {
        show { ScanningScreen(scanning, busyNotice = false, {}, {}, {}) }
        check("scan_landscape")
    }

    @Test
    fun scanning_largeFont() {
        show(fontScale = 2f) { ScanningScreen(scanning, busyNotice = false, {}, {}, {}) }
        check("scan_font2x")
    }

    @Test
    fun scanning_failed() {
        show { ScanningScreen(scanning.copy(stage = ScanStage.Failed(ScanFailure.DECODER)), false, {}, {}, {}) }
        check("scan_failed")
    }

    @Test
    fun scanning_cancelDialog() {
        show { ScanningScreen(scanning, busyNotice = false, {}, {}, {}) }
        rule.onAllNodesWithText(app.getString(R.string.scan_cancel))[0].performClick()
        rule.waitForIdle()
        save("scan_cancel_dialog", dialog = true)
    }

    private fun sheet(plan: PlanUi, nothingBlurred: Boolean? = false, problem: SheetProblem? = null) =
        ExportSheetUi(EditState(), plan = plan, nothingBlurred = nothingBlurred, problem = problem)

    @Composable
    private fun Sheet(ui: ExportSheetUi) {
        ExportSheet(Samples.project(), ui, {}, {}, {}, {}, {})
    }

    @Test
    fun exportSheet_ready() {
        val plan = Samples.plan(setOf(PlanNotice.HDR_TONE_MAPPED_TO_SDR, PlanNotice.SLOWER_THAN_REAL_TIME))
        show { Sheet(sheet(PlanUi.Ready(plan))) }
        save("sheet_ready", dialog = true)
    }

    @Test
    fun exportSheet_nothingBlurred() {
        show { Sheet(sheet(PlanUi.Ready(Samples.plan(emptySet())), nothingBlurred = true)) }
        save("sheet_nothing_blurred", dialog = true)
    }

    @Test
    fun exportSheet_noEncoder() {
        show { Sheet(sheet(PlanUi.Unavailable)) }
        save("sheet_no_encoder", dialog = true)
    }

    @Test
    fun exportSheet_loading() {
        show { Sheet(sheet(PlanUi.Loading, nothingBlurred = null)) }
        save("sheet_loading", dialog = true)
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun exportSheet_landscape() {
        show { Sheet(sheet(PlanUi.Ready(Samples.plan()))) }
        save("sheet_landscape", dialog = true)
    }

    private val running = ExportState.Running(ExportPhase.ENCODING, 0.63f, Samples.plan(), 185_000L)

    @Test
    fun exporting_progress() {
        show { ExportingScreen(running, remainingMs = 110_000L, busyNotice = false, {}, {}, {}) }
        check("export_progress")
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun exporting_landscape() {
        show { ExportingScreen(running, remainingMs = 110_000L, busyNotice = false, {}, {}, {}) }
        check("export_landscape")
    }

    @Test
    fun exporting_largeFont() {
        show(fontScale = 2f) { ExportingScreen(running, remainingMs = null, busyNotice = false, {}, {}, {}) }
        check("export_font2x")
    }

    private val savedOutcome = ExportOutcome.Saved(
        Samples.report(setOf(PlanNotice.CODEC_CHANGED_TO_H264, PlanNotice.SLOWER_THAN_REAL_TIME)),
    )

    @Test
    fun result_saved() {
        show { ResultScreen(savedOutcome, canReturnToEditor = true, {}, {}) }
        check("result_saved")
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun result_savedLandscape() {
        show { ResultScreen(savedOutcome, canReturnToEditor = true, {}, {}) }
        check("result_saved_landscape")
    }

    @Test
    @Config(qualifiers = TABLET)
    fun result_savedTablet() {
        show { ResultScreen(savedOutcome, canReturnToEditor = true, {}, {}) }
        check("result_saved_tablet")
    }

    @Test
    fun result_savedLargeFont() {
        show(fontScale = 2f) { ResultScreen(savedOutcome, canReturnToEditor = true, {}, {}) }
        check("result_saved_font2x")
    }

    @Test
    fun result_failed() {
        val outcome = ExportOutcome.Failed(ExportFailure.OUT_OF_SPACE, "ENOSPC")
        show { ResultScreen(outcome, canReturnToEditor = true, {}, {}) }
        check("result_failed")
    }

    @Test
    fun result_cancelledWithoutEditor() {
        show { ResultScreen(ExportOutcome.Cancelled, canReturnToEditor = false, {}, {}) }
        check("result_cancelled")
    }

    @Test
    fun about() {
        show { AboutScreen(onBack = {}) }
        check("about")
    }

    @Test
    fun about_largeFont() {
        show(fontScale = 2f) { AboutScreen(onBack = {}) }
        check("about_font2x")
    }

    private companion object {
        const val FRAME = "frame"
    }
}

private const val PHONE = "w360dp-h760dp-xhdpi"
private const val LANDSCAPE = "w760dp-h360dp-land-xhdpi"
private const val TABLET = "w900dp-h1200dp-mdpi"
