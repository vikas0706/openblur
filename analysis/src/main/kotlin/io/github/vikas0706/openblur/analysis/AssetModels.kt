package io.github.vikas0706.openblur.analysis

import android.content.Context
import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Reads the ONNX models the app packages as assets. Both calls do file I/O and throw [IOException]. */
class AssetModels(context: Context) {
    // A context that has no application yet, such as an instrumentation's, still has its own assets.
    private val assets: AssetManager = (context.applicationContext ?: context).assets

    fun detectorModel(): ByteArray = read(DETECTOR_ASSET)

    fun embedderModel(): ByteArray = read(EMBEDDER_ASSET)

    internal fun read(name: String): ByteArray {
        val stream = try {
            assets.open(name, AssetManager.ACCESS_STREAMING)
        } catch (e: IOException) {
            throw IOException("Model asset $name is missing from the app", e)
        }
        stream.use { input ->
            // For an asset, available() is the whole remaining length, so one exact allocation holds the model.
            val bytes = ByteArray(input.available())
            var filled = 0
            while (filled < bytes.size) {
                val read = input.read(bytes, filled, bytes.size - filled)
                if (read < 0) break
                filled += read
            }
            if (filled < bytes.size) return bytes.copyOf(filled)
            val next = input.read()
            if (next < 0) return bytes
            val rest = ByteArrayOutputStream(bytes.size)
            rest.write(bytes)
            rest.write(next)
            input.copyTo(rest)
            return rest.toByteArray()
        }
    }

    companion object {
        const val DETECTOR_ASSET = "yunet_n_dynamic.onnx"
        const val EMBEDDER_ASSET = "face_recognition_sface_2021dec_int8.onnx"
    }
}
