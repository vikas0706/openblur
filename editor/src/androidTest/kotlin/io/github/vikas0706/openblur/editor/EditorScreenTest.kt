package io.github.vikas0706.openblur.editor

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.Effect
import androidx.media3.effect.Presentation
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vikas0706.openblur.api.AppServices
import io.github.vikas0706.openblur.api.ExportController
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
import io.github.vikas0706.openblur.core.EditOps
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.FaceTrack
import io.github.vikas0706.openblur.core.ManualRegion
import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskTimeline
import io.github.vikas0706.openblur.core.MaskTimelineFactory
import io.github.vikas0706.openblur.core.Person
import io.github.vikas0706.openblur.core.RegionKeyframe
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.core.TransferKind
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real screen against fake services and a video file that does not exist, so everything
 * except playback itself is exercised: tabs, toggles, areas, undo, saving, the dialogs and the error state.
 */
@RunWith(AndroidJUnit4::class)
class EditorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val store = FakeProjectStore()
    private val exported = CopyOnWriteArrayList<EditState>()
    private var rescans = 0
    private var backs = 0

    private fun track(id: Int, personId: Int, startUs: Long, endUs: Long) = FaceTrack(
        id, personId, longArrayOf(startUs, endUs),
        floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f, 0.5f, 0.5f, 0.2f, 0.2f),
        FloatArray(8) { Float.NaN }, ByteArray(2), -1, 0.9f,
    )

    private fun result(withFaces: Boolean): AnalysisResult {
        val tracks = if (withFaces) listOf(track(1, 10, 0L, 4_000_000L), track(2, 20, 1_000_000L, 3_000_000L)) else emptyList()
        val people = tracks.map { Person(it.personId, intArrayOf(it.id), -1, it.startUs, it.endUs - it.startUs) }
        return AnalysisResult(1920, 1080, 10_000_000L, tracks, people, LongArray(0), 250, 250)
    }

    private fun show(withFaces: Boolean = true) {
        val info = SourceVideoInfo(
            mimeType = "video/avc", codedWidth = 1920, codedHeight = 1080, rotationDegrees = 0, frameRate = 25f,
            bitrate = 8_000_000, durationUs = 10_000_000L, frameCount = 250, keyframeIntervalSeconds = 1f,
            transfer = TransferKind.SDR, bitDepth = 8, isDolbyVision = false, audioMimeType = null,
            audioTrackCount = 0, hasLocation = false, fileSizeBytes = 0L,
        )
        val project = Project(
            id = "test",
            sourceUri = Uri.fromFile(File("/nonexistent/openblur-editor-test.mp4")),
            displayName = "Clip",
            info = info,
            options = AnalysisOptions.STANDARD,
            result = result(withFaces),
            edit = EditState(),
        )
        compose.setContent {
            OpenBlurTheme {
                EditorScreen(
                    project = project,
                    services = FakeServices(store),
                    onBack = { backs++ },
                    onExport = { exported += it },
                    onRescanThorough = { rescans++ },
                )
            }
        }
    }

    private fun stateIs(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    @Test
    fun missingSourceShowsTheErrorWithARetry() {
        show()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("This video can't be opened")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Try again").assertExists()
        compose.onNodeWithContentDescription("Play").assertIsNotEnabled()
    }

    @Test
    fun tappingAPersonShowsThemAndUndoBringsTheBlurBack() {
        show()
        compose.onNodeWithContentDescription("Undo").assertIsNotEnabled()
        compose.onNode(hasContentDescription("Person 1") and stateIs("Blurred")).performScrollTo().performClick()
        compose.onNode(hasContentDescription("Person 1") and stateIs("Not blurred")).assertExists()
        compose.onNodeWithContentDescription("Undo").assertIsEnabled().performClick()
        compose.onNode(hasContentDescription("Person 1") and stateIs("Blurred")).assertExists()
        compose.onNodeWithContentDescription("Redo").assertIsEnabled()
    }

    @Test
    fun editsAreStoredAfterAShortPause() {
        show()
        compose.onNodeWithText("Show everyone").performScrollTo().performClick()
        compose.waitUntil(5_000) { store.saved.isNotEmpty() }
        assertEquals(setOf(1, 2), store.saved.last().visibleTrackIds)
    }

    @Test
    fun noFacesPointsToTheTwoWaysForward() {
        show(withFaces = false)
        compose.onNodeWithText("No faces found").assertExists()
        compose.onNodeWithText("Scan more thoroughly").assertExists()
        compose.onNodeWithText("Add an area").performScrollTo().performClick()
        compose.onNodeWithText("Area 1").assertExists()
        compose.onNodeWithText("Delete area").performScrollTo().performClick()
        compose.onNodeWithText("Area 1").assertDoesNotExist()
    }

    @Test
    fun styleSaysWhoItAppliesTo() {
        show()
        compose.onNodeWithText("Style").performClick()
        compose.onNodeWithText("Applies to everyone").assertExists()
        compose.onNodeWithText("Areas").performClick()
        compose.onNodeWithText("Add area").performScrollTo().performClick()
        compose.onNodeWithText("Style").performClick()
        compose.onNodeWithText("Applies to Area 1 only").assertExists()
        compose.onNodeWithText("Eyes").assertDoesNotExist()
        compose.onNodeWithText("Change everyone instead").performScrollTo().performClick()
        compose.onNodeWithText("Applies to everyone").assertExists()
        compose.onNodeWithText("Eyes").assertExists()
    }

    @Test
    fun rescanAsksFirst() {
        show()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Scan again, more thoroughly").performClick()
        compose.onNodeWithText("Keep editing").performClick()
        assertEquals(0, rescans)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Scan again, more thoroughly").performClick()
        compose.onNodeWithText("Scan again").performClick()
        compose.runOnIdle { assertEquals(1, rescans) }
    }

    @Test
    fun saveVideoHandsOverTheCurrentEdit() {
        show()
        compose.onNodeWithText("Show everyone").performScrollTo().performClick()
        compose.onNodeWithText("Save video").performClick()
        compose.runOnIdle {
            assertEquals(1, exported.size)
            assertEquals(setOf(1, 2), exported[0].visibleTrackIds)
        }
    }

    @Test
    fun backArrowLeavesAndStoresTheEdit() {
        show()
        compose.onNodeWithText("Show everyone").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
        compose.waitUntil(5_000) { store.saved.isNotEmpty() }
        assertTrue(store.saved.last().visibleTrackIds.containsAll(setOf(1, 2)))
    }
}

private class FakeProjectStore : ProjectStore {
    val saved = CopyOnWriteArrayList<EditState>()

    override fun newProjectId(): String = "test"

    override fun thumbnails(projectId: String): ThumbnailFiles = object : ThumbnailFiles {
        override fun file(handle: Int): File? = null

        override fun put(width: Int, height: Int, rgb: ByteArray): Int = 0

        override fun release(handle: Int) {}
    }

    override suspend fun save(project: Project) {}

    override suspend fun saveEdit(projectId: String, edit: EditState) {
        saved += edit
    }

    override suspend fun load(projectId: String): Project? = null

    override suspend fun list(): List<ProjectSummary> = emptyList()

    override suspend fun delete(projectId: String) {}

    override suspend fun deleteAbandoned() {}
}

private class FakeServices(override val projects: ProjectStore) : AppServices {
    override val inspector: SourceInspector get() = error("The editor does not inspect sources")
    override val analyzer: VideoAnalyzer get() = error("The editor does not scan")
    override val exporter: ExportController get() = error("The editor does not export")

    override val maskEffects = object : MaskEffectFactory {
        override fun create() = object : MaskEffectController {
            // Never runs: the test video cannot be opened, so no frame reaches the effect chain.
            override val effect: Effect = Presentation.createForHeight(16)

            override fun setTimeline(timeline: MaskTimeline?) {}

            override fun setTimeOffsetUs(offsetUs: Long) {}
        }
    }

    override val timelines = object : MaskTimelineFactory {
        override fun build(result: AnalysisResult, edit: EditState) = object : MaskTimeline {
            override val durationUs = result.durationUs
            override val isEmpty = true

            override fun sample(timeUs: Long, out: MaskBatch, includeHidden: Boolean) = out.clear()
        }

        override fun regionAt(region: ManualRegion, timeUs: Long): RegionKeyframe = EditOps.regionAt(region, timeUs)
    }
}
