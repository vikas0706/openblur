package io.github.vikas0706.openblur.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputNamesTest {
    @Test
    fun plainNameIsKept() {
        assertEquals("Birthday 2026", OutputNames.sanitize("Birthday 2026"))
    }

    @Test
    fun charactersNoFileSystemTakesBecomeSpaces() {
        assertEquals("a b c", OutputNames.sanitize("a/b:c*?"))
        assertEquals("tab and newline", OutputNames.sanitize("tab\tand\nnewline"))
        assertEquals("quote less greater pipe", OutputNames.sanitize("quote\"less<greater>pipe|"))
        assertEquals("back slash", OutputNames.sanitize("back\\slash"))
    }

    @Test
    fun extensionGivenByTheCallerIsNotDoubled() {
        assertEquals("clip", OutputNames.sanitize("clip.mp4"))
        assertEquals("clip", OutputNames.sanitize("clip.MP4"))
        assertEquals("clip.mov", OutputNames.sanitize("clip.mov"))
    }

    @Test
    fun leadingAndTrailingDotsAndSpacesAreDropped() {
        assertEquals("hidden", OutputNames.sanitize(".hidden"))
        assertEquals("name", OutputNames.sanitize("  name. "))
    }

    @Test
    fun nothingUsableFallsBackToVideo() {
        assertEquals("video", OutputNames.sanitize(""))
        assertEquals("video", OutputNames.sanitize("..."))
        assertEquals("video", OutputNames.sanitize("///"))
        assertEquals("video", OutputNames.sanitize(".mp4"))
    }

    @Test
    fun longNameIsCutToFitTheFileNameLimitWithoutSplittingACharacter() {
        val camera = "🎥"
        val base = OutputNames.sanitize(camera.repeat(100))
        val bytes = base.toByteArray(Charsets.UTF_8)
        assertEquals(180, bytes.size)
        assertEquals(camera.repeat(45), base)

        val withCounter = OutputNames.candidate(OutputNames.sanitize("x".repeat(400)), 9999)
        assertTrue(withCounter.toByteArray(Charsets.UTF_8).size <= 255)
    }

    @Test
    fun firstCandidateHasNoCounterAndLaterOnesCountFromTwo() {
        assertEquals("clip.mp4", OutputNames.candidate("clip", 1))
        assertEquals("clip (2).mp4", OutputNames.candidate("clip", 2))
        assertEquals("clip (3).mp4", OutputNames.candidate("clip", 3))
    }

    @Test
    fun uniqueReturnsThePlainNameWhenItIsFree() {
        assertEquals("clip.mp4", OutputNames.unique("clip", emptyList()))
        assertEquals("clip.mp4", OutputNames.unique("clip", listOf("other.mp4", "clip (2).mp4")))
    }

    @Test
    fun uniqueSkipsTakenNames() {
        assertEquals("clip (2).mp4", OutputNames.unique("clip", listOf("clip.mp4")))
        assertEquals("clip (3).mp4", OutputNames.unique("clip", listOf("clip.mp4", "clip (2).mp4")))
        assertEquals("clip (2).mp4", OutputNames.unique("clip", listOf("clip.mp4", "clip (3).mp4")))
    }

    @Test
    fun uniqueComparesWithoutCase() {
        assertEquals("clip (2).mp4", OutputNames.unique("clip", listOf("CLIP.MP4")))
        assertEquals("Clip (3).mp4", OutputNames.unique("Clip", listOf("clip.mp4", "CLIP (2).mp4")))
    }
}
