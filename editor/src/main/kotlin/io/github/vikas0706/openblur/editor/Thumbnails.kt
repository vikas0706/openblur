package io.github.vikas0706.openblur.editor

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.vikas0706.openblur.api.ThumbnailFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Face pictures of one project, decoded off the main thread and kept while the editor is open. */
internal class ThumbnailCache(private val files: ThumbnailFiles) {
    private val images = LruCache<Int, ImageBitmap>(MAX_IMAGES)

    fun cached(handle: Int): ImageBitmap? = if (handle < 0) null else images.get(handle)

    /** Null when the handle is unknown or its file is missing or not an image; the caller shows a placeholder. */
    suspend fun load(handle: Int): ImageBitmap? {
        if (handle < 0) return null
        images.get(handle)?.let { return it }
        val decoded = withContext(Dispatchers.IO) {
            try {
                files.file(handle)?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }?.asImageBitmap()
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }
        if (decoded != null) images.put(handle, decoded)
        return decoded
    }

    private companion object {
        const val MAX_IMAGES = 256
    }
}

@Composable
internal fun rememberThumbnail(cache: ThumbnailCache, handle: Int): ImageBitmap? {
    val image by produceState(initialValue = cache.cached(handle), cache, handle) {
        value = cache.load(handle)
    }
    return image
}
