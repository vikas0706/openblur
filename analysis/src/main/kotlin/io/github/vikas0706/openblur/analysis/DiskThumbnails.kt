package io.github.vikas0706.openblur.analysis

import io.github.vikas0706.openblur.api.ThumbnailFiles
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Turns a tightly packed RGB picture, row 0 at the top, into an image file's bytes. */
internal fun interface ThumbnailEncoder {
    @Throws(IOException::class)
    fun encode(width: Int, height: Int, rgb: ByteArray, out: OutputStream)
}

internal object ThumbnailNames {
    const val EXTENSION = ".jpg"

    fun fileName(handle: Int): String = "$handle$EXTENSION"

    /** Null for anything this store did not name itself, including padded or oversized numbers. */
    fun handleOf(fileName: String): Int? {
        if (!fileName.endsWith(EXTENSION)) return null
        val digits = fileName.substring(0, fileName.length - EXTENSION.length)
        if (digits.isEmpty() || digits.length > 10 || digits.any { it !in '0'..'9' }) return null
        if (digits.length > 1 && digits[0] == '0') return null
        return digits.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    /** One past the highest handle among [fileNames], so a reopened folder never reuses a handle that has a file. */
    fun nextHandle(fileNames: Iterable<String>): Int {
        var highest = -1
        for (name in fileNames) {
            val handle = handleOf(name) ?: continue
            if (handle > highest) highest = handle
        }
        return if (highest == Int.MAX_VALUE) highest else highest + 1
    }
}

/**
 * One image file per handle in [folder]. The scan thread puts and releases while the UI thread asks for files,
 * so a handle is only announced after its finished file has been renamed into place.
 */
internal class DiskThumbnails(
    private val folder: File,
    private val encoder: ThumbnailEncoder,
) : ThumbnailFiles {
    private val lock = Any()
    private var live: HashSet<Int>? = null
    private var next = 0
    private var retired = false

    override fun put(width: Int, height: Int, rgb: ByteArray): Int {
        require(width > 0 && height > 0) { "Thumbnail size must be positive, got ${width}x$height" }
        require(rgb.size.toLong() >= width.toLong() * height * 3) {
            "Thumbnail needs ${width.toLong() * height * 3} bytes, got ${rgb.size}"
        }
        val handle: Int
        synchronized(lock) {
            loaded()
            handle = next
            if (next < Int.MAX_VALUE) next++
            if (retired) return handle
        }
        val target = File(folder, ThumbnailNames.fileName(handle))
        try {
            // Not synced per picture, which would stall the scan; save() syncs the ones a project keeps.
            AtomicFiles.write(target, sync = false) { out -> encoder.encode(width, height, rgb, out) }
        } catch (e: IOException) {
            // A full disk costs the picture, not the scan: the handle stays valid and has no file.
            return handle
        }
        synchronized(lock) {
            if (retired) target.delete() else loaded().add(handle)
        }
        return handle
    }

    override fun release(handle: Int) {
        val known = synchronized(lock) { loaded().remove(handle) }
        if (known) File(folder, ThumbnailNames.fileName(handle)).delete()
    }

    override fun file(handle: Int): File? {
        if (handle < 0) return null
        synchronized(lock) {
            if (handle !in loaded()) return null
        }
        return File(folder, ThumbnailNames.fileName(handle)).takeIf { it.isFile }
    }

    /** After the project is deleted: later puts still return handles but write nothing, so the folder stays gone. */
    fun retire() {
        synchronized(lock) {
            retired = true
            live = HashSet()
        }
    }

    /** Forces the pictures a saved project refers to onto disk. */
    fun sync(handles: Iterable<Int>) {
        for (handle in handles) file(handle)?.let(AtomicFiles::sync)
    }

    /**
     * Deletes pictures no longer referred to. Only handles below the newest referenced one are considered,
     * because a scan that is still running for this project allocates handles above it.
     */
    fun removeUnreferenced(referenced: Set<Int>) {
        val newest = referenced.maxOrNull() ?: return
        val stale = synchronized(lock) {
            val handles = loaded()
            val found = handles.filter { it < newest && it !in referenced }
            handles.removeAll(found.toSet())
            found
        }
        for (handle in stale) File(folder, ThumbnailNames.fileName(handle)).delete()
    }

    // Callers hold [lock].
    private fun loaded(): HashSet<Int> {
        live?.let { return it }
        val handles = HashSet<Int>()
        val names = folder.list().orEmpty()
        for (name in names) {
            val handle = ThumbnailNames.handleOf(name)
            when {
                handle != null -> handles.add(handle)
                // Left behind when the process died between writing and renaming.
                name.endsWith(AtomicFiles.TEMP_SUFFIX) -> File(folder, name).delete()
            }
        }
        next = ThumbnailNames.nextHandle(names.asIterable())
        live = handles
        return handles
    }
}
