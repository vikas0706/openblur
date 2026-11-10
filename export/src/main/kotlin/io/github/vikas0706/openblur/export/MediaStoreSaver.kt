package io.github.vikas0706.openblur.export

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A saved video: its MediaStore Uri, the name it ended up with, and the file path on Android 9 and older. */
internal class SavedVideo(val uri: Uri, val displayName: String, val legacyFile: File?)

internal class SaveException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Thrown out of the copy loop when the export was stopped while the file was being copied. */
internal class SaveStoppedException : IOException("Stopped while saving")

/** Copies a finished temp file into Movies/OpenBlur and registers it with MediaStore. Blocking. */
internal class MediaStoreSaver(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /**
     * [marker] names the destination before the first byte is written, so a crash can be cleaned up.
     * Throws [SaveStoppedException] or [SaveException]; either way nothing is left in shared storage.
     */
    fun save(
        tempFile: File,
        baseName: String,
        dateTakenMs: Long,
        marker: File,
        isStopped: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): SavedVideo = try {
        if (Build.VERSION.SDK_INT >= 29) {
            saveScoped(tempFile, baseName, dateTakenMs, marker, isStopped, onProgress)
        } else {
            saveLegacy(tempFile, baseName, dateTakenMs, marker, isStopped, onProgress)
        }
    } finally {
        marker.delete()
    }

    fun delete(saved: SavedVideo) {
        try {
            resolver.delete(saved.uri, null, null)
        } catch (e: RuntimeException) {
            // The row may already be gone; the file below is what matters on old versions.
        }
        saved.legacyFile?.delete()
    }

    /**
     * When the source was recorded, as far as its provider knows: the capture date, else the modified
     * date, else now. Used for DATE_TAKEN so the copy sorts next to the original in galleries.
     */
    fun sourceDateTakenMs(source: Uri): Long {
        val taken = queryLong(source, MediaStore.Video.VideoColumns.DATE_TAKEN)
        if (taken > 0L) return taken
        val modified = if (source.scheme == ContentResolver.SCHEME_FILE) {
            source.path?.let { File(it).lastModified() } ?: 0L
        } else {
            queryLong(source, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        }
        if (modified > 0L) return modified
        return System.currentTimeMillis()
    }

    @RequiresApi(29)
    private fun saveScoped(
        tempFile: File,
        baseName: String,
        dateTakenMs: Long,
        marker: File,
        isStopped: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): SavedVideo {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = Environment.DIRECTORY_MOVIES + "/" + OutputNames.FOLDER
        // MediaStore only shows this app its own files here. A clash with someone else's file is
        // resolved by MediaStore itself, which is why the final name is read back after saving.
        val name = OutputNames.unique(baseName, existingNames(collection, relativePath))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, OutputNames.MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = insertPending(collection, values, dateTakenMs)
            ?: throw SaveException("MediaStore returned no entry for a new video in $relativePath")
        ExportFiles.writeMarker(marker, uri.toString())
        try {
            val descriptor = resolver.openFileDescriptor(uri, "w")
                ?: throw SaveException("MediaStore did not open $uri for writing")
            // The stream owns the descriptor and closes it once.
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out -> copy(tempFile, out, isStopped, onProgress) }
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, publish, null, null) == 0) throw SaveException("MediaStore did not publish $uri")
            return SavedVideo(uri, queryString(uri, MediaStore.MediaColumns.DISPLAY_NAME) ?: name, legacyFile = null)
        } catch (e: Exception) {
            deleteQuietly(uri)
            throw asSaveFailure(e, "Could not write $name into $relativePath")
        }
    }

    /**
     * DATE_TAKEN is a column MediaStore fills from the file on newer versions. If a version rejects the
     * value instead of ignoring it, the entry is created without it.
     */
    private fun insertPending(collection: Uri, values: ContentValues, dateTakenMs: Long): Uri? {
        val dated = ContentValues(values).apply { put(MediaStore.Video.VideoColumns.DATE_TAKEN, dateTakenMs) }
        try {
            return resolver.insert(collection, dated)
        } catch (e: RuntimeException) {
            Log.w(TAG, "MediaStore rejected an entry with DATE_TAKEN, retrying without", e)
        }
        return try {
            resolver.insert(collection, values)
        } catch (e: RuntimeException) {
            throw SaveException("MediaStore refused a new video: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun saveLegacy(
        tempFile: File,
        baseName: String,
        dateTakenMs: Long,
        marker: File,
        isStopped: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): SavedVideo {
        val granted = appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw SaveException("The storage permission (WRITE_EXTERNAL_STORAGE) is not granted, so the Movies folder cannot be written on Android ${Build.VERSION.RELEASE}")
        }
        @Suppress("DEPRECATION") // The public directory is the only way into Movies before scoped storage.
        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val folder = File(movies, OutputNames.FOLDER)
        if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) {
            throw SaveException("Could not create ${folder.absolutePath}")
        }
        val file = claimNewFile(folder, baseName)
        ExportFiles.writeMarker(marker, file.absolutePath)
        try {
            FileOutputStream(file).use { out -> copy(tempFile, out, isStopped, onProgress) }
            val uri = register(file, dateTakenMs) ?: throw SaveException("MediaStore did not accept ${file.absolutePath}")
            return SavedVideo(uri, file.name, legacyFile = file)
        } catch (e: Exception) {
            file.delete()
            throw asSaveFailure(e, "Could not write ${file.absolutePath}")
        }
    }

    /** Creates the first free name atomically, so two writers can never end up with the same file. */
    private fun claimNewFile(folder: File, baseName: String): File {
        for (attempt in 1..MAX_NAME_ATTEMPTS) {
            val file = File(folder, OutputNames.candidate(baseName, attempt))
            try {
                if (file.createNewFile()) return file
            } catch (e: IOException) {
                throw SaveException("Could not create ${file.absolutePath}: ${e.message}", e)
            }
        }
        throw SaveException("No free file name for $baseName in ${folder.absolutePath}")
    }

    private fun register(file: File, dateTakenMs: Long): Uri? {
        val nowSeconds = System.currentTimeMillis() / 1000
        val values = ContentValues().apply {
            @Suppress("DEPRECATION") // DATA is how a file is registered before API 29.
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.TITLE, file.nameWithoutExtension)
            put(MediaStore.MediaColumns.MIME_TYPE, OutputNames.MIME_TYPE)
            put(MediaStore.MediaColumns.SIZE, file.length())
            put(MediaStore.MediaColumns.DATE_ADDED, nowSeconds)
            put(MediaStore.MediaColumns.DATE_MODIFIED, nowSeconds)
            put(MediaStore.Video.VideoColumns.DATE_TAKEN, dateTakenMs)
        }
        val inserted = try {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: RuntimeException) {
            null
        }
        return inserted ?: scan(file)
    }

    /** Fallback for a MediaStore that rejects direct inserts: let the media scanner pick the file up. */
    private fun scan(file: File): Uri? {
        val done = CountDownLatch(1)
        var result: Uri? = null
        MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), arrayOf(OutputNames.MIME_TYPE)) { _, uri ->
            result = uri
            done.countDown()
        }
        done.await(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return result
    }

    private fun copy(source: File, out: FileOutputStream, isStopped: () -> Boolean, onProgress: (Float) -> Unit) {
        FileInputStream(source).use { input ->
            val from = input.channel
            val to = out.channel
            val total = from.size()
            var position = 0L
            while (position < total) {
                if (isStopped()) throw SaveStoppedException()
                val moved = from.transferTo(position, minOf(COPY_CHUNK_BYTES, total - position), to)
                if (moved <= 0L) throw IOException("The copy stalled at byte $position of $total")
                position += moved
                onProgress(position.toFloat() / total)
            }
            // Without the sync a power cut right after "saved" could leave a truncated video.
            out.fd.sync()
        }
    }

    @RequiresApi(29)
    private fun existingNames(collection: Uri, relativePath: String): List<String> {
        val names = ArrayList<String>()
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$relativePath%"),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(0)) names.add(cursor.getString(0))
                }
            }
        } catch (e: RuntimeException) {
            // An unreadable index only costs the preferred counter; MediaStore still picks a free name.
        }
        return names
    }

    private fun asSaveFailure(error: Exception, context: String): IOException = when (error) {
        is SaveStoppedException, is SaveException -> error
        else -> SaveException("$context: ${error.javaClass.simpleName}: ${error.message}", error)
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (e: RuntimeException) {
            // Pending entries the app cannot delete expire on their own after a week.
        }
    }

    private fun queryString(uri: Uri, column: String): String? = try {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun queryLong(uri: Uri, column: String): Long = try {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    } catch (e: RuntimeException) {
        // Providers that do not know the column throw instead of returning nothing.
        0L
    }

    private companion object {
        const val TAG = "OpenBlurExport"
        const val COPY_CHUNK_BYTES = 4L * 1024 * 1024
        const val MAX_NAME_ATTEMPTS = 10_000
        const val SCAN_TIMEOUT_SECONDS = 10L
    }
}
