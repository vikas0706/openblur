package io.github.vikas0706.openblur.analysis

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object AtomicFiles {
    const val TEMP_SUFFIX = ".tmp"

    /**
     * Readers see the old content or the complete new content, never a mix: the data goes to a sibling temp
     * file, is forced to disk, then renamed over [target].
     */
    @Throws(IOException::class)
    fun write(target: File, sync: Boolean = true, block: (OutputStream) -> Unit) {
        val parent = target.parentFile ?: throw IOException("No parent folder for $target")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Cannot create folder $parent")
        }
        val temp = File(parent, target.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { file ->
                val buffered = BufferedOutputStream(file, 64 * 1024)
                block(buffered)
                buffered.flush()
                if (sync) file.fd.sync()
            }
            replace(temp, target)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    @Throws(IOException::class)
    fun replace(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Forces a file that was written without syncing onto disk. Opened read-only so a missing file is not created. */
    fun sync(file: File) {
        try {
            RandomAccessFile(file, "r").use { it.fd.sync() }
        } catch (e: IOException) {
            // Best effort: the file stays readable either way, only its durability is at stake.
        }
    }

    /** Returns true when nothing is left of [folder]. */
    fun deleteTree(folder: File): Boolean {
        folder.listFiles()?.forEach { child ->
            if (child.isDirectory) deleteTree(child) else child.delete()
        }
        folder.delete()
        return !folder.exists()
    }
}
