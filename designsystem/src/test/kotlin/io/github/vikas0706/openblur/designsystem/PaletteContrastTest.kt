package io.github.vikas0706.openblur.designsystem

import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2.x contrast of every colour pair the components put next to each other. */
class PaletteContrastTest {
    private val surfaces = listOf(
        "surface" to Palette.SURFACE,
        "surfaceRaised" to Palette.SURFACE_RAISED,
        "surfaceHigh" to Palette.SURFACE_HIGH,
    )

    private fun channel(value: Long): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Long): Double =
        0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [top] drawn over [bottom] at [alpha], per channel, as the press overlay is. */
    private fun over(top: Long, alpha: Float, bottom: Long): Long {
        fun mix(shift: Int): Long {
            val t = (top shr shift) and 0xFF
            val b = (bottom shr shift) and 0xFF
            return (t * alpha + b * (1 - alpha)).roundToInt().toLong()
        }
        return (0xFFL shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun assertContrast(name: String, a: Long, b: Long, atLeast: Double) {
        val ratio = contrast(a, b)
        assertTrue("$name is %.2f:1, needs $atLeast:1".format(ratio), ratio >= atLeast)
    }

    @Test
    fun theFormulaMatchesKnownValues() {
        assertEquals(21.0, contrast(0xFFFFFFFF, 0xFF000000), 1e-9)
        assertEquals(4.48, contrast(0xFF777777, 0xFFFFFFFF), 0.005)
        assertEquals(2.82, contrast(0xFF555555, 0xFF000000), 0.005)
    }

    @Test
    fun textIsReadableOnEverySurface() {
        for ((name, surface) in surfaces) {
            assertContrast("text on $name", Palette.TEXT, surface, 4.5)
            assertContrast("textMuted on $name", Palette.TEXT_MUTED, surface, 4.5)
            assertContrast("danger text on $name", Palette.DANGER, surface, 4.5)
        }
    }

    @Test
    fun textIsReadableOnFilledControls() {
        assertContrast("onAmber on amber", Palette.ON_AMBER, Palette.AMBER, 4.5)
        assertContrast("surface on text (Material onPrimary on primary)", Palette.SURFACE, Palette.TEXT, 4.5)
        assertContrast("surface on danger (Material onError on error)", Palette.SURFACE, Palette.DANGER, 4.5)
    }

    @Test
    fun controlBoundariesAndStateMarksStandOutOnEverySurface() {
        for ((name, surface) in surfaces) {
            assertContrast("outlineStrong border on $name", Palette.OUTLINE_STRONG, surface, 3.0)
            assertContrast("amber border, tile or thumb on $name", Palette.AMBER, surface, 3.0)
            assertContrast("selected ring, check or focus dash on $name", Palette.TEXT, surface, 3.0)
            assertContrast("danger border or icon on $name", Palette.DANGER, surface, 3.0)
        }
    }

    @Test
    fun glyphsStandOutOnTheirTiles() {
        assertContrast("check on a near-white tile", Palette.SURFACE, Palette.TEXT, 3.0)
        assertContrast("check or state glyph on an amber tile", Palette.ON_AMBER, Palette.AMBER, 3.0)
        assertContrast("check on a disabled tile", Palette.SURFACE, Palette.OUTLINE_STRONG, 3.0)
        assertContrast("eye glyph on the visible mark", Palette.TEXT, Palette.SURFACE, 3.0)
    }

    @Test
    fun theSwitchThumbStandsOutOnItsTrack() {
        assertContrast("off thumb on off track", Palette.OUTLINE_STRONG, Palette.SURFACE_HIGH, 3.0)
        assertContrast("on thumb on near-white track", Palette.SURFACE, Palette.TEXT, 3.0)
        assertContrast("on thumb on amber track", Palette.ON_AMBER, Palette.AMBER, 3.0)
        assertContrast("disabled on thumb on disabled track", Palette.SURFACE, Palette.OUTLINE_STRONG, 3.0)
    }

    @Test
    fun theFocusRingShowsOnDarkAndOnAmber() {
        for ((name, surface) in surfaces) {
            assertContrast("focus dashes on $name", Palette.TEXT, surface, 3.0)
        }
        assertContrast("focus ring base line on amber", Palette.SURFACE, Palette.AMBER, 3.0)
        assertContrast("focus dashes on their base line", Palette.TEXT, Palette.SURFACE, 3.0)
    }

    @Test
    fun disabledContentStaysLegible() {
        // WCAG exempts disabled controls; 3:1 is this kit's own floor so they can still be read.
        for ((name, surface) in surfaces) {
            assertContrast("textDisabled on $name", Palette.OUTLINE_STRONG, surface, 3.0)
        }
    }

    @Test
    fun pressedOverlayKeepsTextAndBordersAboveTheirMinimums() {
        for ((name, surface) in surfaces) {
            val pressed = over(Palette.TEXT, PRESSED_ALPHA, surface)
            fun shown(color: Long) = over(Palette.TEXT, PRESSED_ALPHA, color)
            assertContrast("text on pressed $name", shown(Palette.TEXT), pressed, 4.5)
            assertContrast("textMuted on pressed $name", shown(Palette.TEXT_MUTED), pressed, 4.5)
            assertContrast("danger text on pressed $name", shown(Palette.DANGER), pressed, 4.5)
            assertContrast("outlineStrong border on pressed $name", shown(Palette.OUTLINE_STRONG), pressed, 3.0)
            assertContrast("amber border on pressed $name", shown(Palette.AMBER), pressed, 3.0)
        }
        val pressedAmber = over(Palette.ON_AMBER, AMBER_PRESSED_ALPHA, Palette.AMBER)
        assertContrast("onAmber on pressed amber", Palette.ON_AMBER, pressedAmber, 4.5)
        assertContrast("pressed amber against the screen", pressedAmber, Palette.SURFACE, 3.0)
    }

    @Test
    fun theDecorativeOutlineIsNotUsableAsAControlBoundary() {
        // Documents why components never use `outline` to mark an enabled control.
        for ((_, surface) in surfaces) {
            assertTrue(contrast(Palette.OUTLINE, surface) < 3.0)
        }
    }
}
