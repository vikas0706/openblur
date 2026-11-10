package io.github.vikas0706.openblur.shell

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vikas0706.openblur.api.AnalysisException
import io.github.vikas0706.openblur.api.ExportFailure
import io.github.vikas0706.openblur.api.ExportPhase
import io.github.vikas0706.openblur.api.ExportState
import io.github.vikas0706.openblur.api.Project
import io.github.vikas0706.openblur.api.UnreadableVideoException
import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.PlanNotice
import io.github.vikas0706.openblur.designsystem.R as DesignR
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives MainActivity through the whole flow against [FakeAppServices].
 * The same file runs on the JVM under Robolectric and on a device; see the module's build file.
 */
@RunWith(AndroidJUnit4::class)
class ShellFlowTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app: TestShellApp get() = ApplicationProvider.getApplicationContext()
    private val fake: FakeAppServices get() = app.fake
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        app.reset()
        // The system permission dialog cannot be answered from here, so the one-time question counts as asked.
        ExportPermissions.markNotificationsAsked(app)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch(intent: Intent? = null) {
        scenario = if (intent == null) ActivityScenario.launch(MainActivity::class.java) else ActivityScenario.launch(intent)
    }

    private fun relaunch() {
        scenario?.close()
        app.reset()
        launch()
    }

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun chooseVideo() {
        scenario!!.onActivity { it.openVideo(Samples.sourceUri) }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForTextGone(text: String) {
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
    }

    private fun assertText(text: String) {
        rule.onNodeWithText(text).assertExists()
    }

    /** Scrolls to the node when it sits in a scrolling column, then clicks it. */
    private fun click(matcher: SemanticsMatcher) {
        val node = rule.onNode(matcher)
        try {
            node.performScrollTo()
        } catch (_: AssertionError) {
            // Dialog buttons and top bar actions have no scrolling parent.
        }
        node.performClick()
    }

    private fun click(text: String) = click(hasText(text))

    private fun openSavedProject(name: String = "holiday.mp4") {
        waitForText(string(R.string.home_headline))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
        rule.onNodeWithText(name).performClick()
        waitForText(TestShellApp.EDITOR_TITLE_PREFIX + name)
    }

    private fun openExportSheet() {
        fake.fakeProjects.put(Samples.project("p1"))
        launch()
        openSavedProject()
        click(TestShellApp.EDITOR_EXPORT)
        waitForText(string(R.string.sheet_title))
    }

    private fun startExport() {
        openExportSheet()
        waitForText(string(R.string.sheet_plan_heading))
        click(string(R.string.sheet_save))
        waitForText(string(R.string.export_title))
    }

    @Test
    fun home_showsTheMainAction_theNetworkFact_andTheEmptyState() {
        launch()
        waitForText(string(R.string.home_headline))
        rule.onNodeWithText(string(R.string.home_choose_video)).assertIsEnabled()
        assertText(string(R.string.home_no_network))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(string(R.string.home_projects_empty)))
        assertEquals(1, fake.fakeProjects.deleteAbandonedCalls)
    }

    @Test
    fun home_listFailure_offersTryAgain() {
        fake.fakeProjects.listFailure = IOException("disk")
        launch()
        waitForText(string(R.string.home_headline))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(string(R.string.home_projects_error)))
        fake.fakeProjects.listFailure = null
        click(string(R.string.action_try_again))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(string(R.string.home_projects_empty)))
    }

    @Test
    fun chosenVideo_showsScanProgressAndFacts_thenOpensTheEditor() {
        val hold = CompletableDeferred<Unit>()
        fake.fakeAnalyzer.hold = hold
        launch()
        chooseVideo()

        waitForText(string(R.string.scan_title))
        rule.onNodeWithTag(TAG_SCAN_STATUS).assertTextEquals("50%")
        assertText(app.resources.getQuantityString(R.plurals.scan_frames, 60, 60))
        assertText(app.resources.getQuantityString(R.plurals.scan_faces, 2, 2))
        assertText("holiday.mp4")
        assertText("1920 x 1080")
        assertText("29.97 fps")
        assertText("0:30")
        assertText("H.264 (AVC), 8-bit")
        assertEquals(AnalysisOptions.STANDARD, fake.fakeAnalyzer.lastOptions)

        hold.complete(Unit)
        waitForText(TestShellApp.EDITOR_TITLE_PREFIX + "holiday.mp4")
        assertEquals(1, fake.fakeProjects.ids().size)
        assertEquals(1, fake.fakeAnalyzer.runs)
    }

    @Test
    fun videoSharedFromAnotherApp_startsScanning() {
        fake.fakeAnalyzer.hold = CompletableDeferred()
        val intent = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, Samples.sourceUri)
        launch(intent)
        waitForText(string(R.string.scan_title))
        assertEquals(1, fake.fakeAnalyzer.runs)
    }

    @Test
    fun videoSharedOnlyAsClipData_startsScanning() {
        fake.fakeAnalyzer.hold = CompletableDeferred()
        val intent = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("video/mp4")
        intent.clipData = android.content.ClipData.newRawUri(null, Samples.sourceUri)
        launch(intent)
        waitForText(string(R.string.scan_title))
        assertEquals(1, fake.fakeAnalyzer.runs)
    }

    @Test
    fun videoOpenedFromAnotherApp_startsScanning() {
        fake.fakeAnalyzer.hold = CompletableDeferred()
        val intent = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(Samples.sourceUri, "video/mp4")
        launch(intent)
        waitForText(string(R.string.scan_title))
    }

    @Test
    fun cancelScan_asksFirst_thenReturnsHomeAndDiscardsTheScan() {
        fake.fakeAnalyzer.hold = CompletableDeferred()
        launch()
        chooseVideo()
        waitForText(string(R.string.scan_title))

        click(string(R.string.scan_cancel))
        waitForText(string(R.string.scan_cancel_title))
        click(string(R.string.scan_cancel_dismiss))
        waitForTextGone(string(R.string.scan_cancel_title))
        assertText(string(R.string.scan_title))

        click(string(R.string.scan_cancel))
        click(string(R.string.scan_cancel_confirm))
        waitForText(string(R.string.home_headline))
        assertEquals(1, fake.fakeAnalyzer.cancelled)
        assertEquals(1, fake.fakeProjects.discarded.size)
        assertTrue(fake.fakeProjects.ids().isEmpty())
    }

    @Test
    fun eachScanFailure_hasItsOwnMessage_andAWayBack() {
        val cases = listOf(
            AnalysisException(AnalysisException.Kind.SOURCE_UNREADABLE, "x") to R.string.scan_fail_unreadable_title,
            AnalysisException(AnalysisException.Kind.DECODER_FAILED, "x") to R.string.scan_fail_decoder_title,
            AnalysisException(AnalysisException.Kind.MODEL_FAILED, "x") to R.string.scan_fail_model_title,
            AnalysisException(AnalysisException.Kind.OUT_OF_MEMORY, "x") to R.string.scan_fail_memory_title,
            AnalysisException(AnalysisException.Kind.UNKNOWN, "x") to R.string.scan_fail_unknown_title,
        )
        for ((error, title) in cases) {
            relaunch()
            fake.fakeAnalyzer.failure = error
            chooseVideo()
            waitForText(string(title))
            rule.onNodeWithText(string(R.string.scan_retry)).assertIsEnabled()
            click(string(R.string.scan_choose_another))
            waitForText(string(R.string.home_headline))
        }
    }

    @Test
    fun unreadableVideo_isExplained_andScanAgainWorksOnceItIsReadable() {
        fake.fakeInspector.failure = UnreadableVideoException("no video track")
        launch()
        chooseVideo()
        waitForText(string(R.string.scan_fail_unreadable_title))
        assertText(string(R.string.scan_fail_unreadable_text))

        fake.fakeInspector.failure = null
        click(string(R.string.scan_retry))
        waitForText(TestShellApp.EDITOR_TITLE_PREFIX + "holiday.mp4")
    }

    @Test
    fun projectList_showsSavedScans_andDeletesAfterConfirmation() {
        fake.fakeProjects.put(Samples.project("p1", "holiday.mp4"))
        fake.fakeProjects.put(Samples.project("p2", "school play.mov"))
        launch()
        waitForText(string(R.string.home_headline))
        val list = rule.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("school play.mov"))
        list.performScrollToNode(hasText("holiday.mp4"))
        val people = app.resources.getQuantityString(R.plurals.project_people, 2, 2)
        rule.onAllNodesWithText(string(R.string.project_meta, "0:30", people)).fetchSemanticsNodes().let {
            assertTrue(it.isNotEmpty())
        }

        val delete = string(R.string.project_delete, "holiday.mp4")
        list.performScrollToNode(hasContentDescription(delete))
        rule.onNodeWithContentDescription(delete).performClick()
        waitForText(string(R.string.delete_title))
        click(string(R.string.delete_dismiss))
        waitForTextGone(string(R.string.delete_title))
        assertEquals(setOf("p1", "p2"), fake.fakeProjects.ids())

        rule.onNodeWithContentDescription(delete).performClick()
        click(string(R.string.delete_confirm))
        waitForTextGone("holiday.mp4")
        assertEquals(setOf("p2"), fake.fakeProjects.ids())
    }

    @Test
    fun savedScan_reopensWithoutScanning() {
        fake.fakeProjects.put(Samples.project("p1"))
        launch()
        openSavedProject()
        assertEquals(0, fake.fakeAnalyzer.runs)
        click(TestShellApp.EDITOR_BACK)
        waitForText(string(R.string.home_headline))
    }

    @Test
    fun savedScanWhoseVideoIsGone_isExplainedOnHome() {
        fake.fakeProjects.put(Samples.project("p1"))
        fake.fakeProjects.unavailable += "p1"
        launch()
        waitForText(string(R.string.home_headline))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("holiday.mp4"))
        rule.onNodeWithText("holiday.mp4").performClick()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(string(R.string.home_notice_project_missing)))
    }

    @Test
    fun rescan_runsThorough_andReplacesTheOldScan() {
        fake.fakeProjects.put(Samples.project("p1"))
        val hold = CompletableDeferred<Unit>()
        fake.fakeAnalyzer.hold = hold
        launch()
        openSavedProject()
        click(TestShellApp.EDITOR_RESCAN)
        waitForText(string(R.string.scan_title_thorough))
        assertEquals(AnalysisOptions.THOROUGH, fake.fakeAnalyzer.lastOptions)

        hold.complete(Unit)
        waitForText(TestShellApp.EDITOR_TITLE_PREFIX + "holiday.mp4")
        assertFalse("p1" in fake.fakeProjects.ids())
        assertEquals(1, fake.fakeProjects.ids().size)
    }

    @Test
    fun exportSheet_statesWhatWillBeSaved() {
        fake.fakeExporter.plans = listOf(
            Samples.plan(setOf(PlanNotice.CODEC_CHANGED_TO_H264, PlanNotice.SLOWER_THAN_REAL_TIME)),
        )
        openExportSheet()
        waitForText(string(R.string.sheet_plan_heading))
        assertText("1920 x 1080")
        assertText("29.97 fps")
        assertText("H.264 (AVC)")
        assertText("about 12.0 Mbps")
        assertText("up to about 46.5 MB")
        assertText(string(R.string.sheet_audio_copied))
        assertText(string(R.string.notice_codec_h264))
        assertText(string(R.string.notice_slow))
        assertText(string(R.string.sheet_remove_location))
        assertText(string(R.string.sheet_destination, "holiday blurred"))
        rule.onNodeWithText(string(R.string.sheet_save)).assertIsEnabled()

        click(string(R.string.sheet_quality_maximum))
        waitForText("about 24.0 Mbps")
        assertText(string(R.string.sheet_quality_maximum_text))
    }

    @Test
    fun exportSheet_withoutLocation_hasNoLocationSwitch() {
        fake.fakeProjects.put(
            Samples.project("p1").let {
                Project(it.id, it.sourceUri, it.displayName, Samples.info(hasLocation = false), it.options, it.result, it.edit)
            },
        )
        launch()
        openSavedProject()
        click(TestShellApp.EDITOR_EXPORT)
        waitForText(string(R.string.sheet_plan_heading))
        rule.onNodeWithText(string(R.string.sheet_remove_location)).assertDoesNotExist()
    }

    @Test
    fun exportSheet_whenThePhoneCannotEncode_explainsAndHasNoSaveButton() {
        fake.fakeExporter.plans = emptyList()
        openExportSheet()
        waitForText(string(R.string.sheet_no_plan_title))
        assertText(string(R.string.sheet_no_plan_text))
        rule.onNodeWithText(string(R.string.sheet_save)).assertDoesNotExist()
        assertTrue(fake.fakeExporter.lastRequest == null)
    }

    @Test
    fun exportSheet_whenNothingIsBlurred_asksBeforeSaving() {
        fake.fakeTimelines.nothingBlurred = true
        openExportSheet()
        waitForText(string(R.string.sheet_nothing_blurred))
        click(string(R.string.sheet_save))
        waitForText(string(R.string.sheet_empty_title))
        assertTrue(fake.fakeExporter.lastRequest == null)

        click(string(R.string.sheet_empty_confirm))
        waitForText(string(R.string.export_title))
        assertNotNull(fake.fakeExporter.lastRequest)
    }

    @Test
    fun exportSheet_closeButton_returnsToTheEditor() {
        openExportSheet()
        rule.onNodeWithContentDescription(string(R.string.action_close)).performClick()
        waitForTextGone(string(R.string.sheet_title))
        assertText(TestShellApp.EDITOR_TITLE_PREFIX + "holiday.mp4")
    }

    @Test
    fun exporting_showsPhaseProgressAndElapsedTime() {
        startExport()
        val request = fake.fakeExporter.lastRequest!!
        assertEquals("holiday blurred", request.displayName)
        assertEquals(Samples.sourceUri, request.sourceUri)
        assertFalse(request.removeLocation)

        assertText(string(R.string.export_phase_encoding))
        rule.onNodeWithTag(TAG_EXPORT_PERCENT).assertTextEquals("40%")
        assertText(string(R.string.export_elapsed, "1:05"))

        rule.runOnUiThread {
            fake.fakeExporter.emit(ExportState.Running(ExportPhase.SAVING, 1f, Samples.plan(), 80_000L))
        }
        waitForText(string(R.string.export_phase_saving))
        assertText(string(R.string.export_elapsed, "1:20"))
    }

    @Test
    fun cancelExport_asksFirst_thenReturnsToTheEditor() {
        startExport()
        click(string(R.string.export_cancel))
        waitForText(string(R.string.export_cancel_title))
        click(string(R.string.export_cancel_dismiss))
        waitForTextGone(string(R.string.export_cancel_title))
        assertEquals(0, fake.fakeExporter.cancelCalls)

        click(string(R.string.export_cancel))
        waitForText(string(R.string.export_cancel_title))
        rule.onNode(hasText(string(R.string.export_cancel_confirm)) and isDialogButton()).performClick()
        waitForText(string(R.string.result_cancelled_title))
        assertEquals(1, fake.fakeExporter.cancelCalls)

        click(string(R.string.result_back_to_editor))
        waitForText(TestShellApp.EDITOR_TITLE_PREFIX + "holiday.mp4")
        assertEquals(ExportState.Idle, fake.fakeExporter.state.value)
    }

    @Test
    fun savedResult_comparesSourceAndOutput_andOffersTheNextSteps() {
        startExport()
        rule.runOnUiThread { fake.fakeExporter.succeed() }
        waitForText(string(R.string.result_saved_title))
        assertText(string(R.string.result_saved_as, "holiday blurred"))

        fun row(label: Int, source: String, output: String) {
            rule.onNode(hasContentDescription(string(R.string.result_compare_spoken, string(label), source, output)))
                .assertExists()
        }
        row(R.string.fact_resolution, "1920 x 1080", "1920 x 1080")
        row(R.string.fact_frame_rate, "29.97 fps", "29.97 fps")
        row(R.string.fact_codec, "H.264 (AVC), 8-bit", "H.264 (AVC), 8-bit")
        row(R.string.fact_bitrate, "10.0 Mbps", "12.0 Mbps")
        row(R.string.fact_audio, "AAC", string(R.string.result_audio_copied, "AAC"))
        row(R.string.fact_file_size, "39.0 MB", "46.5 MB")
        row(R.string.fact_hdr, string(R.string.hdr_none), string(R.string.hdr_none))
        assertText(string(R.string.notice_slow))

        rule.onNodeWithText(string(R.string.result_share)).assertIsEnabled()
        rule.onNodeWithText(string(R.string.result_open_external)).assertIsEnabled()
        rule.onNodeWithText(string(R.string.result_blur_another)).assertIsEnabled()

        click(string(R.string.result_back_to_editor))
        waitForText(TestShellApp.EDITOR_TITLE_PREFIX + "holiday.mp4")
        assertEquals(ExportState.Idle, fake.fakeExporter.state.value)
        assertTrue(fake.fakeExporter.resetCalls >= 1)
    }

    @Test
    fun failedExport_explainsAndCopiesTheDetails() {
        startExport()
        val detail = "MediaCodec.CodecException: Error 0xfffffc0e"
        rule.runOnUiThread { fake.fakeExporter.fail(ExportFailure.ENCODER_FAILED, detail) }
        waitForText(string(R.string.result_failed_title))
        assertText(string(R.string.failure_encoder))
        assertText(string(R.string.failure_encoder_advice))

        click(string(R.string.result_copy_details))
        waitForText(string(R.string.result_details_copied))
        var copied: String? = null
        rule.runOnUiThread {
            val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals(detail, copied)

        click(string(R.string.result_back_to_editor))
        waitForText(TestShellApp.EDITOR_TITLE_PREFIX + "holiday.mp4")
    }

    @Test
    fun everyExportFailure_hasAMessageAndAdvice() {
        startExport()
        for (failure in ExportFailure.entries) {
            rule.runOnUiThread { fake.fakeExporter.fail(failure, "detail for $failure") }
            waitForText(Labels.exportFailure(app.resources, failure))
            assertText(Labels.exportFailureAdvice(app.resources, failure))
        }
    }

    @Test
    fun blurAnotherVideo_leavesTheResultForHome() {
        startExport()
        rule.runOnUiThread { fake.fakeExporter.fail(ExportFailure.OUT_OF_SPACE, "ENOSPC") }
        waitForText(string(R.string.result_failed_title))
        click(string(R.string.result_blur_another))
        waitForText(string(R.string.home_headline))
        assertEquals(ExportState.Idle, fake.fakeExporter.state.value)
    }

    @Test
    fun returningWhileAnExportRuns_landsOnTheExportScreen() {
        fake.fakeExporter.emit(ExportState.Running(ExportPhase.ENCODING, 0.7f, Samples.plan(), 125_000L))
        launch()
        waitForText(string(R.string.export_title))
        rule.onNodeWithTag(TAG_EXPORT_PERCENT).assertTextEquals("70%")
        assertText(string(R.string.export_elapsed, "2:05"))
    }

    @Test
    fun about_statesVersionPrivacyLicenceSourceAndNotices() {
        launch()
        waitForText(string(R.string.home_headline))
        click(string(R.string.home_about))
        waitForText(string(R.string.about_title))
        assertText(string(R.string.about_privacy_text))
        assertText(string(R.string.about_licence_text))
        assertText(string(R.string.about_source_url))
        assertText(string(R.string.about_component_onnx))
        assertText(string(R.string.about_component_yunet))
        assertText(string(R.string.about_component_sface))

        click(string(R.string.about_show_bsd))
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodesWithText("BSD 3-Clause License", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        click(string(R.string.about_hide_bsd))

        // ONNX Runtime is MIT licensed and links third-party code; both notices must ship in the APK.
        click(string(R.string.about_show_onnx))
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodesWithText("Copyright (c) Microsoft Corporation", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        click(string(R.string.about_hide_onnx))
        click(string(R.string.about_show_onnx_notices))
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodesWithText("THIRD PARTY SOFTWARE NOTICES", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        click(string(R.string.about_hide_onnx_notices))

        rule.onNodeWithContentDescription(string(DesignR.string.designsystem_back))
            .performClick()
        waitForText(string(R.string.home_headline))
    }

    @Test
    fun exportSheet_whenTheBlurCannotBePrepared_cannotSave() {
        fake.fakeTimelines.failure = IllegalStateException("bad track")
        openExportSheet()
        waitForText(string(R.string.sheet_problem_prepare))
        rule.onNodeWithText(string(R.string.sheet_save)).assertIsNotEnabled()
    }

    /** The confirm button of a dialog shares its label with the screen button that opened it. */
    private fun isDialogButton(): SemanticsMatcher =
        hasAnyAncestor(isDialog())

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
