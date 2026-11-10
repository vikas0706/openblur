package io.github.vikas0706.openblur.shell

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.vikas0706.openblur.api.AnalysisEvent
import io.github.vikas0706.openblur.api.AnalysisException
import io.github.vikas0706.openblur.api.AppServices
import io.github.vikas0706.openblur.api.ExportPhase
import io.github.vikas0706.openblur.api.ExportRequest
import io.github.vikas0706.openblur.api.ExportState
import io.github.vikas0706.openblur.api.Project
import io.github.vikas0706.openblur.api.UnreadableVideoException
import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.ExportQuality
import io.github.vikas0706.openblur.core.MaskTimeline
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the app's navigation and every long-running piece of work the screens show.
 * The screen is derived from two things: where the user is ([Base]) and what the export service is doing,
 * so returning to the app during or after an export always lands on the right screen.
 */
internal class ShellViewModel(
    private val services: AppServices,
    private val saved: SavedStateHandle,
    private val fallbackName: String,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val startTimeoutMs: Long = 15_000L,
) : ViewModel() {

    private sealed interface Base {
        data object Home : Base

        data object About : Base

        data class Scanning(val scan: ScanUi) : Base

        data object Opening : Base

        data class Editor(val project: Project, val sheet: ExportSheetUi?) : Base
    }

    private val base = MutableStateFlow<Base>(Base.Home)

    /** True between ExportController.start and the controller's first state other than Idle. */
    private val exportPending = MutableStateFlow(false)
    private val remainingMs = MutableStateFlow<Long?>(null)
    private val eta = EtaEstimator()

    val screen: StateFlow<Screen> =
        combine(base, services.exporter.state, exportPending, remainingMs, ::derive)
            .stateIn(viewModelScope, SharingStarted.Eagerly, derive(base.value, services.exporter.state.value, false, null))

    private val _home = MutableStateFlow(HomeUi())
    val home: StateFlow<HomeUi> = _home.asStateFlow()

    private val _busyNotice = MutableStateFlow(false)

    /** True after a video arrived while another one was being scanned or exported. */
    val busyNotice: StateFlow<Boolean> = _busyNotice.asStateFlow()

    private val pickChannel = Channel<Unit>(Channel.CONFLATED)

    /** Emits when the activity should open the system video picker. */
    val pickRequests: Flow<Unit> = pickChannel.receiveAsFlow()

    private val startup: Job
    private var scanJob: Job? = null
    private var scanReturn: Project? = null
    private var openJob: Job? = null
    private var listJob: Job? = null
    private var sheetJob: Job? = null
    private var planJob: Job? = null
    private var pendingJob: Job? = null
    private var sheetTimeline: MaskTimeline? = null

    init {
        startup = viewModelScope.launch {
            try {
                services.projects.deleteAbandoned()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Leftover folders only cost disk space; the next start tries again.
            }
        }
        viewModelScope.launch {
            services.exporter.state.collect { state ->
                if (state is ExportState.Running) {
                    remainingMs.value = eta.update(state.elapsedMs, state.progress)
                } else {
                    eta.reset()
                    remainingMs.value = null
                }
            }
        }
        restore()
        refreshProjects()
    }

    private fun derive(base: Base, export: ExportState, pending: Boolean, remaining: Long?): Screen {
        val canReturn = base is Base.Editor || base is Base.Opening
        return when (export) {
            is ExportState.Running -> Screen.Exporting(export, remaining)
            is ExportState.Succeeded -> Screen.Result(ExportOutcome.Saved(export.report), canReturn)
            is ExportState.Failed -> Screen.Result(ExportOutcome.Failed(export.failure, export.detail), canReturn)
            ExportState.Cancelled -> Screen.Result(ExportOutcome.Cancelled, canReturn)
            ExportState.Idle -> when {
                pending -> Screen.Exporting(ExportState.Running(ExportPhase.PREPARING, 0f, null, 0L), null)
                base is Base.Home -> Screen.Home
                base is Base.About -> Screen.About
                base is Base.Scanning -> Screen.Scanning(base.scan)
                base is Base.Opening -> Screen.OpeningProject
                base is Base.Editor -> Screen.Editor(base.project, base.sheet)
                else -> Screen.Home
            }
        }
    }

    private fun restore() {
        when (saved.get<String>(KEY_SCREEN)) {
            SCREEN_ABOUT -> base.value = Base.About
            SCREEN_SCAN -> {
                val uri = saved.get<String>(KEY_SCAN_URI) ?: return
                startScan(
                    uri = Uri.parse(uri),
                    thorough = saved.get<Boolean>(KEY_SCAN_THOROUGH) == true,
                    replaceId = saved.get<String>(KEY_SCAN_REPLACE),
                    returnTo = null,
                )
            }
            SCREEN_EDITOR -> saved.get<String>(KEY_PROJECT_ID)?.let(::openProject)
        }
    }

    private fun isBusy(): Boolean {
        val current = base.value
        val scanning = current is Base.Scanning && current.scan.stage !is ScanStage.Failed
        return scanning || exportPending.value || services.exporter.state.value is ExportState.Running
    }

    /** A video came from the picker or from another app. Returns false when it was refused because work is running. */
    fun onVideoChosen(uri: Uri): Boolean {
        if (isBusy()) {
            _busyNotice.value = true
            return false
        }
        if (services.exporter.state.value !is ExportState.Idle) services.exporter.reset()
        closeSheetWork()
        openJob?.cancel()
        startScan(uri, thorough = false, replaceId = null, returnTo = null)
        return true
    }

    fun dismissBusyNotice() {
        _busyNotice.value = false
    }

    fun showHome() {
        scanReturn = null
        openJob?.cancel()
        closeSheetWork()
        _busyNotice.value = false
        saved[KEY_SCREEN] = SCREEN_HOME
        saved.remove<String>(KEY_PROJECT_ID)
        base.value = Base.Home
        refreshProjects()
    }

    fun showAbout() {
        if (base.value !is Base.Home) return
        saved[KEY_SCREEN] = SCREEN_ABOUT
        base.value = Base.About
    }

    fun requestPick() {
        pickChannel.trySend(Unit)
    }

    fun showHomeNotice(notice: HomeNotice) {
        _home.update { it.copy(notice = notice) }
    }

    fun dismissHomeNotice() {
        _home.update { it.copy(notice = null) }
    }

    fun refreshProjects() {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            if (_home.value.projects !is ProjectListUi.Loaded) _home.update { it.copy(projects = ProjectListUi.Loading) }
            // Listing before the leftover folders are gone could race with their removal.
            startup.join()
            val loaded = try {
                ProjectListUi.Loaded(services.projects.list())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ProjectListUi.Failed
            }
            _home.update { it.copy(projects = loaded) }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            try {
                services.projects.delete(projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showHomeNotice(HomeNotice.DELETE_FAILED)
            }
            refreshProjects()
        }
    }

    fun openProject(projectId: String) {
        openJob?.cancel()
        saved[KEY_SCREEN] = SCREEN_EDITOR
        saved[KEY_PROJECT_ID] = projectId
        base.value = Base.Opening
        openJob = viewModelScope.launch {
            val project = try {
                services.projects.load(projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (base.value !is Base.Opening) return@launch
            if (project == null) {
                showHome()
                showHomeNotice(HomeNotice.PROJECT_UNAVAILABLE)
            } else {
                showEditor(project)
            }
        }
    }

    private fun showEditor(project: Project) {
        _busyNotice.value = false
        saved[KEY_SCREEN] = SCREEN_EDITOR
        saved[KEY_PROJECT_ID] = project.id
        base.value = Base.Editor(project, null)
    }

    private fun startScan(uri: Uri, thorough: Boolean, replaceId: String?, returnTo: Project?) {
        saved[KEY_SCREEN] = SCREEN_SCAN
        saved[KEY_SCAN_URI] = uri.toString()
        saved[KEY_SCAN_THOROUGH] = thorough
        saved[KEY_SCAN_REPLACE] = replaceId
        scanReturn = returnTo
        _busyNotice.value = false
        val previous = scanJob
        previous?.cancel()
        base.value = Base.Scanning(
            ScanUi(
                thorough = thorough,
                isRescan = replaceId != null,
                displayName = returnTo?.displayName,
                info = returnTo?.info,
                stage = ScanStage.Reading,
            ),
        )
        scanJob = viewModelScope.launch {
            // The previous scan removes its own leftovers before it ends; two scans must not overlap.
            previous?.join()
            startup.join()
            runScan(uri, thorough, replaceId)
        }
    }

    private suspend fun runScan(uri: Uri, thorough: Boolean, replaceId: String?) {
        var unsaved = false
        try {
            val name = try {
                services.inspector.displayName(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            updateScan { it.copy(displayName = name ?: it.displayName) }
            val info = services.inspector.inspect(uri)
            if (info.displayWidth <= 0 || info.displayHeight <= 0) {
                throw UnreadableVideoException("The video track reports no picture size")
            }
            updateScan { it.copy(info = info, stage = ScanStage.Detecting(0f, 0, 0)) }

            val options = if (thorough) AnalysisOptions.THOROUGH else AnalysisOptions.STANDARD
            val projectId = services.projects.newProjectId()
            unsaved = true
            val thumbnails = services.projects.thumbnails(projectId)
            var result: AnalysisResult? = null
            services.analyzer.analyze(uri, info, options, thumbnails).collect { event ->
                when (event) {
                    is AnalysisEvent.Progress -> updateScan {
                        it.copy(stage = ScanStage.Detecting(event.fraction, event.framesSeen, event.facesSoFar))
                    }
                    is AnalysisEvent.Finished -> result = event.result
                }
            }
            val finished = result
                ?: throw AnalysisException(AnalysisException.Kind.UNKNOWN, "The scan ended without a result")

            updateScan { it.copy(stage = ScanStage.Saving) }
            val project = Project(
                id = projectId,
                sourceUri = uri,
                displayName = name ?: scanReturn?.displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: fallbackName,
                info = info,
                options = options,
                result = finished,
                edit = carriedOverEdit(replaceId),
            )
            try {
                services.projects.save(project)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ScanSaveException(e)
            }
            unsaved = false
            if (replaceId != null) {
                try {
                    services.projects.delete(replaceId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The old scan stays in the list, where the user can delete it.
                }
            }
            scanReturn = null
            showEditor(project)
            refreshProjects()
        } catch (e: CancellationException) {
            if (unsaved) discardUnsavedScan()
            throw e
        } catch (e: Throwable) {
            if (unsaved) discardUnsavedScan()
            // A scan that was cancelled can still fail while it shuts down; that must not mark its successor as failed.
            if (!currentCoroutineContext().isActive) return
            updateScan { it.copy(stage = ScanStage.Failed(scanFailureOf(e))) }
        }
    }

    /** Manual blur areas and the shared look do not depend on the scan, so a rescan keeps them. */
    private suspend fun carriedOverEdit(replaceId: String?): EditState {
        if (replaceId == null) return EditState()
        val old = try {
            services.projects.load(replaceId)?.edit
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: scanReturn?.edit ?: return EditState()
        return EditState(appearance = old.appearance, regions = old.regions)
    }

    private suspend fun discardUnsavedScan() {
        withContext(NonCancellable) {
            try {
                services.projects.deleteAbandoned()
            } catch (_: Exception) {
                // Removed at the next start instead.
            }
        }
    }

    private fun scanFailureOf(error: Throwable): ScanFailure = when (error) {
        is UnreadableVideoException, is SecurityException -> ScanFailure.UNREADABLE
        is ScanSaveException -> ScanFailure.SAVE
        is OutOfMemoryError -> ScanFailure.OUT_OF_MEMORY
        is AnalysisException -> when (error.kind) {
            AnalysisException.Kind.SOURCE_UNREADABLE -> ScanFailure.UNREADABLE
            AnalysisException.Kind.DECODER_FAILED -> ScanFailure.DECODER
            AnalysisException.Kind.MODEL_FAILED -> ScanFailure.MODEL
            AnalysisException.Kind.OUT_OF_MEMORY -> ScanFailure.OUT_OF_MEMORY
            AnalysisException.Kind.UNKNOWN -> ScanFailure.UNKNOWN
        }
        is IOException -> ScanFailure.UNREADABLE
        else -> ScanFailure.UNKNOWN
    }

    private inline fun updateScan(change: (ScanUi) -> ScanUi) {
        base.update { if (it is Base.Scanning) Base.Scanning(change(it.scan)) else it }
    }

    /** Stops a running scan, or leaves a failed one. Returns to the editor when the scan was a rescan. */
    fun cancelScan() {
        if (base.value !is Base.Scanning) return
        scanJob?.cancel()
        val back = scanReturn
        val replaceId = saved.get<String>(KEY_SCAN_REPLACE)
        scanReturn = null
        when {
            back != null -> showEditor(back)
            replaceId != null -> openProject(replaceId)
            else -> showHome()
        }
    }

    fun retryScan() {
        val current = base.value as? Base.Scanning ?: return
        if (current.scan.stage !is ScanStage.Failed) return
        val uri = saved.get<String>(KEY_SCAN_URI) ?: return
        startScan(Uri.parse(uri), current.scan.thorough, saved.get<String>(KEY_SCAN_REPLACE), scanReturn)
    }

    fun rescanThorough() {
        val editor = base.value as? Base.Editor ?: return
        if (isBusy()) return
        closeSheetWork()
        startScan(editor.project.sourceUri, thorough = true, replaceId = editor.project.id, returnTo = editor.project)
    }

    fun openExportSheet(edit: EditState) {
        val editor = base.value as? Base.Editor ?: return
        closeSheetWork()
        val sheet = ExportSheetUi(edit = edit)
        base.value = editor.copy(sheet = sheet)
        val project = editor.project
        sheetJob = viewModelScope.launch {
            launch {
                try {
                    services.projects.saveEdit(project.id, edit)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The export uses the edit in memory; only reopening the project later would miss it.
                }
            }
            try {
                val timeline = withContext(worker) { services.timelines.build(project.result, edit) }
                sheetTimeline = timeline
                updateSheet { it.copy(nothingBlurred = timeline.isEmpty) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateSheet { it.copy(problem = SheetProblem.PREPARE_FAILED) }
            }
        }
        loadPlan(project, sheet.quality)
    }

    private fun loadPlan(project: Project, quality: ExportQuality) {
        planJob?.cancel()
        updateSheet { it.copy(plan = PlanUi.Loading) }
        planJob = viewModelScope.launch {
            val plan = try {
                val first = services.exporter.plan(project.info, quality).firstOrNull()
                if (first == null) PlanUi.Unavailable else PlanUi.Ready(first)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PlanUi.Failed
            }
            updateSheet { if (it.quality == quality) it.copy(plan = plan) else it }
        }
    }

    private inline fun updateSheet(change: (ExportSheetUi) -> ExportSheetUi) {
        base.update { if (it is Base.Editor && it.sheet != null) it.copy(sheet = change(it.sheet)) else it }
    }

    fun setExportQuality(quality: ExportQuality) {
        val editor = base.value as? Base.Editor ?: return
        val sheet = editor.sheet ?: return
        if (sheet.quality == quality) return
        updateSheet { it.copy(quality = quality) }
        loadPlan(editor.project, quality)
    }

    fun retryPlan() {
        val editor = base.value as? Base.Editor ?: return
        val sheet = editor.sheet ?: return
        loadPlan(editor.project, sheet.quality)
    }

    fun setRemoveLocation(remove: Boolean) {
        updateSheet { it.copy(removeLocation = remove) }
    }

    fun onStoragePermissionDenied() {
        updateSheet { it.copy(problem = SheetProblem.STORAGE_DENIED) }
    }

    fun closeExportSheet() {
        closeSheetWork()
        base.update { if (it is Base.Editor) it.copy(sheet = null) else it }
    }

    private fun closeSheetWork() {
        sheetJob?.cancel()
        planJob?.cancel()
        sheetTimeline = null
    }

    /** [displayName] is the saved file's name without extension. Does nothing unless the sheet is ready to save. */
    fun startExport(displayName: String) {
        val editor = base.value as? Base.Editor ?: return
        val sheet = editor.sheet ?: return
        val timeline = sheetTimeline ?: return
        if (!sheet.canSave || exportPending.value) return
        // The editor reopens with the exported edit, so nothing chosen before the export is lost.
        val project = editor.project.withEdit(sheet.edit)
        val request = ExportRequest(
            sourceUri = project.sourceUri,
            displayName = displayName,
            info = project.info,
            timeline = timeline,
            quality = sheet.quality,
            removeLocation = sheet.removeLocation && project.info.hasLocation,
        )
        exportPending.value = true
        base.value = Base.Editor(project, null)
        val started = try {
            services.exporter.start(request)
            true
        } catch (_: Exception) {
            false
        }
        pendingJob?.cancel()
        pendingJob = viewModelScope.launch {
            val accepted = started && withTimeoutOrNull(startTimeoutMs) {
                services.exporter.state.first { it !is ExportState.Idle }
            } != null
            if (!accepted) {
                base.update {
                    if (it is Base.Editor) it.copy(sheet = sheet.copy(problem = SheetProblem.START_FAILED)) else it
                }
            } else {
                sheetTimeline = null
            }
            exportPending.value = false
        }
    }

    fun cancelExport() {
        services.exporter.cancel()
    }

    /** Leaves the result screen for the editor the export came from, or for Home when there is none. */
    fun leaveResult() {
        services.exporter.reset()
        if (base.value !is Base.Editor && base.value !is Base.Opening) showHome()
    }

    fun blurAnotherVideo() {
        services.exporter.reset()
        showHome()
        requestPick()
    }

    private fun Project.withEdit(edit: EditState): Project =
        Project(id, sourceUri, displayName, info, options, result, edit)

    private class ScanSaveException(cause: Throwable) : Exception(cause)

    companion object {
        const val KEY_SCREEN = "screen"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_SCAN_URI = "scan_uri"
        const val KEY_SCAN_THOROUGH = "scan_thorough"
        const val KEY_SCAN_REPLACE = "scan_replace"
        const val SCREEN_HOME = "home"
        const val SCREEN_ABOUT = "about"
        const val SCREEN_SCAN = "scan"
        const val SCREEN_EDITOR = "editor"

        fun factory(services: AppServices, fallbackName: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShellViewModel(services, createSavedStateHandle(), fallbackName) }
        }
    }
}
