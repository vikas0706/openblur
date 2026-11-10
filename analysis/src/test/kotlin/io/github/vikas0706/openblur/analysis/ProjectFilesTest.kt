package io.github.vikas0706.openblur.analysis

import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.AnalysisResultCodec
import io.github.vikas0706.openblur.core.EditState
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    // Whole seconds, so file times set from this clock survive file systems with coarse timestamps.
    private var now = 1_794_300_000_000L

    private val encoder = ThumbnailEncoder { width, height, rgb, out -> out.write(rgb, 0, width * height * 3) }

    private val root: File get() = File(temp.root, "projects")

    private fun open(resultCodec: AnalysisResultCodec = FakeResultCodec()) =
        ProjectFiles(root, resultCodec, FakeEditCodec(), encoder, Dispatchers.IO) { now }

    private suspend fun ProjectFiles.saveSample(
        id: String,
        result: AnalysisResult = Fixtures.result(),
        edit: EditState = Fixtures.edit(),
        name: String = "clip.mp4",
    ) = save(id, SOURCE, name, Fixtures.info(), AnalysisOptions.THOROUGH, result, edit)

    private fun names(folder: File): List<String> = folder.list().orEmpty().sorted()

    private fun thumbnail() = ByteArray(2 * 2 * 3) { it.toByte() }

    @Test
    fun saveThenLoadReturnsEverything() = runBlocking {
        val files = open()
        val id = files.newId()
        files.saveSample(id)

        val loaded = present(files.load(id) { true })
        assertTrue(Fixtures.sameResult(Fixtures.result(), loaded.result))
        assertEquals(Fixtures.edit(), loaded.edit)
        assertEquals(SOURCE, loaded.meta.sourceUri)
        assertEquals("clip.mp4", loaded.meta.displayName)
        assertEquals(Fixtures.info(), loaded.meta.info)
        assertEquals(AnalysisOptions.THOROUGH, loaded.meta.options)
        assertEquals(now, loaded.meta.createdAtMs)
        assertEquals(2, loaded.meta.peopleCount)
        assertEquals(listOf("edit-1.bin", "meta.bin", "result-1.bin"), names(File(root, id)))
    }

    @Test
    fun aSecondStoreInstanceReadsWhatTheFirstSaved() = runBlocking {
        val id = open().let { files -> files.newId().also { files.saveSample(it) } }
        val loaded = present(open().load(id) { true })
        assertTrue(Fixtures.sameResult(Fixtures.result(), loaded.result))
    }

    @Test
    fun unknownAndUnsavedProjectsLoadAsNullAndAreLeftAlone() = runBlocking {
        val files = open()
        assertNull(files.load("no-such-project") { true })

        val scanning = files.newId()
        files.thumbnails(scanning).put(2, 2, thumbnail())
        assertNull(files.load(scanning) { true })
        assertTrue("a scan in progress must keep its pictures", File(root, "$scanning/thumbs/0.jpg").isFile)
    }

    @Test
    fun unreadableSourceLoadsAsNullButKeepsTheProject() = runBlocking {
        val files = open()
        val id = files.newId()
        files.saveSample(id)
        var asked: String? = null
        assertNull(files.load(id) { asked = it; false })
        assertEquals(SOURCE, asked)
        assertNotNull(files.load(id) { true })
    }

    @Test
    fun damagedScanResultRemovesTheProject() = runBlocking {
        val files = open()
        val id = files.newId()
        files.saveSample(id)
        File(root, "$id/result-1.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(files.load(id) { true })
        assertFalse(File(root, id).exists())
        assertEquals(emptyList<Any>(), files.list())
    }

    @Test
    fun missingScanResultRemovesTheProject() = runBlocking {
        val files = open()
        val id = files.newId()
        files.saveSample(id)
        assertTrue(File(root, "$id/result-1.bin").delete())
        assertNull(files.load(id) { true })
        assertFalse(File(root, id).exists())
    }

    @Test
    fun damagedMetadataRemovesTheProject() = runBlocking {
        val files = open()
        val id = files.newId()
        files.saveSample(id)
        val meta = File(root, "$id/meta.bin")
        meta.writeBytes(meta.readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() })
        assertNull(files.load(id) { true })
        assertFalse(File(root, id).exists())
    }

    @Test
    fun damagedEditFallsBackToBlurEverything() = runBlocking {
        val files = open()
        val id = files.newId()
        files.saveSample(id)
        File(root, "$id/edit-1.bin").writeBytes(byteArrayOf(9, 9))

        val loaded = present(files.load(id) { true })
        assertEquals(EditState(), loaded.edit)
        assertTrue(Fixtures.sameResult(Fixtures.result(), loaded.result))

        files.saveEdit(id, Fixtures.edit(setOf(1)))
        assertEquals(Fixtures.edit(setOf(1)), files.load(id) { true }!!.edit)
    }

    @Test
    fun saveEditReplacesTheEditOnly() = runBlocking {
        val files = open()
        val id = files.newId()
        files.saveSample(id)
        val resultBytes = File(root, "$id/result-1.bin").readBytes()
        val metaBytes = File(root, "$id/meta.bin").readBytes()

        files.saveEdit(id, EditState(visibleTrackIds = setOf(1, 2)))

        assertEquals(EditState(visibleTrackIds = setOf(1, 2)), files.load(id) { true }!!.edit)
        assertTrue(resultBytes.contentEquals(File(root, "$id/result-1.bin").readBytes()))
        assertTrue(metaBytes.contentEquals(File(root, "$id/meta.bin").readBytes()))
        assertEquals(listOf("edit-1.bin", "meta.bin", "result-1.bin"), names(File(root, id)))
    }

    @Test
    fun saveEditForAnUnsavedOrDeletedProjectDoesNothing() = runBlocking {
        val files = open()
        val unsaved = files.newId()
        files.saveEdit(unsaved, Fixtures.edit())
        assertFalse(File(root, unsaved).exists())

        val deleted = files.newId()
        files.saveSample(deleted)
        files.delete(deleted)
        files.saveEdit(deleted, Fixtures.edit())
        assertFalse("a late edit must not bring a deleted project back", File(root, deleted).exists())
    }

    @Test
    fun saveEditCallsLandInCallOrder() = runBlocking {
        val files = open()
        val id = files.newId()
        files.saveSample(id)
        repeat(5) { round ->
            val jobs = (1..40).map { value ->
                launch { files.saveEdit(id, EditState(visibleTrackIds = setOf(round * 100 + value))) }
            }
            jobs.joinAll()
            assertEquals(setOf(round * 100 + 40), files.load(id) { true }!!.edit.visibleTrackIds)
        }
    }

    @Test
    fun savingAgainReplacesTheScanInOneStep() = runBlocking {
        val files = open()
        val id = files.newId()
        val firstSave = now
        files.saveSample(id)
        files.saveEdit(id, EditState(visibleTrackIds = setOf(1)))

        now += 60_000L
        val rescan = Fixtures.result(thumbnails = listOf(5), durationUs = 99L)
        files.saveSample(id, result = rescan, edit = EditState())

        val loaded = present(files.load(id) { true })
        assertTrue(Fixtures.sameResult(rescan, loaded.result))
        assertEquals(EditState(), loaded.edit)
        assertEquals(2, loaded.meta.generation)
        assertEquals(firstSave, loaded.meta.createdAtMs)
        assertEquals(now, loaded.meta.updatedAtMs)
        assertEquals(listOf("edit-2.bin", "meta.bin", "result-2.bin"), names(File(root, id)))

        files.saveEdit(id, Fixtures.edit())
        assertEquals(Fixtures.edit(), files.load(id) { true }!!.edit)
    }

    @Test
    fun aSaveThatFailsHalfwayLeavesThePreviousProjectIntact() = runBlocking {
        val failing = FailingResultCodec()
        val files = open(failing)
        val id = files.newId()
        files.saveSample(id)

        failing.failWrites = true
        val rescan = Fixtures.result(thumbnails = listOf(5), durationUs = 99L)
        assertThrows(IOException::class.java) { runBlocking { files.saveSample(id, result = rescan) } }

        failing.failWrites = false
        val loaded = present(files.load(id) { true })
        assertTrue(Fixtures.sameResult(Fixtures.result(), loaded.result))
        assertEquals(1, loaded.meta.generation)
        assertEquals(listOf("edit-1.bin", "meta.bin", "result-1.bin"), names(File(root, id)))
    }

    @Test
    fun aFirstSaveThatFailsLeavesNoListedProject() = runBlocking {
        val failing = FailingResultCodec().apply { failWrites = true }
        val files = open(failing)
        val id = files.newId()
        assertThrows(IOException::class.java) { runBlocking { files.saveSample(id) } }
        assertNull(files.load(id) { true })
        assertEquals(emptyList<Any>(), files.list())
        assertEquals(emptyList<String>(), names(File(root, id)))
    }

    @Test
    fun listIsNewestFirstAndAnEditCountsAsAChange() = runBlocking {
        val files = open()
        val older = files.newId()
        val newer = files.newId()
        files.saveSample(older, name = "older.mp4")
        now += 10_000L
        files.saveSample(newer, name = "newer.mp4", result = Fixtures.result(listOf(0, 1, 2), durationUs = 5L))

        val before = files.list()
        assertEquals(listOf(newer, older), before.map { it.id })
        assertEquals(listOf("newer.mp4", "older.mp4"), before.map { it.displayName })
        assertEquals(listOf(5L, 12_500_000L), before.map { it.durationUs })
        assertEquals(listOf(3, 2), before.map { it.peopleCount })
        assertEquals(listOf(now, now - 10_000L), before.map { it.updatedAtMs })

        now += 10_000L
        files.saveEdit(older, EditState(visibleTrackIds = setOf(1)))
        val after = files.list()
        assertEquals(listOf(older, newer), after.map { it.id })
        assertEquals(now, after[0].updatedAtMs)
    }

    @Test
    fun listSkipsUnsavedFoldersAndRemovesDamagedOnes() = runBlocking {
        val files = open()
        val good = files.newId()
        val damaged = files.newId()
        val scanning = files.newId()
        files.saveSample(good)
        files.saveSample(damaged)
        files.thumbnails(scanning).put(2, 2, thumbnail())
        File(root, "$damaged/meta.bin").writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))
        File(root, "stray-file.txt").writeText("not a project")

        assertEquals(listOf(good), files.list().map { it.id })
        assertFalse(File(root, damaged).exists())
        assertTrue(File(root, scanning).isDirectory)
        assertTrue(File(root, "stray-file.txt").isFile)
    }

    @Test
    fun listReportsTheFirstPersonsPictureAsCover() = runBlocking {
        val files = open()
        val withCover = files.newId()
        val pictures = files.thumbnails(withCover)
        val first = pictures.put(2, 2, thumbnail())
        val second = pictures.put(2, 2, thumbnail())
        files.saveSample(withCover, result = Fixtures.result(listOf(second, first)))

        now += 10_000L
        val withoutPicture = files.newId()
        files.saveSample(withoutPicture, result = Fixtures.result(listOf(40)))
        now += 10_000L
        val withoutPeople = files.newId()
        files.saveSample(withoutPeople, result = Fixtures.result(emptyList()))

        val summaries = files.list().associateBy { it.id }
        assertEquals(pictures.file(second), summaries.getValue(withCover).cover)
        assertNull(summaries.getValue(withoutPicture).cover)
        assertNull(summaries.getValue(withoutPeople).cover)
        assertEquals(0, summaries.getValue(withoutPeople).peopleCount)
    }

    @Test
    fun saveDropsPicturesOlderThanTheScanThatNoLongerNeedsThem() = runBlocking {
        val files = open()
        val id = files.newId()
        val pictures = files.thumbnails(id)
        repeat(6) { pictures.put(2, 2, thumbnail()) }
        files.saveSample(id, result = Fixtures.result(listOf(1, 3)))
        assertEquals(listOf("1.jpg", "3.jpg", "4.jpg", "5.jpg"), names(File(root, "$id/thumbs")))
    }

    @Test
    fun deleteRemovesEverythingAndSilencesTheOldThumbnailStore() = runBlocking {
        val files = open()
        val id = files.newId()
        val pictures = files.thumbnails(id)
        val handle = pictures.put(2, 2, thumbnail())
        files.saveSample(id, result = Fixtures.result(listOf(handle)))

        files.delete(id)
        assertFalse(File(root, id).exists())
        assertNull(files.load(id) { true })
        assertNull(pictures.file(handle))
        pictures.put(2, 2, thumbnail())
        assertFalse("a scan still running must not recreate the folder", File(root, id).exists())

        assertNull(files.delete(id))
        assertNull(files.delete("never-existed"))
    }

    @Test
    fun deleteReportsTheSourceOnlyOnceNoSavedProjectUsesIt() = runBlocking {
        val files = open()
        val first = files.newId()
        val second = files.newId()
        val other = files.newId()
        files.saveSample(first)
        files.saveSample(second)
        files.save(other, OTHER_SOURCE, "other.mp4", Fixtures.info(), AnalysisOptions.STANDARD, Fixtures.result(), EditState())

        assertNull("another project still reads this video", files.delete(first))
        assertEquals(SOURCE, files.delete(second))
        assertNull("already deleted", files.delete(second))
        assertNull("never saved", files.delete(files.newId()))
        assertEquals(OTHER_SOURCE, files.delete(other))
    }

    @Test
    fun discardUnsavedRemovesAScanOfThisProcessAtOnce() = runBlocking {
        val files = open()
        val cancelled = files.newId()
        val pictures = files.thumbnails(cancelled)
        pictures.put(2, 2, thumbnail())
        files.discardUnsaved(cancelled)
        assertFalse(File(root, cancelled).exists())
        pictures.put(2, 2, thumbnail())
        assertFalse("a late picture must not recreate the folder", File(root, cancelled).exists())

        val saved = files.newId()
        files.saveSample(saved)
        files.discardUnsaved(saved)
        assertNotNull(files.load(saved) { true })
        files.discardUnsaved("never-existed")
    }

    @Test
    fun deleteAbandonedRemovesOnlyOldUnsavedFoldersNobodyUses() = runBlocking {
        val earlier = open()
        val abandoned = earlier.newId()
        val saved = earlier.newId()
        val reopened = earlier.newId()
        earlier.thumbnails(abandoned).put(2, 2, thumbnail())
        earlier.thumbnails(reopened).put(2, 2, thumbnail())
        earlier.saveSample(saved)
        File(root, "not a project id!").mkdirs()

        // A new instance stands for the next app start: it has handed out none of these ids.
        val files = open()
        now = System.currentTimeMillis() + 30 * 60 * 1000L
        files.deleteAbandoned()
        assertTrue("younger than an hour", File(root, abandoned).exists())

        now = System.currentTimeMillis() + 2 * 60 * 60 * 1000L
        files.thumbnails(reopened)
        files.deleteAbandoned()
        assertFalse(File(root, abandoned).exists())
        assertTrue("saved projects are never abandoned", File(root, saved).exists())
        assertTrue("in use by this process", File(root, reopened).exists())
        assertTrue(File(root, "not a project id!").exists())
        assertNotNull(files.load(saved) { true })
    }

    @Test
    fun deleteAbandonedSparesAScanOfThisProcessHoweverOld() = runBlocking {
        val files = open()
        val scanning = files.newId()
        files.thumbnails(scanning).put(2, 2, thumbnail())
        now = System.currentTimeMillis() + 5 * 60 * 60 * 1000L
        files.deleteAbandoned()
        assertTrue(File(root, "$scanning/thumbs/0.jpg").isFile)
    }

    @Test
    fun deleteAbandonedJudgesAgeByTheNewestPicture() = runBlocking {
        val id = open().let { files -> files.newId().also { files.thumbnails(it).put(2, 2, thumbnail()) } }
        val folder = File(root, id)
        val longAgo = System.currentTimeMillis() - 3 * 60 * 60 * 1000L
        assertTrue(folder.setLastModified(longAgo))
        assertTrue(File(folder, "thumbs").setLastModified(longAgo))

        now = System.currentTimeMillis()
        open().deleteAbandoned()
        assertTrue("the picture inside is recent", folder.exists())

        assertTrue(File(folder, "thumbs/0.jpg").setLastModified(longAgo))
        open().deleteAbandoned()
        assertFalse(folder.exists())
    }

    @Test
    fun idsThatCouldLeaveTheProjectsFolderAreRefused() = runBlocking {
        val files = open()
        val outside = File(temp.root, "outside").apply { mkdirs() }
        for (id in listOf("../outside", "..", ".", "", "a/b", "a\\b", "x".repeat(65), "sp ace")) {
            assertFalse(id, ProjectFiles.isValidId(id))
            assertThrows(id, IllegalArgumentException::class.java) { files.thumbnails(id) }
            assertThrows(id, IllegalArgumentException::class.java) { runBlocking { files.saveSample(id) } }
            assertThrows(id, IllegalArgumentException::class.java) { runBlocking { files.saveEdit(id, EditState()) } }
            assertNull(id, files.load(id) { true })
            files.delete(id)
        }
        assertTrue(outside.isDirectory)
        assertTrue(ProjectFiles.isValidId(files.newId()))
    }

    @Test
    fun newIdsAreDistinct() {
        val files = open()
        assertEquals(200, List(200) { files.newId() }.toSet().size)
    }

    private fun <T : Any> present(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    /** Writes part of the data, then fails the way a full disk would. */
    private class FailingResultCodec : AnalysisResultCodec {
        private val real = FakeResultCodec()

        @Volatile
        var failWrites = false

        override fun write(result: AnalysisResult, out: OutputStream) {
            if (failWrites) {
                out.write(ByteArray(10))
                throw IOException("No space left on device")
            }
            real.write(result, out)
        }

        override fun read(input: InputStream): AnalysisResult = real.read(input)
    }

    private companion object {
        const val SOURCE = "content://media/external/video/media/42"
        const val OTHER_SOURCE = "content://media/external/video/media/43"
    }
}
