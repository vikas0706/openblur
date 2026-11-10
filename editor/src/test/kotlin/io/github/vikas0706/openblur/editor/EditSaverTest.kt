package io.github.vikas0706.openblur.editor

import io.github.vikas0706.openblur.api.Project
import io.github.vikas0706.openblur.api.ProjectStore
import io.github.vikas0706.openblur.api.ProjectSummary
import io.github.vikas0706.openblur.api.ThumbnailFiles
import io.github.vikas0706.openblur.core.EditState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

private class RecordingStore : ProjectStore {
    val writes = ArrayList<EditState>()

    override fun newProjectId(): String = "p"

    override fun thumbnails(projectId: String): ThumbnailFiles = throw UnsupportedOperationException()

    override suspend fun save(project: Project) {}

    override suspend fun saveEdit(projectId: String, edit: EditState) {
        writes += edit
    }

    override suspend fun load(projectId: String): Project? = null

    override suspend fun list(): List<ProjectSummary> = emptyList()

    override suspend fun delete(projectId: String) {}

    override suspend fun deleteAbandoned() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class EditSaverTest {
    // The saver's detached scope runs on Main, which plain JVM tests lack.
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val loaded = EditState()
    private val changed = EditState(visibleTrackIds = setOf(3))

    @Test
    fun anUndoBackToTheLoadedEditIsStoredAfterTheScreenComesBack() = runTest {
        val store = RecordingStore()
        val holder = EditorSessionHolder()
        holder.saverFor(store, "p", loaded).save(changed)

        // The host hands the screen the project as it was loaded, while the kept session holds the newer edit.
        val again = holder.saverFor(store, "p", loaded)
        again.save(loaded)
        assertEquals(listOf(changed, loaded), store.writes)
    }

    @Test
    fun aSaverIsOnlyReusedForTheSameProjectAndStore() {
        val store = RecordingStore()
        val holder = EditorSessionHolder()
        val first = holder.saverFor(store, "p", loaded)
        assertSame(first, holder.saverFor(store, "p", changed))
        assertNotSame(first, holder.saverFor(store, "q", loaded))
        assertNotSame(first, holder.saverFor(RecordingStore(), "q", loaded))
    }

    @Test
    fun theSameEditIsNotWrittenTwice() = runTest {
        val store = RecordingStore()
        val saver = EditSaver(store, "p", loaded)
        saver.save(loaded)
        saver.save(changed)
        saver.save(changed)
        assertEquals(listOf(changed), store.writes)
    }
}
