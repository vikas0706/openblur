package io.github.vikas0706.openblur.editing

import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.FaceTrack
import io.github.vikas0706.openblur.core.MaskAppearance
import io.github.vikas0706.openblur.core.MaskShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskGeometryTest {
    private class Frame(val width: Int, val height: Int) {
        val aspect: Float get() = width.toFloat() / height
    }

    private val landscape = Frame(1920, 1080)
    private val portrait = Frame(1080, 1920)
    private val frames = listOf(landscape, portrait)

    private val cx = 0.40f
    private val cy = 0.55f
    private val w = 0.20f
    private val h = 0.25f

    private fun mask(frame: Frame, look: MaskAppearance, eyes: FloatArray? = null, boxW: Float = w, boxH: Float = h): Mask =
        timeline(
            listOf(stillTrack(1, 0L, 1_000_000L, cx, cy, boxW, boxH, eyes = eyes)),
            EditState(appearance = look),
            frame.width,
            frame.height,
        ).at(500_000L).single()

    /** Eyes [distancePx] apart around a centre, the line from the right eye to the left eye turned [degrees] clockwise. */
    private fun eyesPx(frame: Frame, centreXPx: Float, centreYPx: Float, distancePx: Float, degrees: Float): FloatArray {
        val angle = degrees * PI.toFloat() / 180f
        val dx = cos(angle) * distancePx / 2f
        val dy = sin(angle) * distancePx / 2f
        return floatArrayOf(
            (centreXPx - dx) / frame.width, (centreYPx - dy) / frame.height,
            (centreXPx + dx) / frame.width, (centreYPx + dy) / frame.height,
        )
    }

    @Test
    fun ovalPadsTheBoxAndSitsALittleHigher() {
        for (frame in frames) {
            val oval = mask(frame, MaskAppearance(shape = MaskShape.OVAL))

            assertEquals(MaskShape.OVAL, oval.shape)
            assertEquals(cx, oval.cx, TOLERANCE)
            assertEquals(cy - 0.05f * h, oval.cy, TOLERANCE)
            assertEquals(0.70f * w * frame.aspect, oval.halfW, TOLERANCE)
            assertEquals(0.80f * h, oval.halfH, TOLERANCE)
            assertEquals(0f, oval.rotation, 0f)
        }
    }

    @Test
    fun ovalHalfSizesAreInFrameHeights() {
        val wide = mask(landscape, MaskAppearance(shape = MaskShape.OVAL))
        val tall = mask(portrait, MaskAppearance(shape = MaskShape.OVAL))

        assertEquals(0.70f * 0.20f * 16f / 9f, wide.halfW, TOLERANCE)
        assertEquals(0.70f * 0.20f * 9f / 16f, tall.halfW, TOLERANCE)
        assertEquals(0.80f * 0.25f, wide.halfH, TOLERANCE)
        assertEquals(wide.halfH, tall.halfH, 0f)
    }

    @Test
    fun rectPadsTheBoxAndSitsALittleHigher() {
        for (frame in frames) {
            val rect = mask(frame, MaskAppearance(shape = MaskShape.RECT))

            assertEquals(MaskShape.RECT, rect.shape)
            assertEquals(cx, rect.cx, TOLERANCE)
            assertEquals(cy - 0.05f * h, rect.cy, TOLERANCE)
            assertEquals(0.62f * w * frame.aspect, rect.halfW, TOLERANCE)
            assertEquals(0.72f * h, rect.halfH, TOLERANCE)
            assertEquals(0f, rect.rotation, 0f)
        }
    }

    @Test
    fun scaleMultipliesHalfSizesAndLeavesTheCentreAlone() {
        for (frame in frames) {
            for (shape in listOf(MaskShape.OVAL, MaskShape.RECT)) {
                val base = mask(frame, MaskAppearance(shape = shape, scale = 1f))
                val large = mask(frame, MaskAppearance(shape = shape, scale = 1.5f))

                assertEquals(base.halfW * 1.5f, large.halfW, TOLERANCE)
                assertEquals(base.halfH * 1.5f, large.halfH, TOLERANCE)
                assertEquals(base.cx, large.cx, 0f)
                assertEquals(base.cy, large.cy, 0f)
            }
        }
    }

    @Test
    fun ovalAndRectCoverTheWholeFaceBox() {
        val corners = listOf(
            cx - w / 2 to cy - h / 2, cx + w / 2 to cy - h / 2,
            cx - w / 2 to cy + h / 2, cx + w / 2 to cy + h / 2,
        )
        for (frame in frames) {
            for (shape in listOf(MaskShape.OVAL, MaskShape.RECT)) {
                val mask = mask(frame, MaskAppearance(shape = shape))
                for ((x, y) in corners) {
                    assertTrue("$shape misses a box corner", mask.contains(x, y, frame.width, frame.height))
                }
                assertFalse(mask.contains(cx + w * 1.5f, cy, frame.width, frame.height))
                assertFalse(mask.contains(cx, cy + h * 1.5f, frame.width, frame.height))
            }
        }
    }

    @Test
    fun levelEyesGiveALevelBandBetweenThem() {
        for (frame in frames) {
            val centreX = cx * frame.width
            val centreY = (cy - 0.03f) * frame.height
            val distancePx = 0.42f * w * frame.width
            val band = mask(frame, MaskAppearance(shape = MaskShape.EYES), eyesPx(frame, centreX, centreY, distancePx, 0f))

            assertEquals(MaskShape.EYES, band.shape)
            assertEquals(cx, band.cx, TOLERANCE)
            assertEquals(cy - 0.03f, band.cy, TOLERANCE)
            assertEquals(1.35f * distancePx / frame.height, band.halfW, TOLERANCE)
            assertEquals(0.45f * distancePx / frame.height, band.halfH, TOLERANCE)
            assertEquals(0f, band.rotation, TOLERANCE)
        }
    }

    @Test
    fun tiltedEyesTurnTheBandByTheirAngleOnScreen() {
        for (frame in frames) {
            for (degrees in listOf(30f, -30f, 75f, 180f)) {
                val centreX = cx * frame.width
                val centreY = cy * frame.height
                val distancePx = 0.45f * w * frame.width
                val eyes = eyesPx(frame, centreX, centreY, distancePx, degrees)
                val band = mask(frame, MaskAppearance(shape = MaskShape.EYES), eyes)

                assertEquals(cx, band.cx, TOLERANCE)
                assertEquals(cy, band.cy, TOLERANCE)
                assertEquals(1.35f * distancePx / frame.height, band.halfW, TOLERANCE)
                assertEquals(0.45f * distancePx / frame.height, band.halfH, TOLERANCE)
                assertEquals(degrees * PI.toFloat() / 180f, band.rotation, 1e-4f)

                assertTrue(band.contains(eyes[0], eyes[1], frame.width, frame.height))
                assertTrue(band.contains(eyes[2], eyes[3], frame.width, frame.height))
            }
        }
    }

    @Test
    fun aTiltedBandIsLongAlongTheEyeLineAndShortAcrossIt() {
        for (frame in frames) {
            val distancePx = 0.45f * w * frame.width
            val eyes = eyesPx(frame, cx * frame.width, cy * frame.height, distancePx, 30f)
            val band = mask(frame, MaskAppearance(shape = MaskShape.EYES), eyes)
            val alongX = cos(PI.toFloat() / 6f)
            val alongY = sin(PI.toFloat() / 6f)

            fun inside(alongPx: Float, acrossPx: Float): Boolean = band.contains(
                cx + (alongX * alongPx - alongY * acrossPx) / frame.width,
                cy + (alongY * alongPx + alongX * acrossPx) / frame.height,
                frame.width,
                frame.height,
            )

            assertTrue(inside(1.30f * distancePx, 0f))
            assertFalse(inside(1.40f * distancePx, 0f))
            assertTrue(inside(0f, 0.40f * distancePx))
            assertFalse(inside(0f, 0.50f * distancePx))
            assertTrue(inside(-1.30f * distancePx, -0.40f * distancePx))
        }
    }

    @Test
    fun scaleMultipliesTheEyeBand() {
        for (frame in frames) {
            val eyes = eyesPx(frame, cx * frame.width, cy * frame.height, 0.42f * w * frame.width, 20f)
            val base = mask(frame, MaskAppearance(shape = MaskShape.EYES), eyes)
            val large = mask(frame, MaskAppearance(shape = MaskShape.EYES, scale = 1.6f), eyes)

            assertEquals(base.halfW * 1.6f, large.halfW, TOLERANCE)
            assertEquals(base.halfH * 1.6f, large.halfH, TOLERANCE)
            assertEquals(base.rotation, large.rotation, 0f)
            assertEquals(base.cx, large.cx, 0f)
            assertEquals(base.cy, large.cy, 0f)
        }
    }

    @Test
    fun unknownEyesFallBackToALevelBandAcrossTheEyeRegionOfTheBox() {
        for (frame in frames) {
            val band = mask(frame, MaskAppearance(shape = MaskShape.EYES, scale = 1.25f), eyes = null)

            assertEquals(MaskShape.EYES, band.shape)
            assertEquals(cx, band.cx, TOLERANCE)
            assertEquals(cy - h / 2f + 0.40f * h, band.cy, TOLERANCE)
            assertEquals(0.60f * w * frame.aspect * 1.25f, band.halfW, TOLERANCE)
            assertEquals(0.16f * h * 1.25f, band.halfH, TOLERANCE)
            assertEquals(0f, band.rotation, 0f)
        }
    }

    @Test
    fun oneUnknownEyeCoordinateIsEnoughToFallBack() {
        val eyes = floatArrayOf(0.37f, 0.52f, Float.NaN, 0.52f)
        val band = mask(landscape, MaskAppearance(shape = MaskShape.EYES), eyes)

        assertEquals(cy - 0.10f * h, band.cy, TOLERANCE)
        assertEquals(0.60f * w * landscape.aspect, band.halfW, TOLERANCE)
        assertEquals(0f, band.rotation, 0f)
    }

    @Test
    fun eyesTooCloseTogetherToSizeABandFallBackToo() {
        for (frame in frames) {
            val shortSidePx = minOf(w * frame.width, h * frame.height)
            val near = eyesPx(frame, cx * frame.width, cy * frame.height, 0.19f * shortSidePx, 10f)
            val far = eyesPx(frame, cx * frame.width, cy * frame.height, 0.21f * shortSidePx, 10f)
            val same = floatArrayOf(0.4f, 0.5f, 0.4f, 0.5f)

            for (eyes in listOf(near, same)) {
                val band = mask(frame, MaskAppearance(shape = MaskShape.EYES), eyes)
                assertEquals(0f, band.rotation, 0f)
                assertEquals(0.60f * w * frame.aspect, band.halfW, TOLERANCE)
                assertEquals(0.16f * h, band.halfH, TOLERANCE)
            }
            val turned = mask(frame, MaskAppearance(shape = MaskShape.EYES), far)
            assertEquals(10f * PI.toFloat() / 180f, turned.rotation, 1e-4f)
            assertEquals(1.35f * 0.21f * shortSidePx / frame.height, turned.halfW, TOLERANCE)
        }
    }

    @Test
    fun eyesAreInterpolatedBetweenSamples() {
        val frame = landscape
        val first = eyesPx(frame, 600f, 500f, 80f, 0f)
        val second = eyesPx(frame, 800f, 540f, 120f, 0f)
        val timeline = timeline(
            listOf(
                track(
                    1,
                    Sample(0L, 0.30f, 0.50f, 0.10f, 0.20f, first),
                    Sample(1_000_000L, 0.42f, 0.50f, 0.15f, 0.25f, second),
                ),
            ),
            EditState(appearance = MaskAppearance(shape = MaskShape.EYES)),
        )

        val band = timeline.at(500_000L).single()

        assertEquals(700f / frame.width, band.cx, TOLERANCE)
        assertEquals(520f / frame.height, band.cy, TOLERANCE)
        assertEquals(1.35f * 100f / frame.height, band.halfW, TOLERANCE)
        assertEquals(0.45f * 100f / frame.height, band.halfH, TOLERANCE)
    }

    @Test
    fun eyesKnownAtOnlyOneNeighbourCountOnThatSampleAndNotBetween() {
        val known = eyesPx(landscape, 600f, 500f, 90f, 15f)
        val timeline = timeline(
            listOf(
                track(
                    1,
                    Sample(0L, 0.30f, 0.50f, 0.10f, 0.20f, known),
                    Sample(1_000_000L, 0.30f, 0.50f, 0.10f, 0.20f, null),
                    Sample(2_000_000L, 0.30f, 0.50f, 0.10f, 0.20f, known),
                ),
            ),
            EditState(appearance = MaskAppearance(shape = MaskShape.EYES)),
        )
        val tilt = 15f * PI.toFloat() / 180f

        assertEquals(tilt, timeline.at(0L).single().rotation, 1e-4f)
        assertEquals(0f, timeline.at(400_000L).single().rotation, 0f)
        assertEquals(0f, timeline.at(1_000_000L).single().rotation, 0f)
        assertEquals(0f, timeline.at(1_600_000L).single().rotation, 0f)
        assertEquals(tilt, timeline.at(2_000_000L).single().rotation, 1e-4f)
    }

    @Test
    fun aTrackWhoseEyeArrayIsTooShortUsesTheBoxBand() {
        val base = stillTrack(1, 0L, 1_000_000L, cx, cy, w, h)
        val broken = FaceTrack(
            base.id, base.personId, base.timesUs, base.boxes, FloatArray(3), base.sampleKinds, -1, 0.9f,
        )
        val band = timeline(listOf(broken), EditState(appearance = MaskAppearance(shape = MaskShape.EYES))).at(1_000_000L).single()

        assertEquals(0.60f * w * landscape.aspect, band.halfW, TOLERANCE)
        assertEquals(0f, band.rotation, 0f)
    }

    @Test
    fun masksAreNotClampedToTheFrame() {
        val timeline = timeline(
            listOf(stillTrack(1, 0L, 1_000_000L, cx = 0.02f, cy = 0.97f, w = 0.3f, h = 0.4f)),
            EditState(appearance = MaskAppearance(shape = MaskShape.RECT, scale = 1.8f)),
        )

        val mask = timeline.at(0L).single()

        assertEquals(0.02f, mask.cx, TOLERANCE)
        assertEquals(0.97f - 0.05f * 0.4f, mask.cy, TOLERANCE)
        assertEquals(0.62f * 0.3f * landscape.aspect * 1.8f, mask.halfW, TOLERANCE)
        assertEquals(0.72f * 0.4f * 1.8f, mask.halfH, TOLERANCE)
        assertTrue("the mask reaches past the left edge", mask.cx - mask.halfW / landscape.aspect < 0f)
        assertTrue("the mask reaches past the bottom edge", mask.cy + mask.halfH > 1f)
    }

    @Test
    fun aFrameWithoutASizeIsTreatedAsSquare() {
        val mask = timeline(
            listOf(stillTrack(1, 0L, 1_000L, w = 0.2f, h = 0.2f)),
            EditState(appearance = MaskAppearance(shape = MaskShape.RECT)),
            frameWidth = 0,
            frameHeight = 0,
        ).at(0L).single()

        assertEquals(0.62f * 0.2f, mask.halfW, TOLERANCE)
    }
}
