package io.github.vikas0706.openblur.analysis

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vikas0706.openblur.api.Project
import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.EditState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileProjectStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val projectsFolder = File(context.filesDir, "projects")
    private val source = File(context.cacheDir, "store-test-source.mp4")
    private var now = 1_794_300_000_000L

    private fun store() = FileProjectStore(context, FakeResultCodec(), FakeEditCodec()) { now }

    private fun project(
        id: String,
        result: AnalysisResult = Fixtures.result(),
        edit: EditState = Fixtures.edit(),
        sourceUri: Uri = Uri.fromFile(source),
        name: String = "Clip one.mp4",
    ) = Project(id, sourceUri, name, Fixtures.info(), AnalysisOptions.THOROUGH, result, edit)

    /** Four 8x8 quadrants: red, green, blue, white, reading left to right and top to bottom. */
    private fun quadrants(): ByteArray {
        val rgb = ByteArray(16 * 16 * 3)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val color = when {
                    y < 8 && x < 8 -> Color.RED
                    y < 8 -> Color.GREEN
                    x < 8 -> Color.BLUE
                    else -> Color.WHITE
                }
                val at = (y * 16 + x) * 3
                rgb[at] = Color.red(color).toByte()
                rgb[at + 1] = Color.green(color).toByte()
                rgb[at + 2] = Color.blue(color).toByte()
            }
        }
        return rgb
    }

    @Before
    fun setUp() {
        projectsFolder.deleteRecursively()
        source.writeBytes(ByteArray(64) { it.toByte() })
    }

    @After
    fun tearDown() {
        projectsFolder.deleteRecursively()
        source.delete()
    }

    @Test
    fun savedProjectLoadsWithEveryField() = runBlocking {
        val store = store()
        val id = store.newProjectId()
        store.save(project(id))

        val loaded = store.load(id)
        assertNotNull(loaded)
        loaded!!
        assertEquals(id, loaded.id)
        assertEquals(Uri.fromFile(source), loaded.sourceUri)
        assertEquals("Clip one.mp4", loaded.displayName)
        assertEquals(Fixtures.info(), loaded.info)
        assertEquals(AnalysisOptions.THOROUGH, loaded.options)
        assertTrue(Fixtures.sameResult(Fixtures.result(), loaded.result))
        assertEquals(Fixtures.edit(), loaded.edit)
        assertTrue(File(projectsFolder, id).isDirectory)
    }

    @Test
    fun aNewStoreInstanceSeesEarlierProjects() = runBlocking {
        val id = store().let { store -> store.newProjectId().also { store.save(project(it)) } }
        val reopened = store()
        assertNotNull(reopened.load(id))
        assertEquals(listOf(id), reopened.list().map { it.id })
    }

    @Test
    fun saveEditIsWhatTheNextLoadReturns() = runBlocking {
        val store = store()
        val id = store.newProjectId()
        store.save(project(id))
        store.saveEdit(id, EditState(visibleTrackIds = setOf(1)))
        store.saveEdit(id, EditState(visibleTrackIds = setOf(1, 2)))
        assertEquals(EditState(visibleTrackIds = setOf(1, 2)), store.load(id)!!.edit)
    }

    @Test
    fun projectIsNotListedBeforeItIsSaved() = runBlocking {
        val store = store()
        val id = store.newProjectId()
        store.thumbnails(id).put(16, 16, quadrants())
        assertEquals(emptyList<String>(), store.list().map { it.id })
        assertNull(store.load(id))
        store.save(project(id))
        assertEquals(listOf(id), store.list().map { it.id })
    }

    @Test
    fun loadIsNullWhileTheSourceFileIsGoneAndWorksAgainWhenItReturns() = runBlocking {
        val store = store()
        val id = store.newProjectId()
        store.save(project(id))

        assertTrue(source.delete())
        assertNull(store.load(id))
        assertEquals("the project itself is kept", listOf(id), store.list().map { it.id })

        source.writeBytes(ByteArray(8))
        assertNotNull(store.load(id))
    }

    @Test
    fun loadIsNullForAContentUriNoProviderAnswers() = runBlocking {
        val store = store()
        val id = store.newProjectId()
        val gone = Uri.parse("content://io.github.vikas0706.openblur.analysis.missing/video/7")
        store.save(project(id, sourceUri = gone))
        assertNull(store.load(id))
        assertEquals(listOf(id), store.list().map { it.id })
    }

    @Test
    fun damagedProjectIsRemoved() = runBlocking {
        val store = store()
        val id = store.newProjectId()
        store.save(project(id))
        File(projectsFolder, "$id/result-1.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.load(id))
        assertFalse(File(projectsFolder, id).exists())
        assertEquals(emptyList<String>(), store.list().map { it.id })
    }

    @Test
    fun thumbnailsAreJpegFilesWithTheTopRowFirst() {
        val store = store()
        val id = store.newProjectId()
        val pictures = store.thumbnails(id)
        val handle = pictures.put(16, 16, quadrants())
        assertTrue(handle >= 0)

        val file = pictures.file(handle)
        assertNotNull(file)
        val head = file!!.inputStream().use { stream -> ByteArray(3).also { stream.read(it) } }
        assertEquals("JPEG files start with FF D8 FF", listOf(0xFF, 0xD8, 0xFF), head.map { it.toInt() and 0xFF })

        val bitmap = BitmapFactory.decodeFile(file.path)
        assertNotNull(bitmap)
        assertEquals(16, bitmap.width)
        assertEquals(16, bitmap.height)
        // Quadrant centres, away from the edges that JPEG chroma subsampling smears.
        assertDominant(bitmap.getPixel(3, 3), red = true, green = false, blue = false)
        assertDominant(bitmap.getPixel(12, 3), red = false, green = true, blue = false)
        assertDominant(bitmap.getPixel(3, 12), red = false, green = false, blue = true)
        assertDominant(bitmap.getPixel(12, 12), red = true, green = true, blue = true)
    }

    private fun assertDominant(pixel: Int, red: Boolean, green: Boolean, blue: Boolean) {
        val label = "pixel #${Integer.toHexString(pixel)}"
        assertEquals("$label red", red, Color.red(pixel) > 128)
        assertEquals("$label green", green, Color.green(pixel) > 128)
        assertEquals("$label blue", blue, Color.blue(pixel) > 128)
    }

    @Test
    fun thumbnailHandlesContinueAfterReopeningAndReleaseDeletesTheFile() {
        val id = store().newProjectId()
        val first = store().thumbnails(id)
        val a = first.put(16, 16, quadrants())
        val b = first.put(16, 16, quadrants())
        assertEquals(listOf(0, 1), listOf(a, b))

        val second = store().thumbnails(id)
        assertNotNull(second.file(a))
        assertEquals(2, second.put(16, 16, quadrants()))
        second.release(a)
        assertNull(second.file(a))
        assertFalse(File(projectsFolder, "$id/thumbs/0.jpg").exists())
        assertNull(second.file(99))
        assertNull(second.file(-1))
    }

    @Test
    fun thumbnailStoreIsTheSameObjectForOneProject() {
        val store = store()
        val id = store.newProjectId()
        assertTrue(store.thumbnails(id) === store.thumbnails(id))
    }

    @Test
    fun listIsNewestFirstWithTheFirstPersonsPictureAsCover() = runBlocking {
        val store = store()
        val older = store.newProjectId()
        val handle = store.thumbnails(older).put(16, 16, quadrants())
        store.save(project(older, result = Fixtures.result(listOf(handle)), name = "Older.mp4"))
        now += 60_000L
        val newer = store.newProjectId()
        store.save(project(newer, result = Fixtures.result(emptyList(), durationUs = 7L), name = "Newer.mp4"))

        val summaries = store.list()
        assertEquals(listOf(newer, older), summaries.map { it.id })
        assertEquals(listOf("Newer.mp4", "Older.mp4"), summaries.map { it.displayName })
        assertEquals(listOf(0, 1), summaries.map { it.peopleCount })
        assertEquals(listOf(7L, 12_500_000L), summaries.map { it.durationUs })
        assertEquals(listOf(now, now - 60_000L), summaries.map { it.updatedAtMs })
        assertNull(summaries[0].cover)
        assertEquals(store.thumbnails(older).file(handle), summaries[1].cover)

        now += 60_000L
        store.saveEdit(older, EditState())
        assertEquals(listOf(older, newer), store.list().map { it.id })
    }

    @Test
    fun deleteRemovesTheProjectAndItsPictures() = runBlocking {
        val store = store()
        val id = store.newProjectId()
        val handle = store.thumbnails(id).put(16, 16, quadrants())
        store.save(project(id, result = Fixtures.result(listOf(handle))))
        store.delete(id)
        assertNull(store.load(id))
        assertEquals(emptyList<String>(), store.list().map { it.id })
        assertFalse(File(projectsFolder, id).exists())
        store.delete(id)
    }

    @Test
    fun deleteAbandonedRemovesOldUnsavedFoldersOnly() = runBlocking {
        val earlier = store()
        val abandoned = earlier.newProjectId()
        val saved = earlier.newProjectId()
        earlier.thumbnails(abandoned).put(16, 16, quadrants())
        earlier.save(project(saved))

        // A new instance stands for the next app start, which has handed out neither id.
        val store = store()
        now = System.currentTimeMillis() + 30 * 60 * 1000L
        store.deleteAbandoned()
        assertTrue(File(projectsFolder, abandoned).exists())

        now = System.currentTimeMillis() + 2 * 60 * 60 * 1000L
        store.deleteAbandoned()
        assertFalse(File(projectsFolder, abandoned).exists())
        assertNotNull(store.load(saved))
    }

    @Test
    fun deleteAbandonedSparesAScanThatIsStillRunningHere() = runBlocking {
        val store = store()
        val scanning = store.newProjectId()
        store.thumbnails(scanning).put(16, 16, quadrants())
        now = System.currentTimeMillis() + 6 * 60 * 60 * 1000L
        store.deleteAbandoned()
        assertNotNull(store.thumbnails(scanning).file(0))
    }
}
