package io.github.vikas0706.openblur.export

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.vikas0706.openblur.api.ExportState
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Temp files under cacheDir/exports. Next to each temp file a marker can name the half-written copy in
 * shared storage (a MediaStore Uri or a file path), so a crash during saving leaves nothing behind for good.
 */
internal class ExportFiles(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.cacheDir, DIRECTORY)

    /** Reserves a path for a new temp file; the file itself is created by the muxer. */
    fun newTempFile(): File = synchronized(LOCK) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create ${directory.absolutePath}")
        }
        File(directory, "export-${UUID.randomUUID()}$TEMP_SUFFIX")
    }

    fun markerFor(tempFile: File): File = File(tempFile.parentFile, tempFile.name.removeSuffix(TEMP_SUFFIX) + MARKER_SUFFIX)

    /** Removes what an export that never finished left behind. Does nothing while an export is running. */
    fun removeLeftovers() {
        synchronized(LOCK) {
            // The lock and this check together keep a new export's temp file out of the sweep.
            if (ExportSession.state.value is ExportState.Running) return
            val entries = directory.listFiles() ?: return
            for (entry in entries) {
                if (entry.name.endsWith(MARKER_SUFFIX)) removeMarked(entry)
            }
            for (entry in entries) {
                if (entry.exists() && !entry.delete()) Log.w(TAG, "Could not delete ${entry.name}")
            }
        }
    }

    private fun removeMarked(marker: File) {
        val target = try {
            marker.readText().trim()
        } catch (e: IOException) {
            return
        }
        if (target.isEmpty()) return
        try {
            if (target.startsWith("content://")) {
                appContext.contentResolver.delete(Uri.parse(target), null, null)
            } else {
                val file = File(target)
                // Only ever a file this app wrote into its own folder under Movies.
                if (file.parentFile?.name == OutputNames.FOLDER && file.name.endsWith(OutputNames.EXTENSION)) file.delete()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not remove unfinished copy $target", e)
        }
    }

    companion object {
        private const val TAG = "OpenBlurExport"
        private const val DIRECTORY = "exports"
        private const val TEMP_SUFFIX = ".mp4"
        private const val MARKER_SUFFIX = ".pending"
        private val LOCK = Any()

        fun writeMarker(marker: File, target: String) {
            try {
                marker.writeText(target)
            } catch (e: IOException) {
                // Without the marker a crash can strand the copy; the save itself is unaffected.
                Log.w(TAG, "Could not write ${marker.name}", e)
            }
        }
    }
}
