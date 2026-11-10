package io.github.vikas0706.openblur.export

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreSaverDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val saver = MediaStoreSaver(context)
    private val files = ExportFiles(context)
    private val saved = ArrayList<SavedVideo>()
    private val base = "openblur-saver-test-${System.currentTimeMillis()}"

    @Before
    fun requireScopedStorage() {
        // Android 9 and older need WRITE_EXTERNAL_STORAGE granted by hand before this can run.
        assumeTrue(Build.VERSION.SDK_INT >= 29)
    }

    @After
    fun tearDown() {
        saved.forEach { saver.delete(it) }
        files.removeLeftovers()
    }

    @Test
    fun savesIntoMoviesOpenBlurAndCountsUpInsteadOfOverwriting() {
        val firstBytes = Random(1).nextBytes(300_000)
        val secondBytes = Random(2).nextBytes(200_000)

        val first = save(firstBytes)
        val second = save(secondBytes)

        assertEquals("$base.mp4", first.displayName)
        assertEquals("$base (2).mp4", second.displayName)
        assertArrayEquals(firstBytes, read(first.uri))
        assertArrayEquals(secondBytes, read(second.uri))
        assertEquals("Movies/OpenBlur/", column(first.uri, MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("0", column(first.uri, MediaStore.MediaColumns.IS_PENDING))
    }

    @Test
    fun stoppingDuringTheCopyLeavesNoEntryBehind() {
        val temp = files.newTempFile().apply { writeBytes(Random(3).nextBytes(100_000)) }
        val marker = files.markerFor(temp)
        try {
            assertThrows(SaveStoppedException::class.java) {
                saved.add(saver.save(temp, base, System.currentTimeMillis(), marker, isStopped = { true }, onProgress = {}))
            }
        } finally {
            temp.delete()
        }
        assertFalse(marker.exists())
        assertEquals(emptyList<String>(), namesStartingWith(base))
    }

    private fun save(bytes: ByteArray): SavedVideo {
        val temp = files.newTempFile().apply { writeBytes(bytes) }
        try {
            val video = saver.save(temp, base, System.currentTimeMillis(), files.markerFor(temp), isStopped = { false }, onProgress = {})
            saved.add(video)
            return video
        } finally {
            temp.delete()
        }
    }

    private fun read(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun column(uri: Uri, name: String): String? =
        context.contentResolver.query(uri, arrayOf(name), null, null, null)!!.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun namesStartingWith(prefix: String): List<String> {
        val names = ArrayList<String>()
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("$prefix%"),
            null,
        )!!.use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(0))
        }
        return names
    }
}
