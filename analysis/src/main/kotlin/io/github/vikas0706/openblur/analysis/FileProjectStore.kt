package io.github.vikas0706.openblur.analysis

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import io.github.vikas0706.openblur.api.Project
import io.github.vikas0706.openblur.api.ProjectStore
import io.github.vikas0706.openblur.api.ProjectSummary
import io.github.vikas0706.openblur.api.ThumbnailFiles
import io.github.vikas0706.openblur.core.AnalysisResultCodec
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.EditStateCodec
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps each project in filesDir/projects/<id>/. [save] and [saveEdit] throw [IOException] when the data
 * cannot be written, for example on a full disk; the previous files stay intact in that case.
 */
class FileProjectStore internal constructor(
    context: Context,
    resultCodec: AnalysisResultCodec,
    editCodec: EditStateCodec,
    nowMs: () -> Long,
) : ProjectStore {

    constructor(context: Context, resultCodec: AnalysisResultCodec, editCodec: EditStateCodec) :
        this(context, resultCodec, editCodec, System::currentTimeMillis)

    private val appContext: Context = context.applicationContext

    private val files = ProjectFiles(
        root = File(appContext.filesDir, PROJECTS_FOLDER),
        resultCodec = resultCodec,
        editCodec = editCodec,
        encoder = JpegThumbnailEncoder(),
        io = Dispatchers.IO,
        nowMs = nowMs,
    )

    override fun newProjectId(): String = files.newId()

    override fun thumbnails(projectId: String): ThumbnailFiles = files.thumbnails(projectId)

    override suspend fun save(project: Project) {
        files.save(
            id = project.id,
            sourceUri = project.sourceUri.toString(),
            displayName = project.displayName,
            info = project.info,
            options = project.options,
            result = project.result,
            edit = project.edit,
        )
    }

    override suspend fun saveEdit(projectId: String, edit: EditState) = files.saveEdit(projectId, edit)

    override suspend fun load(projectId: String): Project? {
        val stored = files.load(projectId) { canOpen(it.toUri()) } ?: return null
        return Project(
            id = projectId,
            sourceUri = stored.meta.sourceUri.toUri(),
            displayName = stored.meta.displayName,
            info = stored.meta.info,
            options = stored.meta.options,
            result = stored.result,
            edit = stored.edit,
        )
    }

    override suspend fun list(): List<ProjectSummary> = files.list()

    override suspend fun delete(projectId: String) {
        val orphaned = files.delete(projectId) ?: return
        releaseReadGrant(orphaned.toUri())
    }

    override suspend fun deleteAbandoned() = files.deleteAbandoned()

    override suspend fun discardUnsaved(projectId: String) = files.discardUnsaved(projectId)

    // Persisted grants are capped per app, so one no saved project needs is handed back.
    private suspend fun releaseReadGrant(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        withContext(Dispatchers.IO) {
            try {
                appContext.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // No persisted grant was held, as with providers that do not offer one.
            }
        }
    }

    // A picker grant can lapse and a file can be deleted; either way the project cannot be previewed or exported.
    private fun canOpen(uri: Uri): Boolean = try {
        when (uri.scheme) {
            null, ContentResolver.SCHEME_FILE -> uri.path?.let { File(it).canRead() } ?: false
            else -> appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val PROJECTS_FOLDER = "projects"
    }
}

/** Reuses one bitmap per thumbnail size; the lock covers two scans putting pictures at once. */
internal class JpegThumbnailEncoder : ThumbnailEncoder {
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)

    @Synchronized
    override fun encode(width: Int, height: Int, rgb: ByteArray, out: OutputStream) {
        val count = width * height
        if (pixels.size < count) pixels = IntArray(count)
        val argb = pixels
        var source = 0
        for (i in 0 until count) {
            argb[i] = (0xFF shl 24) or
                ((rgb[source].toInt() and 0xFF) shl 16) or
                ((rgb[source + 1].toInt() and 0xFF) shl 8) or
                (rgb[source + 2].toInt() and 0xFF)
            source += 3
        }
        val target = bitmap?.takeIf { it.width == width && it.height == height }
            ?: createBitmap(width, height).also {
                bitmap?.recycle()
                bitmap = it
            }
        target.setPixels(argb, 0, width, 0, 0, width, height)
        if (!target.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
            throw IOException("JPEG encoder rejected a ${width}x$height thumbnail")
        }
    }

    private companion object {
        const val JPEG_QUALITY = 90
    }
}
