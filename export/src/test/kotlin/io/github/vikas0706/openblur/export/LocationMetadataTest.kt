package io.github.vikas0706.openblur.export

import androidx.media3.common.Metadata
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.container.XmpData
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationMetadataTest {
    private val gps = Mp4LocationData(12.5f, -45.25f)
    private val created = Mp4TimestampData(3_800_000_000L, 3_800_000_000L)
    private val appleLocation = MdtaMetadataEntry(
        "com.apple.quicktime.location.ISO6709",
        "+12.5000-045.2500/".toByteArray(Charsets.UTF_8),
        MdtaMetadataEntry.TYPE_INDICATOR_STRING,
    )
    private val captureFps = MdtaMetadataEntry(
        MdtaMetadataEntry.KEY_ANDROID_CAPTURE_FPS,
        ByteBuffer.allocate(4).putFloat(240f).array(),
        MdtaMetadataEntry.TYPE_INDICATOR_FLOAT32,
    )

    @Test
    fun mp4LocationBoxIsLocation() {
        assertTrue(LocationMetadata.isLocation(gps))
    }

    @Test
    fun locationKeysInMdtaAreLocationAndOtherKeysAreNot() {
        assertTrue(LocationMetadata.isLocation(appleLocation))
        assertFalse(LocationMetadata.isLocation(captureFps))
    }

    @Test
    fun creationTimeIsNotLocation() {
        assertFalse(LocationMetadata.isLocation(created))
    }

    @Test
    fun xmpIsLocationOnlyWhenItCarriesGpsTags() {
        val withGps = "<x:xmpmeta><rdf:Description exif:GPSLatitude=\"12,30N\"/></x:xmpmeta>"
        val without = "<x:xmpmeta><rdf:Description xmp:CreatorTool=\"Camera\"/></x:xmpmeta>"
        assertTrue(LocationMetadata.isLocation(XmpData(withGps.toByteArray(Charsets.UTF_8))))
        assertTrue(LocationMetadata.isLocation(XmpData(withGps.toByteArray(Charsets.UTF_16LE))))
        assertFalse(LocationMetadata.isLocation(XmpData(without.toByteArray(Charsets.UTF_8))))
    }

    @Test
    fun hasLocationLooksAtEveryEntry() {
        assertFalse(LocationMetadata.hasLocation(null))
        assertFalse(LocationMetadata.hasLocation(Metadata(created, captureFps)))
        assertTrue(LocationMetadata.hasLocation(Metadata(created, captureFps, gps)))
    }

    @Test
    fun stripperRemovesLocationAndKeepsEverythingElse() {
        val entries = linkedSetOf<Metadata.Entry>(created, gps, captureFps, appleLocation)
        LocationStripper().updateMetadataEntries(entries)
        assertEquals(linkedSetOf<Metadata.Entry>(created, captureFps), entries)
    }
}
