package io.github.vikas0706.openblur.export

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputGeometryTest {
    private fun geometry(width: Int, height: Int, rotation: Int, allowPortrait: Boolean = true) =
        OutputGeometry.forSource(video(codedWidth = width, codedHeight = height, rotationDegrees = rotation), allowPortrait)

    @Test
    fun landscapeRecordingKeepsSizeAndRotation() {
        assertEquals(OutputGeometry(1920, 1080, 0, portraitEncoding = false), geometry(1920, 1080, 0))
        assertEquals(OutputGeometry(3840, 2160, 180, portraitEncoding = false), geometry(3840, 2160, 180))
    }

    @Test
    fun phoneHeldUprightKeepsItsLandscapeCodedSizeAndSidewaysRotation() {
        assertEquals(OutputGeometry(1920, 1080, 90, portraitEncoding = false), geometry(1920, 1080, 90))
        assertEquals(OutputGeometry(1920, 1080, 270, portraitEncoding = false), geometry(1920, 1080, 270))
    }

    @Test
    fun portraitCodedPictureNeedsPortraitEncodingToKeepItsSize() {
        // Screen recordings are coded tall with no rotation flag.
        assertEquals(OutputGeometry(1080, 2400, 0, portraitEncoding = true), geometry(1080, 2400, 0))
    }

    @Test
    fun withoutPortraitEncodingATallPictureIsLaidOnItsSide() {
        assertEquals(OutputGeometry(2400, 1080, 90, portraitEncoding = false), geometry(1080, 2400, 0, allowPortrait = false))
    }

    @Test
    fun upsideDownPortraitCodedPictureKeepsItsSizeButNotItsRotation() {
        assertEquals(OutputGeometry(1080, 1920, 0, portraitEncoding = true), geometry(1080, 1920, 180))
    }

    @Test
    fun portraitCodedPictureShownSidewaysComesOutTransposed() {
        assertEquals(OutputGeometry(1920, 1080, 0, portraitEncoding = false), geometry(1080, 1920, 90))
        assertEquals(OutputGeometry(1920, 1080, 0, portraitEncoding = false), geometry(1080, 1920, 270))
    }

    @Test
    fun squarePictureIsNeverTurned() {
        assertEquals(OutputGeometry(1080, 1080, 0, portraitEncoding = false), geometry(1080, 1080, 0))
        assertEquals(OutputGeometry(1080, 1080, 180, portraitEncoding = false), geometry(1080, 1080, 180))
        // Transformer only reuses a source rotation of the same orientation class as its own choice, 0.
        assertEquals(OutputGeometry(1080, 1080, 0, portraitEncoding = false), geometry(1080, 1080, 90))
    }

    @Test
    fun rotationOutsideZeroTo359IsNormalised() {
        assertEquals(OutputGeometry(1920, 1080, 270, portraitEncoding = false), geometry(1920, 1080, -90))
        assertEquals(OutputGeometry(1920, 1080, 0, portraitEncoding = false), geometry(1920, 1080, 360))
    }

    @Test
    fun commonPhoneFootageComesOutWithTheSourceCodedSizeAndRotation() {
        val sources = listOf(
            video(codedWidth = 1920, codedHeight = 1080, rotationDegrees = 0),
            video(codedWidth = 1920, codedHeight = 1080, rotationDegrees = 90),
            video(codedWidth = 1920, codedHeight = 1080, rotationDegrees = 180),
            video(codedWidth = 1920, codedHeight = 1080, rotationDegrees = 270),
            video(codedWidth = 3840, codedHeight = 2160, rotationDegrees = 90),
            video(codedWidth = 1080, codedHeight = 2340, rotationDegrees = 0),
            video(codedWidth = 720, codedHeight = 720, rotationDegrees = 0),
        )
        for (source in sources) {
            val geometry = OutputGeometry.forSource(source)
            val label = "${source.codedWidth}x${source.codedHeight} rotation ${source.rotationDegrees}"
            assertEquals(label, source.codedWidth, geometry.encodedWidth)
            assertEquals(label, source.codedHeight, geometry.encodedHeight)
            assertEquals(label, source.rotationDegrees, geometry.rotationDegrees)
        }
    }
}
