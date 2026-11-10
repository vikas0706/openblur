package io.github.vikas0706.openblur.api

import android.net.Uri
import androidx.media3.common.Effect
import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.EncodePlan
import io.github.vikas0706.openblur.core.ExportQuality
import io.github.vikas0706.openblur.core.MaskTimeline
import io.github.vikas0706.openblur.core.MaskTimelineFactory
import io.github.vikas0706.openblur.core.PlanNotice
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.core.ThumbnailStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the UI needs, built once by the Application. The UI depends on these interfaces only. */
interface AppServices {
    val inspector: SourceInspector
    val analyzer: VideoAnalyzer
    val maskEffects: MaskEffectFactory
    val timelines: MaskTimelineFactory
    val exporter: ExportController
    val projects: ProjectStore
}

/** Implemented by the Application class. */
interface AppServicesOwner {
    val services: AppServices
}

class UnreadableVideoException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface SourceInspector {
    /** Main-safe. Throws [UnreadableVideoException] when the Uri cannot be opened or has no video track. */
    suspend fun inspect(uri: Uri): SourceVideoInfo

    /** The file name the provider reports, or null. Main-safe. */
    suspend fun displayName(uri: Uri): String?
}

sealed interface AnalysisEvent {
    /** [fraction] is 0..1 of the video's duration. */
    data class Progress(val fraction: Float, val framesSeen: Int, val facesSoFar: Int) : AnalysisEvent

    data class Finished(val result: AnalysisResult) : AnalysisEvent
}

class AnalysisException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { SOURCE_UNREADABLE, DECODER_FAILED, MODEL_FAILED, OUT_OF_MEMORY, UNKNOWN }
}

interface VideoAnalyzer {
    /**
     * A cold flow: collecting starts the scan, cancelling the collector stops it and frees the decoder.
     * Emits [AnalysisEvent.Progress] a few times per second, then one [AnalysisEvent.Finished], then completes.
     * Fails with [AnalysisException]. Safe to collect from the main dispatcher; the work runs elsewhere.
     */
    fun analyze(
        uri: Uri,
        info: SourceVideoInfo,
        options: AnalysisOptions,
        thumbnails: ThumbnailStore,
    ): Flow<AnalysisEvent>
}

/** One GL effect instance and the knobs to drive it. Use one controller per player or per export. */
interface MaskEffectController {
    /** Pass to ExoPlayer.setVideoEffects or to an export. Frames arrive upright, in display orientation. */
    val effect: Effect

    /** Applies from the next frame the pipeline renders. Null draws nothing. Callable from any thread. */
    fun setTimeline(timeline: MaskTimeline?)

    /** Added to each frame's presentation time before the timeline lookup. Callable from any thread. */
    fun setTimeOffsetUs(offsetUs: Long)
}

interface MaskEffectFactory {
    fun create(): MaskEffectController
}

/** A [ThumbnailStore] whose pictures the UI can load from disk. */
interface ThumbnailFiles : ThumbnailStore {
    /** The image file for [handle], or null when the handle is unknown or was released. */
    fun file(handle: Int): File?
}

class Project(
    val id: String,
    val sourceUri: Uri,
    val displayName: String,
    val info: SourceVideoInfo,
    val options: AnalysisOptions,
    val result: AnalysisResult,
    val edit: EditState,
)

data class ProjectSummary(
    val id: String,
    val displayName: String,
    val durationUs: Long,
    val peopleCount: Int,
    /** A face-free frame is not available, so this is the first person's thumbnail, or null. */
    val cover: File?,
    val updatedAtMs: Long,
)

/** Scans saved on this device so a video can be reopened without scanning again. All functions are main-safe. */
interface ProjectStore {
    /** Reserves an id. The project is not listed until [save] is called for it. */
    fun newProjectId(): String

    /** Thumbnail storage for one project; usable before [save]. */
    fun thumbnails(projectId: String): ThumbnailFiles

    suspend fun save(project: Project)

    suspend fun saveEdit(projectId: String, edit: EditState)

    /** Null when the project is missing, unreadable, or its source video can no longer be opened. */
    suspend fun load(projectId: String): Project?

    /** Newest first. */
    suspend fun list(): List<ProjectSummary>

    suspend fun delete(projectId: String)

    /** Removes folders of scans that were started but never saved. */
    suspend fun deleteAbandoned()

    /**
     * Removes the folder of [projectId] if it was never saved, for a scan that has ended without a result.
     * Does nothing for a saved project.
     */
    suspend fun discardUnsaved(projectId: String) {
        deleteAbandoned()
    }
}

data class ExportRequest(
    val sourceUri: Uri,
    /** File name without extension for the saved video. */
    val displayName: String,
    val info: SourceVideoInfo,
    val timeline: MaskTimeline,
    val quality: ExportQuality,
    val removeLocation: Boolean,
)

enum class ExportPhase { PREPARING, ENCODING, SAVING }

enum class ExportFailure {
    NO_ENCODER,
    SOURCE_UNREADABLE,
    DECODER_FAILED,
    ENCODER_FAILED,
    OUT_OF_SPACE,
    SAVE_FAILED,
    TIME_LIMIT,
    UNKNOWN,
}

data class ExportReport(
    /** MediaStore Uri of the saved video. */
    val outputUri: Uri,
    val displayName: String,
    val source: SourceVideoInfo,
    /** Read back from the saved file, not copied from the request. */
    val output: SourceVideoInfo,
    /** The plan that produced the file. */
    val plan: EncodePlan,
    /** Everything that differs from the source, including changes found only after encoding. */
    val notices: Set<PlanNotice>,
    /** True when the audio samples were copied without re-encoding. Meaningless when the source has no audio. */
    val audioCopied: Boolean,
    val elapsedMs: Long,
)

sealed interface ExportState {
    data object Idle : ExportState

    /** [progress] is 0..1. [plan] is null until the encoder has been chosen. */
    data class Running(val phase: ExportPhase, val progress: Float, val plan: EncodePlan?, val elapsedMs: Long) :
        ExportState

    data class Succeeded(val report: ExportReport) : ExportState

    /** [detail] is technical text for a bug report, not for display as the main message. */
    data class Failed(val failure: ExportFailure, val detail: String) : ExportState

    data object Cancelled : ExportState
}

/** Runs one export at a time in a foreground service, so it survives the app leaving the screen. */
interface ExportController {
    val state: StateFlow<ExportState>

    /** Plans the export would try for this source, best first; empty when this device cannot encode it. Main-safe. */
    suspend fun plan(info: SourceVideoInfo, quality: ExportQuality): List<EncodePlan>

    /** Call from the main thread while the app is visible. Ignored while an export is running. */
    fun start(request: ExportRequest)

    fun cancel()

    /** Moves a finished, failed or cancelled state back to [ExportState.Idle]. */
    fun reset()

    /**
     * True when an export was still running when the app's process last ended, so its video was never saved.
     * Becomes true shortly after start, once leftovers have been checked, and stays true until [dismissInterrupted].
     */
    val interrupted: StateFlow<Boolean> get() = NeverInterrupted

    fun dismissInterrupted() {}
}

private val NeverInterrupted: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
