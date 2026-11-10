package io.github.vikas0706.openblur.export

import androidx.media3.common.Metadata
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.XmpData
import androidx.media3.transformer.InAppMp4Muxer

/** Decides which container metadata says where a video was recorded. The inspector and the stripper share it. */
internal object LocationMetadata {
    fun isLocation(entry: Metadata.Entry): Boolean = when (entry) {
        is Mp4LocationData -> true
        // Apple writes com.apple.quicktime.location.ISO6709; other writers use their own keys with the same word.
        is MdtaMetadataEntry -> entry.key.contains("location", ignoreCase = true) || entry.key.contains("gps", ignoreCase = true)
        // An XMP packet is one blob, so a packet with GPS tags goes as a whole.
        is XmpData -> xmpMentionsGps(entry.data)
        else -> false
    }

    private fun xmpMentionsGps(data: ByteArray): Boolean {
        // Dropping the zero bytes makes a UTF-16 packet searchable with the same ASCII needle.
        val text = String(data, Charsets.ISO_8859_1).replace("\u0000", "")
        return text.contains("GPS")
    }

    fun hasLocation(metadata: Metadata?): Boolean {
        if (metadata == null) return false
        for (i in 0 until metadata.length()) {
            if (isLocation(metadata.get(i))) return true
        }
        return false
    }
}

/** Removes location entries just before the muxer writes the file's metadata. */
internal class LocationStripper : InAppMp4Muxer.MetadataProvider {
    override fun updateMetadataEntries(metadataEntries: MutableSet<Metadata.Entry>) {
        metadataEntries.removeAll { LocationMetadata.isLocation(it) }
    }
}
