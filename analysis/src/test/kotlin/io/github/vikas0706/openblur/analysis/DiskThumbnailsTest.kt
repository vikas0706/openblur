package io.github.vikas0706.openblur.analysis

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskThumbnailsTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** Stands in for the JPEG encoder: the "image" is the size followed by the pixels. */
    private val plainEncoder = ThumbnailEncoder { width, height, rgb, out ->
        out.write(width)
        out.write(height)
        out.write(rgb, 0, width * height * 3)
    }

    private fun folder() = File(temp.root, "thumbs")

    private fun picture(seed: Int) = ByteArray(2 * 2 * 3) { (seed + it).toByte() }

    @Test
    fun namesRoundTripAndForeignNamesAreIgnored() {
        assertEquals("0.jpg", ThumbnailNames.fileName(0))
        assertEquals(41, ThumbnailNames.handleOf(ThumbnailNames.fileName(41)))
        assertEquals(Int.MAX_VALUE, ThumbnailNames.handleOf("2147483647.jpg"))
        for (name in listOf("", ".jpg", "a.jpg", "-1.jpg", "01.jpg", "1.jpeg", "1.jpg.tmp", "2147483648.jpg", "1 .jpg", "99999999999.jpg")) {
            assertNull(name, ThumbnailNames.handleOf(name))
        }
    }

    @Test
    fun nextHandleContinuesAfterTheHighestOnDisk() {
        assertEquals(0, ThumbnailNames.nextHandle(emptyList()))
        assertEquals(0, ThumbnailNames.nextHandle(listOf("notes.txt", "7.jpg.tmp")))
        assertEquals(8, ThumbnailNames.nextHandle(listOf("3.jpg", "7.jpg", "0.jpg", "junk")))
        assertEquals(Int.MAX_VALUE, ThumbnailNames.nextHandle(listOf("2147483647.jpg")))
    }

    @Test
    fun putWritesOneFilePerHandleAndFileReturnsIt() {
        val store = DiskThumbnails(folder(), plainEncoder)
        val first = store.put(2, 2, picture(1))
        val second = store.put(2, 2, picture(50))
        assertEquals(0, first)
        assertEquals(1, second)
        val file = store.file(second)
        assertNotNull(file)
        assertEquals("1.jpg", file!!.name)
        assertArrayEquals(byteArrayOf(2, 2) + picture(50), file.readBytes())
        assertEquals(listOf("0.jpg", "1.jpg"), folder().list()!!.sorted())
    }

    @Test
    fun unknownNegativeAndReleasedHandlesHaveNoFile() {
        val store = DiskThumbnails(folder(), plainEncoder)
        val handle = store.put(2, 2, picture(1))
        assertNull(store.file(-1))
        assertNull(store.file(handle + 1))
        store.release(handle)
        assertNull(store.file(handle))
        assertFalse(File(folder(), "0.jpg").exists())
        store.release(handle)
        store.release(12345)
    }

    @Test
    fun reopeningContinuesNumberingAndKeepsEarlierPictures() {
        val before = DiskThumbnails(folder(), plainEncoder)
        before.put(2, 2, picture(1))
        val kept = before.put(2, 2, picture(2))
        val released = before.put(2, 2, picture(3))
        before.release(released)

        val after = DiskThumbnails(folder(), plainEncoder)
        assertNotNull(after.file(kept))
        assertNull(after.file(released))
        // The released handle was the highest on disk, so it may be handed out again; live ones never are.
        val next = after.put(2, 2, picture(4))
        assertEquals(2, next)
        assertEquals(3, after.put(2, 2, picture(5)))
        assertArrayEquals(byteArrayOf(2, 2) + picture(2), after.file(kept)!!.readBytes())
    }

    @Test
    fun leftoverTempFilesAreRemovedOnOpenAndNeverServed() {
        folder().mkdirs()
        File(folder(), "4.jpg").writeBytes(byteArrayOf(1))
        File(folder(), "5.jpg.tmp").writeBytes(byteArrayOf(9, 9))
        val store = DiskThumbnails(folder(), plainEncoder)
        assertNotNull(store.file(4))
        assertNull(store.file(5))
        assertFalse(File(folder(), "5.jpg.tmp").exists())
        assertEquals(5, store.put(2, 2, picture(1)))
    }

    @Test
    fun aPictureIsInvisibleUntilItIsCompletelyWritten() {
        val halfWritten = CountDownLatch(1)
        val mayFinish = CountDownLatch(1)
        val slowEncoder = ThumbnailEncoder { _, _, rgb, out ->
            out.write(rgb, 0, 6)
            out.flush()
            halfWritten.countDown()
            check(mayFinish.await(10, TimeUnit.SECONDS))
            out.write(rgb, 6, 6)
        }
        val store = DiskThumbnails(folder(), slowEncoder)
        val handle = AtomicReference<Int>()
        val writer = thread { handle.set(store.put(2, 2, picture(1))) }

        assertTrue(halfWritten.await(10, TimeUnit.SECONDS))
        assertNull("no handle may resolve while its file is partial", store.file(0))
        assertFalse(File(folder(), "0.jpg").exists())

        mayFinish.countDown()
        writer.join(10_000)
        assertEquals(0, handle.get())
        assertArrayEquals(picture(1), store.file(0)!!.readBytes())
        assertEquals(listOf("0.jpg"), folder().list()!!.toList())
    }

    @Test
    fun aFailedWriteStillReturnsAHandleButNoFile() {
        var fail = true
        val flaky = ThumbnailEncoder { _, _, rgb, out ->
            out.write(rgb, 0, 3)
            if (fail) throw IOException("disk full")
        }
        val store = DiskThumbnails(folder(), flaky)
        val lost = store.put(2, 2, picture(1))
        assertEquals(0, lost)
        assertNull(store.file(lost))
        assertEquals(emptyList<String>(), folder().list().orEmpty().toList())

        fail = false
        val saved = store.put(2, 2, picture(2))
        assertEquals(1, saved)
        assertNotNull(store.file(saved))
    }

    @Test
    fun badArgumentsAreRejectedBeforeAHandleIsUsed() {
        val store = DiskThumbnails(folder(), plainEncoder)
        assertThrows(IllegalArgumentException::class.java) { store.put(0, 2, picture(1)) }
        assertThrows(IllegalArgumentException::class.java) { store.put(2, 2, ByteArray(11)) }
        assertEquals(0, store.put(2, 2, picture(1)))
    }

    @Test
    fun retiredStoreWritesNothing() {
        val store = DiskThumbnails(folder(), plainEncoder)
        val before = store.put(2, 2, picture(1))
        store.retire()
        assertNull(store.file(before))
        val after = store.put(2, 2, picture(2))
        assertTrue(after >= 0)
        assertNull(store.file(after))
        assertFalse(File(folder(), ThumbnailNames.fileName(after)).exists())
    }

    @Test
    fun removeUnreferencedKeepsReferencedAndNewerPictures() {
        val store = DiskThumbnails(folder(), plainEncoder)
        repeat(6) { store.put(2, 2, picture(it)) }
        store.removeUnreferenced(setOf(1, 3))
        assertNull(store.file(0))
        assertNotNull(store.file(1))
        assertNull(store.file(2))
        assertNotNull(store.file(3))
        // Above the newest referenced handle: these may belong to a scan that has not finished.
        assertNotNull(store.file(4))
        assertNotNull(store.file(5))
        assertEquals(listOf("1.jpg", "3.jpg", "4.jpg", "5.jpg"), folder().list()!!.sorted())

        store.removeUnreferenced(emptySet())
        assertEquals(4, folder().list()!!.size)
    }

    @Test
    fun concurrentPutsReleasesAndReadsStayConsistent() {
        val store = DiskThumbnails(folder(), plainEncoder)
        val handles = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val problems = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val writers = List(4) { writer ->
            thread {
                repeat(50) { round ->
                    val handle = store.put(2, 2, picture(writer * 50 + round))
                    if (round % 3 == 0) store.release(handle) else handles.add(handle)
                }
            }
        }
        val reader = thread {
            repeat(2_000) {
                for (handle in handles) {
                    val file = store.file(handle) ?: continue
                    if (file.length() != 14L) problems.add("handle $handle has ${file.length()} bytes")
                }
            }
        }
        writers.forEach { it.join(30_000) }
        reader.join(30_000)
        assertEquals(emptyList<String>(), problems.toList())
        assertEquals("handles must be unique", handles.size, handles.toSet().size)
        for (handle in handles) assertNotNull("handle $handle", store.file(handle))
        assertEquals(handles.size, folder().list()!!.size)
    }
}
