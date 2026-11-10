package io.github.vikas0706.openblur.analysis

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun writeCreatesMissingFoldersAndLeavesNoTempFile() {
        val target = File(temp.root, "a/b/data.bin")
        AtomicFiles.write(target) { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals(listOf<Byte>(1, 2, 3), target.readBytes().toList())
        assertEquals(listOf("data.bin"), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun writeReplacesAnExistingFile() {
        val target = File(temp.root, "data.bin")
        AtomicFiles.write(target) { it.write(ByteArray(100) { 7 }) }
        AtomicFiles.write(target) { it.write(byteArrayOf(9)) }
        assertEquals(listOf<Byte>(9), target.readBytes().toList())
    }

    @Test
    fun aFailedWriteKeepsTheOldContent() {
        val target = File(temp.root, "data.bin")
        AtomicFiles.write(target) { it.write(byteArrayOf(4, 5)) }
        assertThrows(IOException::class.java) {
            AtomicFiles.write(target) {
                it.write(ByteArray(200_000))
                throw IOException("disk full")
            }
        }
        assertEquals(listOf<Byte>(4, 5), target.readBytes().toList())
        assertEquals(listOf("data.bin"), temp.root.list()!!.toList())
    }

    @Test
    fun syncOfAMissingFileDoesNotCreateIt() {
        val missing = File(temp.root, "missing.jpg")
        AtomicFiles.sync(missing)
        assertFalse(missing.exists())
    }

    @Test
    fun deleteTreeRemovesNestedContent() {
        val folder = File(temp.root, "project")
        File(folder, "thumbs").mkdirs()
        File(folder, "thumbs/0.jpg").writeBytes(byteArrayOf(1))
        File(folder, "meta.bin").writeBytes(byteArrayOf(2))
        assertTrue(AtomicFiles.deleteTree(folder))
        assertFalse(folder.exists())
        assertTrue(AtomicFiles.deleteTree(File(temp.root, "never-existed")))
    }
}
