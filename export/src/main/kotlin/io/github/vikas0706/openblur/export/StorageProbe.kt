package io.github.vikas0706.openblur.export

import android.content.Context
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException

/** Measures free space where the temp file is written and where the finished video is saved. */
internal class StorageProbe(context: Context) {
    private val appContext = context.applicationContext

    /** Null when an export of [estimateBytes] fits. */
    fun shortfall(estimateBytes: Long): SpaceShortfall? {
        val cache = appContext.cacheDir
        // The app's own folder on shared storage sits on the same volume as the Movies folder.
        val destination = appContext.getExternalFilesDir(null)
        val sameVolume = destination != null && onSameVolume(cache, destination)
        val destinationFree = destination?.usableSpace ?: Long.MAX_VALUE
        val first = SpaceEstimate.shortfall(estimateBytes, cache.usableSpace, destinationFree, sameVolume)
        if (first == null || !first.onCacheVolume) return first
        // The system can make room by clearing other apps' caches, which usableSpace does not count.
        if (!allocate(cache, first.neededBytes)) return first
        return SpaceEstimate.shortfall(estimateBytes, cache.usableSpace, destination?.usableSpace ?: Long.MAX_VALUE, sameVolume)
    }

    private fun onSameVolume(a: File, b: File): Boolean {
        val storage = appContext.getSystemService(StorageManager::class.java) ?: return true
        return try {
            storage.getUuidForPath(a) == storage.getUuidForPath(b)
        } catch (e: IOException) {
            // Unknown layout: assume one volume, which asks for more room and never for less.
            true
        }
    }

    private fun allocate(directory: File, bytes: Long): Boolean {
        val storage = appContext.getSystemService(StorageManager::class.java) ?: return false
        return try {
            val volume = storage.getUuidForPath(directory)
            if (storage.getAllocatableBytes(volume) < bytes) return false
            storage.allocateBytes(volume, bytes)
            true
        } catch (e: IOException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }
}
