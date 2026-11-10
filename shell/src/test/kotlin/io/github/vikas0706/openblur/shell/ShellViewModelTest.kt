package io.github.vikas0706.openblur.shell

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vikas0706.openblur.api.AnalysisException
import io.github.vikas0706.openblur.api.ExportFailure
import io.github.vikas0706.openblur.api.ExportPhase
import io.github.vikas0706.openblur.api.ExportState
import io.github.vikas0706.openblur.api.Project
import io.github.vikas0706.openblur.api.UnreadableVideoException
import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.ExportQuality
import io.github.vikas0706.openblur.core.ManualRegion
import io.github.vikas0706.openblur.core.MaskAppearance
import io.github.vikas0706.openblur.core.MaskStyle
import io.github.vikas0706.openblur.core.RegionKeyframe
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs under Robolectric only because the contracts use android.net.Uri; no screen is involved. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShellViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var fake: FakeAppServices

    @Before
    fun setUp() {
        dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        fake = FakeAppServices()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) =
        ShellViewModel(fake, saved, fallbackName = "Video", worker = dispatcher, startTimeoutMs = 1_000L)

    private fun ShellViewModel.editor(): Screen.Editor = screen.value as Screen.Editor

    private fun ShellViewModel.scanStage(): ScanStage = (screen.value as Screen.Scanning).scan.stage

    @Test
    fun start_removesAbandonedScansOnceAndListsProjects() = runTest {
        fake.fakeProjects.put(Samples.project("p1", "one.mp4"))
        val vm = viewModel()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(1, fake.fakeProjects.deleteAbandonedCalls)
        val list = vm.home.value.projects as ProjectListUi.Loaded
        assertEquals(listOf("one.mp4"), list.projects.map { it.displayName })
    }

    @Test
    fun listFailure_isReported_andRetryRecovers() = runTest {
        fake.fakeProjects.listFailure = IOException("disk")
        val vm = viewModel()
        assertEquals(ProjectListUi.Failed, vm.home.value.projects)
        fake.fakeProjects.listFailure = null
        vm.refreshProjects()
        assertTrue(vm.home.value.projects is ProjectListUi.Loaded)
    }

    @Test
    fun chosenVideo_showsProgress_thenSavesAndOpensTheEditor() = runTest {
        val hold = CompletableDeferred<Unit>()
        fake.fakeAnalyzer.hold = hold
        val vm = viewModel()

        assertTrue(vm.onVideoChosen(Samples.sourceUri))
        val scanning = vm.screen.value as Screen.Scanning
        assertEquals("holiday.mp4", scanning.scan.displayName)
        assertEquals(fake.fakeInspector.info, scanning.scan.info)
        assertEquals(ScanStage.Detecting(0.5f, 60, 2), scanning.scan.stage)
        assertFalse(scanning.scan.thorough)

        hold.complete(Unit)
        val editor = vm.editor()
        assertEquals("holiday.mp4", editor.project.displayName)
        assertEquals(Samples.sourceUri, editor.project.sourceUri)
        assertSame(fake.fakeAnalyzer.result, editor.project.result)
        assertEquals(AnalysisOptions.STANDARD, fake.fakeAnalyzer.lastOptions)
        assertEquals(setOf(editor.project.id), fake.fakeProjects.ids())
        assertEquals(1, fake.fakeAnalyzer.runs)
    }

    @Test
    fun videoWithoutAName_getsTheFallbackName() = runTest {
        fake.fakeInspector.name = null
        val vm = viewModel()
        vm.onVideoChosen(Samples.sourceUri)
        // The sample Uri ends in a media id, which is the best name available.
        assertEquals("41", vm.editor().project.displayName)
    }

    @Test
    fun secondVideoWhileScanning_isRefusedWithANotice() = runTest {
        fake.fakeAnalyzer.hold = CompletableDeferred()
        val vm = viewModel()
        vm.onVideoChosen(Samples.sourceUri)
        assertFalse(vm.onVideoChosen(Samples.outputUri))
        assertTrue(vm.busyNotice.value)
        assertEquals(1, fake.fakeAnalyzer.runs)
        vm.dismissBusyNotice()
        assertFalse(vm.busyNotice.value)
    }

    @Test
    fun cancelScan_stopsTheAnalyzer_discardsTheScan_andReturnsHome() = runTest {
        fake.fakeAnalyzer.hold = CompletableDeferred()
        val vm = viewModel()
        vm.onVideoChosen(Samples.sourceUri)
        vm.cancelScan()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(1, fake.fakeAnalyzer.cancelled)
        assertEquals(listOf("scan-1"), fake.fakeProjects.discarded)
        assertTrue(fake.fakeProjects.ids().isEmpty())
    }

    @Test
    fun unreadableVideo_failsBeforeAnyScanStarts() = runTest {
        fake.fakeInspector.failure = UnreadableVideoException("no video track")
        val vm = viewModel()
        vm.onVideoChosen(Samples.sourceUri)
        assertEquals(ScanStage.Failed(ScanFailure.UNREADABLE), vm.scanStage())
        assertEquals(0, fake.fakeAnalyzer.runs)
    }

    @Test
    fun videoWithoutAPictureSize_isUnreadable() = runTest {
        fake.fakeInspector.info = Samples.info().copy(codedWidth = 0)
        val vm = viewModel()
        vm.onVideoChosen(Samples.sourceUri)
        assertEquals(ScanStage.Failed(ScanFailure.UNREADABLE), vm.scanStage())
    }

    @Test
    fun analysisFailures_mapToTheirOwnMessages_andDiscardTheScan() = runTest {
        val cases = mapOf(
            AnalysisException(AnalysisException.Kind.SOURCE_UNREADABLE, "x") to ScanFailure.UNREADABLE,
            AnalysisException(AnalysisException.Kind.DECODER_FAILED, "x") to ScanFailure.DECODER,
            AnalysisException(AnalysisException.Kind.MODEL_FAILED, "x") to ScanFailure.MODEL,
            AnalysisException(AnalysisException.Kind.OUT_OF_MEMORY, "x") to ScanFailure.OUT_OF_MEMORY,
            AnalysisException(AnalysisException.Kind.UNKNOWN, "x") to ScanFailure.UNKNOWN,
            OutOfMemoryError("heap") to ScanFailure.OUT_OF_MEMORY,
            IllegalStateException("bug") to ScanFailure.UNKNOWN,
        )
        for ((error, expected) in cases) {
            fake = FakeAppServices()
            fake.fakeAnalyzer.failure = error
            val vm = viewModel()
            vm.onVideoChosen(Samples.sourceUri)
            assertEquals(error.toString(), ScanStage.Failed(expected), vm.scanStage())
            assertEquals(listOf("scan-1"), fake.fakeProjects.discarded)
            assertTrue(fake.fakeProjects.ids().isEmpty())
        }
    }

    @Test
    fun saveFailure_hasItsOwnMessage() = runTest {
        fake.fakeProjects.saveFailure = IOException("disk full")
        val vm = viewModel()
        vm.onVideoChosen(Samples.sourceUri)
        assertEquals(ScanStage.Failed(ScanFailure.SAVE), vm.scanStage())
    }

    @Test
    fun retryAfterFailure_scansTheSameVideoAgain() = runTest {
        fake.fakeAnalyzer.failure = AnalysisException(AnalysisException.Kind.DECODER_FAILED, "x")
        val vm = viewModel()
        vm.onVideoChosen(Samples.sourceUri)
        fake.fakeAnalyzer.failure = null
        vm.retryScan()
        assertEquals(Samples.sourceUri, vm.editor().project.sourceUri)
        assertEquals(2, fake.fakeAnalyzer.runs)
    }

    @Test
    fun leavingAFailedScan_returnsHome() = runTest {
        fake.fakeInspector.failure = UnreadableVideoException("x")
        val vm = viewModel()
        vm.onVideoChosen(Samples.sourceUri)
        vm.cancelScan()
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test
    fun chooseAnotherVideo_afterAFailedScan_goesHomeAndAsksForThePicker() = runTest {
        fake.fakeInspector.failure = UnreadableVideoException("x")
        val vm = viewModel()
        var picks = 0
        val collector = backgroundScope.launch(dispatcher) { vm.pickRequests.collect { picks++ } }
        vm.onVideoChosen(Samples.sourceUri)
        vm.chooseAnotherVideo()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(1, picks)
        collector.cancel()
    }

    @Test
    fun interruptedExport_showsOnHomeUntilDismissed() = runTest {
        fake.fakeExporter.interruptedFlag.value = true
        val vm = viewModel()
        assertTrue(vm.home.value.exportInterrupted)
        vm.dismissInterruptedExport()
        assertFalse(vm.home.value.exportInterrupted)
        assertFalse(fake.fakeExporter.interrupted.value)
    }

    @Test
    fun interruptedExport_foundAfterStart_stillReachesHome() = runTest {
        val vm = viewModel()
        assertFalse(vm.home.value.exportInterrupted)
        fake.fakeExporter.interruptedFlag.value = true
        assertTrue(vm.home.value.exportInterrupted)
    }

    @Test
    fun openProject_loadsItWithoutScanning() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        val vm = viewModel()
        vm.openProject("p1")
        assertEquals("p1", vm.editor().project.id)
        assertEquals(0, fake.fakeAnalyzer.runs)
    }

    @Test
    fun openProject_whoseVideoIsGone_returnsHomeWithANotice() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        fake.fakeProjects.unavailable += "p1"
        val vm = viewModel()
        vm.openProject("p1")
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(HomeNotice.PROJECT_UNAVAILABLE, vm.home.value.notice)
        vm.dismissHomeNotice()
        assertNull(vm.home.value.notice)
    }

    @Test
    fun deleteProject_removesItFromTheList() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        val vm = viewModel()
        vm.deleteProject("p1")
        assertTrue((vm.home.value.projects as ProjectListUi.Loaded).projects.isEmpty())
    }

    @Test
    fun deleteFailure_isReported() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        fake.fakeProjects.deleteFailure = IOException("busy")
        val vm = viewModel()
        vm.deleteProject("p1")
        assertEquals(HomeNotice.DELETE_FAILED, vm.home.value.notice)
        assertEquals(1, (vm.home.value.projects as ProjectListUi.Loaded).projects.size)
    }

    @Test
    fun restoredEditor_reloadsTheProject() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        val saved = SavedStateHandle(
            mapOf(ShellViewModel.KEY_SCREEN to ShellViewModel.SCREEN_EDITOR, ShellViewModel.KEY_PROJECT_ID to "p1"),
        )
        assertEquals("p1", viewModel(saved).editor().project.id)
    }

    @Test
    fun restoredScan_startsTheScanAgainWithTheSameSettings() = runTest {
        fake.fakeAnalyzer.hold = CompletableDeferred()
        val saved = SavedStateHandle(
            mapOf(
                ShellViewModel.KEY_SCREEN to ShellViewModel.SCREEN_SCAN,
                ShellViewModel.KEY_SCAN_URI to Samples.sourceUri.toString(),
                ShellViewModel.KEY_SCAN_THOROUGH to true,
            ),
        )
        val vm = viewModel(saved)
        assertTrue((vm.screen.value as Screen.Scanning).scan.thorough)
        assertEquals(AnalysisOptions.THOROUGH, fake.fakeAnalyzer.lastOptions)
    }

    @Test
    fun restoredAbout_staysOnAbout() = runTest {
        val saved = SavedStateHandle(mapOf(ShellViewModel.KEY_SCREEN to ShellViewModel.SCREEN_ABOUT))
        val vm = viewModel(saved)
        assertEquals(Screen.About, vm.screen.value)
        vm.showHome()
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test
    fun stateIsWrittenForRestore() = runTest {
        val saved = SavedStateHandle()
        fake.fakeProjects.put(Samples.project("p1"))
        val vm = viewModel(saved)
        vm.openProject("p1")
        assertEquals(ShellViewModel.SCREEN_EDITOR, saved.get<String>(ShellViewModel.KEY_SCREEN))
        assertEquals("p1", saved.get<String>(ShellViewModel.KEY_PROJECT_ID))
        vm.showHome()
        assertEquals(ShellViewModel.SCREEN_HOME, saved.get<String>(ShellViewModel.KEY_SCREEN))
        assertNull(saved.get<String>(ShellViewModel.KEY_PROJECT_ID))
    }

    @Test
    fun rescan_isThorough_replacesTheOldProject_andKeepsManualAreas() = runTest {
        val region = ManualRegion(7, MaskAppearance(), 0L, 1_000_000L, listOf(RegionKeyframe(0L, 0.5f, 0.5f, 0.2f, 0.2f)))
        val look = MaskAppearance(style = MaskStyle.MOSAIC)
        val oldEdit = EditState(appearance = look, visibleTrackIds = setOf(1), regions = listOf(region))
        fake.fakeProjects.put(Samples.project("old", edit = oldEdit))
        val vm = viewModel()
        vm.openProject("old")

        vm.rescanThorough()
        val project = vm.editor().project
        assertEquals(AnalysisOptions.THOROUGH, fake.fakeAnalyzer.lastOptions)
        assertEquals(AnalysisOptions.THOROUGH, project.options)
        assertEquals(setOf(project.id), fake.fakeProjects.ids())
        assertEquals(listOf(region), project.edit.regions)
        assertEquals(look, project.edit.appearance)
        // Track ids belong to the old scan, so choices keyed by them are not carried over.
        assertTrue(project.edit.visibleTrackIds.isEmpty())
    }

    @Test
    fun cancelledRescan_returnsToTheOldProject() = runTest {
        fake.fakeProjects.put(Samples.project("old"))
        fake.fakeAnalyzer.hold = CompletableDeferred()
        val vm = viewModel()
        vm.openProject("old")
        vm.rescanThorough()
        assertTrue((vm.screen.value as Screen.Scanning).scan.isRescan)
        vm.cancelScan()
        assertEquals("old", vm.editor().project.id)
        assertEquals(setOf("old"), fake.fakeProjects.ids())
    }

    @Test
    fun cancelledRescan_returnsWithTheEditSavedSinceOpening() = runTest {
        fake.fakeProjects.put(Samples.project("old"))
        fake.fakeAnalyzer.hold = CompletableDeferred()
        val vm = viewModel()
        vm.openProject("old")
        val newer = EditState(visibleTrackIds = setOf(2))
        fake.fakeProjects.saveEdit("old", newer)
        vm.rescanThorough()
        vm.cancelScan()
        assertEquals(newer, vm.editor().project.edit)
    }

    @Test
    fun failedRescan_returnsWithTheSavedEdit_orTheOpenedOneWhenItCannotBeRead() = runTest {
        val opened = EditState(visibleTrackIds = setOf(1))
        fake.fakeProjects.put(Samples.project("old", edit = opened))
        fake.fakeAnalyzer.failure = AnalysisException(AnalysisException.Kind.DECODER_FAILED, "x")
        val vm = viewModel()
        vm.openProject("old")
        val newer = EditState(visibleTrackIds = setOf(2))
        fake.fakeProjects.saveEdit("old", newer)
        vm.rescanThorough()
        assertEquals(ScanStage.Failed(ScanFailure.DECODER), vm.scanStage())
        vm.cancelScan()
        assertEquals(newer, vm.editor().project.edit)

        fake.fakeProjects.unavailable += "old"
        vm.rescanThorough()
        vm.cancelScan()
        assertEquals("old", vm.editor().project.id)
        assertEquals(newer, vm.editor().project.edit)
    }

    @Test
    fun exportSheet_loadsThePlan_andKnowsWhetherAnythingIsBlurred() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        fake.fakeTimelines.nothingBlurred = true
        val vm = viewModel()
        vm.openProject("p1")
        val edit = EditState(visibleTrackIds = setOf(1, 2))
        vm.openExportSheet(edit)

        val sheet = vm.editor().sheet!!
        assertEquals(PlanUi.Ready(fake.fakeExporter.plans.first()), sheet.plan)
        assertEquals(ExportQuality.MATCH_SOURCE, sheet.quality)
        assertFalse(sheet.removeLocation)
        assertEquals(true, sheet.nothingBlurred)
        assertTrue(sheet.canSave)
        assertSame(edit, fake.fakeTimelines.lastEdit)
        assertEquals(listOf("p1" to edit), fake.fakeProjects.savedEdits)
    }

    @Test
    fun exportSheet_qualityChange_plansAgain() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        val vm = viewModel()
        vm.openProject("p1")
        vm.openExportSheet(EditState())
        vm.setExportQuality(ExportQuality.MAXIMUM)
        val sheet = vm.editor().sheet!!
        assertEquals(ExportQuality.MAXIMUM, sheet.quality)
        assertEquals(24_000_000, (sheet.plan as PlanUi.Ready).plan.bitrate)
        assertEquals(listOf(ExportQuality.MATCH_SOURCE, ExportQuality.MAXIMUM), fake.fakeExporter.planQualities)
    }

    @Test
    fun exportSheet_withoutAnEncoder_cannotSave() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        fake.fakeExporter.plans = emptyList()
        val vm = viewModel()
        vm.openProject("p1")
        vm.openExportSheet(EditState())
        val sheet = vm.editor().sheet!!
        assertEquals(PlanUi.Unavailable, sheet.plan)
        assertFalse(sheet.canSave)
        vm.startExport("holiday blurred")
        assertNull(fake.fakeExporter.lastRequest)
    }

    @Test
    fun exportSheet_planFailure_canBeRetried() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        fake.fakeExporter.planFailure = IllegalStateException("codec list")
        val vm = viewModel()
        vm.openProject("p1")
        vm.openExportSheet(EditState())
        assertEquals(PlanUi.Failed, vm.editor().sheet!!.plan)
        fake.fakeExporter.planFailure = null
        vm.retryPlan()
        assertTrue(vm.editor().sheet!!.plan is PlanUi.Ready)
    }

    @Test
    fun exportSheet_timelineFailure_blocksSaving() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        fake.fakeTimelines.failure = IllegalStateException("bad track")
        val vm = viewModel()
        vm.openProject("p1")
        vm.openExportSheet(EditState())
        val sheet = vm.editor().sheet!!
        assertEquals(SheetProblem.PREPARE_FAILED, sheet.problem)
        assertFalse(sheet.canSave)
    }

    @Test
    fun closeExportSheet_returnsToThePlainEditor() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        val vm = viewModel()
        vm.openProject("p1")
        vm.openExportSheet(EditState())
        vm.closeExportSheet()
        assertNull(vm.editor().sheet)
    }

    @Test
    fun startExport_sendsTheSheetChoices_andShowsProgress() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        val vm = viewModel()
        vm.openProject("p1")
        val edit = EditState(visibleTrackIds = setOf(2))
        vm.openExportSheet(edit)
        vm.setRemoveLocation(true)
        vm.setExportQuality(ExportQuality.MAXIMUM)
        vm.startExport("holiday blurred")

        val request = fake.fakeExporter.lastRequest!!
        assertEquals("holiday blurred", request.displayName)
        assertEquals(Samples.sourceUri, request.sourceUri)
        assertEquals(ExportQuality.MAXIMUM, request.quality)
        assertTrue(request.removeLocation)
        assertFalse(request.timeline.isEmpty)

        val exporting = vm.screen.value as Screen.Exporting
        assertEquals(ExportPhase.ENCODING, exporting.running.phase)
        assertEquals(0.4f, exporting.running.progress)
        assertNull(exporting.remainingMs)
    }

    @Test
    fun removeLocation_isNeverSentForAVideoWithoutLocation() = runTest {
        val plain = Samples.project("p1").let {
            Project(it.id, it.sourceUri, it.displayName, Samples.info(hasLocation = false), it.options, it.result, it.edit)
        }
        fake.fakeProjects.put(plain)
        val vm = viewModel()
        vm.openProject("p1")
        vm.openExportSheet(EditState())
        vm.setRemoveLocation(true)
        vm.startExport("holiday blurred")
        assertFalse(fake.fakeExporter.lastRequest!!.removeLocation)
    }

    @Test
    fun finishedExport_showsTheResult_andLeavingReturnsToTheEditorWithTheExportedEdit() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        val vm = viewModel()
        vm.openProject("p1")
        val edit = EditState(visibleTrackIds = setOf(2))
        vm.openExportSheet(edit)
        vm.startExport("holiday blurred")
        fake.fakeExporter.succeed()

        val result = vm.screen.value as Screen.Result
        assertTrue(result.outcome is ExportOutcome.Saved)
        assertTrue(result.canReturnToEditor)

        vm.leaveResult()
        assertEquals(1, fake.fakeExporter.resetCalls)
        val editor = vm.editor()
        assertEquals(edit, editor.project.edit)
        assertNull(editor.sheet)
    }

    @Test
    fun failedExport_carriesTheFailureAndItsDetail() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        val vm = viewModel()
        vm.openProject("p1")
        vm.openExportSheet(EditState())
        vm.startExport("holiday blurred")
        fake.fakeExporter.fail(ExportFailure.OUT_OF_SPACE, "ENOSPC")
        val result = vm.screen.value as Screen.Result
        assertEquals(ExportOutcome.Failed(ExportFailure.OUT_OF_SPACE, "ENOSPC"), result.outcome)
    }

    @Test
    fun cancelExport_asksTheController_andShowsCancelled() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        val vm = viewModel()
        vm.openProject("p1")
        vm.openExportSheet(EditState())
        vm.startExport("holiday blurred")
        vm.cancelExport()
        assertEquals(1, fake.fakeExporter.cancelCalls)
        assertEquals(ExportOutcome.Cancelled, (vm.screen.value as Screen.Result).outcome)
        vm.leaveResult()
        assertEquals("p1", vm.editor().project.id)
    }

    @Test
    fun exportThatNeverStarts_reopensTheSheetWithAProblem() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        fake.fakeExporter.acceptsStart = false
        val vm = viewModel()
        vm.openProject("p1")
        vm.openExportSheet(EditState())
        vm.startExport("holiday blurred")
        assertTrue(vm.screen.value is Screen.Exporting)

        advanceTimeBy(1_500L)
        val sheet = vm.editor().sheet!!
        assertEquals(SheetProblem.START_FAILED, sheet.problem)
        // The timeline is still there, so saving again works without reopening the sheet.
        fake.fakeExporter.acceptsStart = true
        vm.startExport("holiday blurred")
        assertTrue(vm.screen.value is Screen.Exporting)
        advanceUntilIdle()
        assertTrue(vm.screen.value is Screen.Exporting)
    }

    @Test
    fun exportThatThrowsOnStart_reopensTheSheetWithAProblem() = runTest {
        fake.fakeProjects.put(Samples.project("p1"))
        fake.fakeExporter.startFailure = IllegalStateException("service not allowed")
        val vm = viewModel()
        vm.openProject("p1")
        vm.openExportSheet(EditState())
        vm.startExport("holiday blurred")
        assertEquals(SheetProblem.START_FAILED, vm.editor().sheet!!.problem)
    }

    @Test
    fun returningWhileAnExportRuns_landsOnExporting_thenOnTheResultWithoutAnEditor() = runTest {
        fake.fakeExporter.emit(ExportState.Running(ExportPhase.ENCODING, 0.7f, null, 90_000L))
        val vm = viewModel()
        assertTrue(vm.screen.value is Screen.Exporting)
        assertFalse(vm.onVideoChosen(Samples.sourceUri))

        fake.fakeExporter.succeed()
        val result = vm.screen.value as Screen.Result
        assertFalse(result.canReturnToEditor)
        vm.leaveResult()
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test
    fun blurAnotherVideo_resetsTheExport_goesHome_andAsksForThePicker() = runTest {
        fake.fakeExporter.succeed()
        val vm = viewModel()
        var picks = 0
        val collector = backgroundScope.launch(dispatcher) { vm.pickRequests.collect { picks++ } }
        vm.blurAnotherVideo()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(ExportState.Idle, fake.fakeExporter.state.value)
        assertEquals(1, picks)
        collector.cancel()
    }

    @Test
    fun aNewVideoAfterAFinishedExport_clearsTheResultAndScans() = runTest {
        fake.fakeExporter.succeed()
        val vm = viewModel()
        assertTrue(vm.onVideoChosen(Samples.sourceUri))
        assertEquals(ExportState.Idle, fake.fakeExporter.state.value)
        assertNotNull(vm.screen.value as? Screen.Editor)
    }

    @Test
    fun remainingTime_appearsOnceProgressIsSteady() = runTest {
        val vm = viewModel()
        for (second in 0..30) {
            fake.fakeExporter.emit(ExportState.Running(ExportPhase.ENCODING, second / 100f, null, second * 1000L))
        }
        val remaining = (vm.screen.value as Screen.Exporting).remainingMs
        assertNotNull(remaining)
        assertTrue("was $remaining", remaining!! in 66_000L..74_000L)
        fake.fakeExporter.succeed()
        fake.fakeExporter.reset()
        fake.fakeExporter.emit(ExportState.Running(ExportPhase.PREPARING, 0f, null, 0L))
        assertNull((vm.screen.value as Screen.Exporting).remainingMs)
    }
}
