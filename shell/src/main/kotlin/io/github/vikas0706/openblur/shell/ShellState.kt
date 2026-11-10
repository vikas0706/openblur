package io.github.vikas0706.openblur.shell

import io.github.vikas0706.openblur.api.ExportFailure
import io.github.vikas0706.openblur.api.ExportReport
import io.github.vikas0706.openblur.api.ExportState
import io.github.vikas0706.openblur.api.Project
import io.github.vikas0706.openblur.api.ProjectSummary
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.EncodePlan
import io.github.vikas0706.openblur.core.ExportQuality
import io.github.vikas0706.openblur.core.SourceVideoInfo

/** What the activity shows. Exactly one screen at a time; the export sheet is part of [Editor]. */
internal sealed interface Screen {
    data object Home : Screen

    data object About : Screen

    data class Scanning(val scan: ScanUi) : Screen

    /** A saved scan is being read from disk. */
    data object OpeningProject : Screen

    data class Editor(val project: Project, val sheet: ExportSheetUi?) : Screen

    /** [remainingMs] is null until the estimate is stable. */
    data class Exporting(val running: ExportState.Running, val remainingMs: Long?) : Screen

    /** [canReturnToEditor] is false when the app was reopened without the project the export came from. */
    data class Result(val outcome: ExportOutcome, val canReturnToEditor: Boolean) : Screen
}

internal sealed interface ExportOutcome {
    data class Saved(val report: ExportReport) : ExportOutcome

    data class Failed(val failure: ExportFailure, val detail: String) : ExportOutcome

    data object Cancelled : ExportOutcome
}

internal enum class ScanFailure { UNREADABLE, DECODER, MODEL, OUT_OF_MEMORY, SAVE, UNKNOWN }

internal sealed interface ScanStage {
    data object Reading : ScanStage

    data class Detecting(val fraction: Float, val framesSeen: Int, val facesSoFar: Int) : ScanStage

    data object Saving : ScanStage

    data class Failed(val failure: ScanFailure) : ScanStage
}

internal data class ScanUi(
    val thorough: Boolean,
    /** True when this scan replaces a project the user came from in the editor. */
    val isRescan: Boolean,
    val displayName: String?,
    val info: SourceVideoInfo?,
    val stage: ScanStage,
)

internal sealed interface PlanUi {
    data object Loading : PlanUi

    data class Ready(val plan: EncodePlan) : PlanUi

    /** This phone has no encoder for the video. */
    data object Unavailable : PlanUi

    /** Asking for the plan failed; the user can ask again. */
    data object Failed : PlanUi
}

internal enum class SheetProblem { PREPARE_FAILED, STORAGE_DENIED, START_FAILED }

internal data class ExportSheetUi(
    val edit: EditState,
    val quality: ExportQuality = ExportQuality.MATCH_SOURCE,
    val removeLocation: Boolean = false,
    val plan: PlanUi = PlanUi.Loading,
    /** Null until the mask timeline has been built. */
    val nothingBlurred: Boolean? = null,
    val problem: SheetProblem? = null,
) {
    val canSave: Boolean get() = plan is PlanUi.Ready && nothingBlurred != null
}

internal sealed interface ProjectListUi {
    data object Loading : ProjectListUi

    data class Loaded(val projects: List<ProjectSummary>) : ProjectListUi

    data object Failed : ProjectListUi
}

internal enum class HomeNotice { PROJECT_UNAVAILABLE, DELETE_FAILED, NO_PICKER }

internal data class HomeUi(
    val projects: ProjectListUi = ProjectListUi.Loading,
    val notice: HomeNotice? = null,
    /** The app's process ended while an export was running, so that video was never saved. */
    val exportInterrupted: Boolean = false,
)
