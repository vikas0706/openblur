package io.github.vikas0706.openblur.shell

import android.net.Uri
import androidx.media3.common.Effect
import io.github.vikas0706.openblur.api.AnalysisEvent
import io.github.vikas0706.openblur.api.AppServices
import io.github.vikas0706.openblur.api.ExportController
import io.github.vikas0706.openblur.api.ExportFailure
import io.github.vikas0706.openblur.api.ExportPhase
import io.github.vikas0706.openblur.api.ExportReport
import io.github.vikas0706.openblur.api.ExportRequest
import io.github.vikas0706.openblur.api.ExportState
import io.github.vikas0706.openblur.api.MaskEffectController
import io.github.vikas0706.openblur.api.MaskEffectFactory
import io.github.vikas0706.openblur.api.Project
import io.github.vikas0706.openblur.api.ProjectStore
import io.github.vikas0706.openblur.api.ProjectSummary
import io.github.vikas0706.openblur.api.SourceInspector
import io.github.vikas0706.openblur.api.ThumbnailFiles
import io.github.vikas0706.openblur.api.VideoAnalyzer
import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.EncodePlan
import io.github.vikas0706.openblur.core.ExportQuality
import io.github.vikas0706.openblur.core.FaceTrack
import io.github.vikas0706.openblur.core.ManualRegion
import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskTimeline
import io.github.vikas0706.openblur.core.MaskTimelineFactory
import io.github.vikas0706.openblur.core.Person
import io.github.vikas0706.openblur.core.PlanNotice
import io.github.vikas0706.openblur.core.RegionKeyframe
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.core.ThumbnailStore
import io.github.vikas0706.openblur.core.TransferKind
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/** In-memory services with scripted behaviour, so the shell can be driven without video files or codecs. */
class FakeAppServices : AppServices {
    val fakeInspector = FakeInspector()
    val fakeAnalyzer = FakeAnalyzer()
    val fakeTimelines = FakeTimelines()
    val fakeExporter = FakeExporter()
    val fakeProjects = FakeProjects()

    override val inspector: SourceInspector get() = fakeInspector
    override val analyzer: VideoAnalyzer get() = fakeAnalyzer
    override val timelines: MaskTimelineFactory get() = fakeTimelines
    override val exporter: ExportController get() = fakeExporter
    override val projects: ProjectStore get() = fakeProjects
    override val maskEffects: MaskEffectFactory = object : MaskEffectFactory {
        override fun create(): MaskEffectController = object : MaskEffectController {
            override val effect: Effect = object : Effect {}
            override fun setTimeline(timeline: MaskTimeline?) = Unit
            override fun setTimeOffsetUs(offsetUs: Long) = Unit
        }
    }
}

class FakeInspector : SourceInspector {
    var info: SourceVideoInfo = Samples.info()
    var name: String? = "holiday.mp4"
    var failure: Throwable? = null

    override suspend fun inspect(uri: Uri): SourceVideoInfo {
        failure?.let { throw it }
        return info
    }

    override suspend fun displayName(uri: Uri): String? = name
}

class FakeAnalyzer : VideoAnalyzer {
    var steps: List<AnalysisEvent.Progress> = listOf(
        AnalysisEvent.Progress(0.25f, 30, 1),
        AnalysisEvent.Progress(0.5f, 60, 2),
    )

    /** When set, the scan waits here after its progress steps, so a test can look at the progress screen. */
    var hold: CompletableDeferred<Unit>? = null
    var failure: Throwable? = null
    var result: AnalysisResult = Samples.result()

    var runs = 0
    var cancelled = 0
    var lastOptions: AnalysisOptions? = null

    override fun analyze(
        uri: Uri,
        info: SourceVideoInfo,
        options: AnalysisOptions,
        thumbnails: ThumbnailStore,
    ): Flow<AnalysisEvent> = flow {
        runs++
        lastOptions = options
        for (step in steps) emit(step)
        try {
            hold?.await()
        } catch (e: CancellationException) {
            cancelled++
            throw e
        }
        failure?.let { throw it }
        emit(AnalysisEvent.Finished(result))
    }
}

class FakeTimelines : MaskTimelineFactory {
    /** What the built timeline reports for isEmpty. */
    var nothingBlurred = false
    var failure: Throwable? = null
    var lastEdit: EditState? = null

    override fun build(result: AnalysisResult, edit: EditState): MaskTimeline {
        failure?.let { throw it }
        lastEdit = edit
        val empty = nothingBlurred
        return object : MaskTimeline {
            override val durationUs: Long = result.durationUs
            override val isEmpty: Boolean = empty
            override fun sample(timeUs: Long, out: MaskBatch, includeHidden: Boolean) = out.clear()
        }
    }

    override fun regionAt(region: ManualRegion, timeUs: Long): RegionKeyframe =
        region.keyframes.first().copy(timeUs = timeUs)
}

class FakeExporter : ExportController {
    private val mutableState = MutableStateFlow<ExportState>(ExportState.Idle)
    override val state: StateFlow<ExportState> get() = mutableState

    var plans: List<EncodePlan> = listOf(Samples.plan())
    var planFailure: Throwable? = null

    /** False makes start() do nothing, like a service that never comes up. */
    var acceptsStart = true
    var startFailure: RuntimeException? = null

    var lastRequest: ExportRequest? = null
    var planQualities = mutableListOf<ExportQuality>()
    var cancelCalls = 0
    var resetCalls = 0

    override suspend fun plan(info: SourceVideoInfo, quality: ExportQuality): List<EncodePlan> {
        planFailure?.let { throw it }
        planQualities += quality
        return if (quality == ExportQuality.MAXIMUM) plans.map { it.copy(bitrate = it.bitrate * 2) } else plans
    }

    override fun start(request: ExportRequest) {
        startFailure?.let { throw it }
        lastRequest = request
        if (!acceptsStart || mutableState.value is ExportState.Running) return
        mutableState.value = ExportState.Running(ExportPhase.ENCODING, 0.4f, plans.firstOrNull(), 65_000L)
    }

    override fun cancel() {
        cancelCalls++
        if (mutableState.value is ExportState.Running) mutableState.value = ExportState.Cancelled
    }

    override fun reset() {
        resetCalls++
        if (mutableState.value !is ExportState.Running) mutableState.value = ExportState.Idle
    }

    fun emit(state: ExportState) {
        mutableState.value = state
    }

    fun succeed(report: ExportReport = Samples.report()) {
        mutableState.value = ExportState.Succeeded(report)
    }

    fun fail(failure: ExportFailure, detail: String) {
        mutableState.value = ExportState.Failed(failure, detail)
    }
}

class FakeProjects : ProjectStore {
    private val saved = LinkedHashMap<String, Project>()
    private var nextId = 1

    var listFailure: Throwable? = null
    var saveFailure: Throwable? = null
    var deleteFailure: Throwable? = null
    var deleteAbandonedCalls = 0
    val savedEdits = mutableListOf<Pair<String, EditState>>()

    /** Ids that load() reports as gone, as when the source video was deleted. */
    val unavailable = mutableSetOf<String>()

    fun put(project: Project) {
        saved[project.id] = project
    }

    fun ids(): Set<String> = saved.keys.toSet()

    fun get(id: String): Project? = saved[id]

    override fun newProjectId(): String = "scan-" + nextId++

    override fun thumbnails(projectId: String): ThumbnailFiles = object : ThumbnailFiles {
        private var next = 0
        override fun put(width: Int, height: Int, rgb: ByteArray): Int = next++
        override fun release(handle: Int) = Unit
        override fun file(handle: Int): File? = null
    }

    override suspend fun save(project: Project) {
        saveFailure?.let { throw it }
        saved[project.id] = project
    }

    override suspend fun saveEdit(projectId: String, edit: EditState) {
        savedEdits += projectId to edit
        val old = saved[projectId] ?: return
        saved[projectId] = Project(old.id, old.sourceUri, old.displayName, old.info, old.options, old.result, edit)
    }

    override suspend fun load(projectId: String): Project? =
        if (projectId in unavailable) null else saved[projectId]

    override suspend fun list(): List<ProjectSummary> {
        listFailure?.let { throw it }
        return saved.values.reversed().map {
            ProjectSummary(it.id, it.displayName, it.result.durationUs, it.result.people.size, null, 0L)
        }
    }

    override suspend fun delete(projectId: String) {
        deleteFailure?.let { throw it }
        saved.remove(projectId)
    }

    override suspend fun deleteAbandoned() {
        deleteAbandonedCalls++
    }
}

object Samples {
    val sourceUri: Uri = Uri.parse("content://media/external/video/media/41")
    val outputUri: Uri = Uri.parse("content://media/external/video/media/42")

    fun info(
        mimeType: String = "video/avc",
        bitrate: Int = 10_000_000,
        fileSizeBytes: Long = 39_000_000L,
        hasLocation: Boolean = true,
    ) = SourceVideoInfo(
        mimeType = mimeType,
        codedWidth = 1920,
        codedHeight = 1080,
        rotationDegrees = 0,
        frameRate = 29.97f,
        bitrate = bitrate,
        durationUs = 30_000_000L,
        frameCount = 899,
        keyframeIntervalSeconds = 1f,
        transfer = TransferKind.SDR,
        bitDepth = 8,
        isDolbyVision = false,
        audioMimeType = "audio/mp4a-latm",
        audioTrackCount = 1,
        hasLocation = hasLocation,
        fileSizeBytes = fileSizeBytes,
    )

    fun plan(notices: Set<PlanNotice> = setOf(PlanNotice.SLOWER_THAN_REAL_TIME)) = EncodePlan(
        mimeType = "video/avc",
        bitrate = 12_000_000,
        iFrameIntervalSeconds = 1f,
        keepHdr = false,
        strictSize = true,
        notices = notices,
    )

    fun result(): AnalysisResult {
        val track = { id: Int, person: Int ->
            FaceTrack(
                id = id,
                personId = person,
                timesUs = longArrayOf(0L, 1_000_000L),
                boxes = floatArrayOf(0.5f, 0.5f, 0.1f, 0.1f, 0.5f, 0.5f, 0.1f, 0.1f),
                eyes = FloatArray(8) { Float.NaN },
                sampleKinds = byteArrayOf(FaceTrack.SAMPLE_DETECTED, FaceTrack.SAMPLE_DETECTED),
                thumbnailHandle = -1,
                meanScore = 0.9f,
            )
        }
        return AnalysisResult(
            frameWidth = 1920,
            frameHeight = 1080,
            durationUs = 30_000_000L,
            tracks = listOf(track(1, 1), track(2, 2)),
            people = listOf(
                Person(1, intArrayOf(1), -1, 0L, 1_000_000L),
                Person(2, intArrayOf(2), -1, 0L, 1_000_000L),
            ),
            cutsUs = LongArray(0),
            framesSeen = 899,
            framesDetected = 899,
        )
    }

    fun project(id: String = "p1", name: String = "holiday.mp4", edit: EditState = EditState()) = Project(
        id = id,
        sourceUri = sourceUri,
        displayName = name,
        info = info(),
        options = AnalysisOptions.STANDARD,
        result = result(),
        edit = edit,
    )

    fun report(notices: Set<PlanNotice> = setOf(PlanNotice.SLOWER_THAN_REAL_TIME)) = ExportReport(
        outputUri = outputUri,
        displayName = "holiday blurred",
        source = info(),
        output = info(bitrate = 12_000_000, fileSizeBytes = 46_500_000L, hasLocation = false),
        plan = plan(notices),
        notices = notices,
        audioCopied = true,
        elapsedMs = 72_000L,
    )
}
