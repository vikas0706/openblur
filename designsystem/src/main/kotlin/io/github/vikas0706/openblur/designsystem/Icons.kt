package io.github.vikas0706.openblur.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Icons drawn for OpenBlur on a 24 dp grid in one style, cut like the mosaic tiles: 2 dp lines with
 * square ends and mitred corners, solid shapes for playback, and squares wherever an icon has a dot.
 * Directional icons mirror themselves in right-to-left layouts; playback icons do not.
 */
object OpenBlurIcons {
    val Back: ImageVector by lazy {
        icon("Back", autoMirror = true) {
            line {
                moveTo(20f, 12f)
                horizontalLineTo(6f)
                moveTo(11f, 6f)
                lineTo(5f, 12f)
                lineTo(11f, 18f)
            }
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            line {
                moveTo(6f, 6f)
                lineTo(18f, 18f)
                moveTo(18f, 6f)
                lineTo(6f, 18f)
            }
        }
    }

    val Play: ImageVector by lazy {
        icon("Play") {
            solid {
                moveTo(8f, 5f)
                lineTo(19f, 12f)
                lineTo(8f, 19f)
                close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        icon("Pause") {
            solid {
                rect(6f, 5f, 4f, 14f)
                rect(14f, 5f, 4f, 14f)
            }
        }
    }

    /** One frame back: the bar is the frame being stepped off. */
    val StepBack: ImageVector by lazy {
        icon("StepBack") {
            solid {
                moveTo(14.5f, 5f)
                lineTo(4.5f, 12f)
                lineTo(14.5f, 19f)
                close()
                rect(16.5f, 5f, 3f, 14f)
            }
        }
    }

    /** One frame forward. */
    val StepForward: ImageVector by lazy {
        icon("StepForward") {
            solid {
                rect(4.5f, 5f, 3f, 14f)
                moveTo(9.5f, 5f)
                lineTo(19.5f, 12f)
                lineTo(9.5f, 19f)
                close()
            }
        }
    }

    val Add: ImageVector by lazy {
        icon("Add") {
            line {
                moveTo(12f, 5f)
                verticalLineTo(19f)
                moveTo(5f, 12f)
                horizontalLineTo(19f)
            }
        }
    }

    val Delete: ImageVector by lazy {
        icon("Delete") {
            line {
                moveTo(4f, 7f)
                horizontalLineTo(20f)
                moveTo(9f, 7f)
                verticalLineTo(4f)
                horizontalLineTo(15f)
                verticalLineTo(7f)
                moveTo(6.5f, 7f)
                lineTo(7.5f, 20f)
                horizontalLineTo(16.5f)
                lineTo(17.5f, 7f)
                moveTo(10f, 11f)
                verticalLineTo(16f)
                moveTo(14f, 11f)
                verticalLineTo(16f)
            }
        }
    }

    val Check: ImageVector by lazy {
        icon("Check") {
            line {
                moveTo(5f, 12f)
                lineTo(10f, 17f)
                lineTo(19f, 7f)
            }
        }
    }

    val More: ImageVector by lazy {
        icon("More") {
            solid {
                rect(10.5f, 4f, 3f, 3f)
                rect(10.5f, 10.5f, 3f, 3f)
                rect(10.5f, 17f, 3f, 3f)
            }
        }
    }

    val Undo: ImageVector by lazy {
        icon("Undo", autoMirror = true) {
            line {
                moveTo(8f, 5f)
                lineTo(4f, 9f)
                lineTo(8f, 13f)
                moveTo(5f, 9f)
                horizontalLineTo(14.5f)
                arcToRelative(5f, 5f, 0f, false, true, 0f, 10f)
                horizontalLineTo(9f)
            }
        }
    }

    val Redo: ImageVector by lazy {
        icon("Redo", autoMirror = true) {
            line {
                moveTo(16f, 5f)
                lineTo(20f, 9f)
                lineTo(16f, 13f)
                moveTo(19f, 9f)
                horizontalLineTo(9.5f)
                arcToRelative(5f, 5f, 0f, false, false, 0f, 10f)
                horizontalLineTo(15f)
            }
        }
    }

    val Share: ImageVector by lazy {
        icon("Share") {
            line {
                moveTo(6f, 12f)
                lineTo(17f, 6f)
                moveTo(6f, 12f)
                lineTo(17f, 18f)
            }
            solid {
                rect(4f, 10f, 4f, 4f)
                rect(15f, 4f, 4f, 4f)
                rect(15f, 16f, 4f, 4f)
            }
        }
    }

    /** Open the video in another app. */
    val OpenExternal: ImageVector by lazy {
        icon("OpenExternal", autoMirror = true) {
            line {
                moveTo(11f, 5f)
                horizontalLineTo(5f)
                verticalLineTo(19f)
                horizontalLineTo(19f)
                verticalLineTo(13f)
                moveTo(14.5f, 4f)
                horizontalLineTo(20f)
                verticalLineTo(9.5f)
                moveTo(19f, 5f)
                lineTo(12f, 12f)
            }
        }
    }

    val Info: ImageVector by lazy {
        icon("Info") {
            line { roundedRect(4f, 4f, 16f, 16f, 3f) }
            solid {
                rect(11f, 7.5f, 2f, 2f)
                rect(11f, 11f, 2f, 5.5f)
            }
        }
    }

    val Warning: ImageVector by lazy {
        icon("Warning") {
            line {
                moveTo(12f, 4f)
                lineTo(21f, 20f)
                horizontalLineTo(3f)
                close()
            }
            solid {
                rect(11f, 10f, 2f, 5f)
                rect(11f, 16f, 2f, 2f)
            }
        }
    }

    /** Something failed. An octagon, so it differs from [Warning] by outline and not only by colour. */
    val Error: ImageVector by lazy {
        icon("Error") {
            line {
                moveTo(8.5f, 3.5f)
                horizontalLineTo(15.5f)
                lineTo(20.5f, 8.5f)
                verticalLineTo(15.5f)
                lineTo(15.5f, 20.5f)
                horizontalLineTo(8.5f)
                lineTo(3.5f, 15.5f)
                verticalLineTo(8.5f)
                close()
            }
            solid {
                rect(11f, 7.5f, 2f, 6f)
                rect(11f, 15f, 2f, 2f)
            }
        }
    }

    /** A face left visible. */
    val Visible: ImageVector by lazy {
        icon("Visible") {
            line {
                moveTo(2.5f, 12f)
                quadTo(12f, 1f, 21.5f, 12f)
                quadTo(12f, 23f, 2.5f, 12f)
                close()
            }
            solid { rect(10f, 10f, 4f, 4f) }
        }
    }

    /** A blurred face: mosaic tiles. */
    val Blurred: ImageVector by lazy {
        icon("Blurred") {
            solid {
                rect(3.5f, 3.5f, 5f, 5f)
                rect(15.5f, 3.5f, 5f, 5f)
                rect(9.5f, 9.5f, 5f, 5f)
                rect(3.5f, 15.5f, 5f, 5f)
                rect(15.5f, 15.5f, 5f, 5f)
            }
            solid(alpha = 0.45f) {
                rect(9.5f, 3.5f, 5f, 5f)
                rect(3.5f, 9.5f, 5f, 5f)
                rect(15.5f, 9.5f, 5f, 5f)
                rect(9.5f, 15.5f, 5f, 5f)
            }
        }
    }

    /** A person blurred in some shots and visible in others: a half-filled tile. */
    val PartlyBlurred: ImageVector by lazy {
        icon("PartlyBlurred", autoMirror = true) {
            line { rect(4f, 4f, 16f, 16f) }
            solid {
                moveTo(4f, 4f)
                lineTo(20f, 20f)
                horizontalLineTo(4f)
                close()
            }
        }
    }

    val Face: ImageVector by lazy {
        icon("Face") {
            line {
                moveTo(12f, 3f)
                curveTo(16.5f, 3f, 19f, 6f, 19f, 10.5f)
                verticalLineTo(13f)
                curveTo(19f, 17.5f, 16f, 21f, 12f, 21f)
                curveTo(8f, 21f, 5f, 17.5f, 5f, 13f)
                verticalLineTo(10.5f)
                curveTo(5f, 6f, 7.5f, 3f, 12f, 3f)
                close()
            }
            solid {
                rect(8.5f, 10f, 2f, 2f)
                rect(13.5f, 10f, 2f, 2f)
                rect(10f, 15.5f, 4f, 1.5f)
            }
        }
    }

    /** A manual blur area: a box with a handle on each corner. */
    val Area: ImageVector by lazy {
        icon("Area") {
            line {
                moveTo(7f, 5f)
                horizontalLineTo(17f)
                moveTo(19f, 7f)
                verticalLineTo(17f)
                moveTo(17f, 19f)
                horizontalLineTo(7f)
                moveTo(5f, 17f)
                verticalLineTo(7f)
            }
            solid {
                rect(3f, 3f, 4f, 4f)
                rect(17f, 3f, 4f, 4f)
                rect(3f, 17f, 4f, 4f)
                rect(17f, 17f, 4f, 4f)
            }
        }
    }

    val Keyframe: ImageVector by lazy {
        icon("Keyframe") {
            solid {
                moveTo(12f, 3f)
                lineTo(21f, 12f)
                lineTo(12f, 21f)
                lineTo(3f, 12f)
                close()
            }
        }
    }

    /** Scan the video for faces again. */
    val Rescan: ImageVector by lazy {
        icon("Rescan") {
            line {
                moveTo(18.5f, 12f)
                arcTo(6.5f, 6.5f, 0f, true, true, 12f, 5.5f)
                horizontalLineTo(15f)
                moveTo(12.5f, 2.5f)
                lineTo(15.5f, 5.5f)
                lineTo(12.5f, 8.5f)
            }
        }
    }

    val Video: ImageVector by lazy {
        icon("Video") {
            line { roundedRect(3f, 5f, 18f, 14f, 2f) }
            solid {
                moveTo(10f, 8.5f)
                lineTo(16f, 12f)
                lineTo(10f, 15.5f)
                close()
            }
        }
    }

    val VolumeOn: ImageVector by lazy {
        icon("VolumeOn", autoMirror = true) {
            solid { speaker() }
            line {
                moveTo(15f, 9f)
                arcToRelative(4.2f, 4.2f, 0f, false, true, 0f, 6f)
                moveTo(17.5f, 5.5f)
                arcToRelative(9f, 9f, 0f, false, true, 0f, 13f)
            }
        }
    }

    val VolumeOff: ImageVector by lazy {
        icon("VolumeOff", autoMirror = true) {
            solid { speaker() }
            line {
                moveTo(15.5f, 9f)
                lineTo(21.5f, 15f)
                moveTo(21.5f, 9f)
                lineTo(15.5f, 15f)
            }
        }
    }
}

private val Ink = SolidColor(Color.Black)

private fun icon(name: String, autoMirror: Boolean = false, content: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).apply(content).build()

private fun ImageVector.Builder.line(shape: PathBuilder.() -> Unit) {
    path(
        stroke = Ink,
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Butt,
        strokeLineJoin = StrokeJoin.Miter,
        pathBuilder = shape,
    )
}

private fun ImageVector.Builder.solid(alpha: Float = 1f, shape: PathBuilder.() -> Unit) {
    path(fill = Ink, fillAlpha = alpha, pathBuilder = shape)
}

private fun PathBuilder.rect(x: Float, y: Float, width: Float, height: Float) {
    moveTo(x, y)
    horizontalLineToRelative(width)
    verticalLineToRelative(height)
    horizontalLineToRelative(-width)
    close()
}

private fun PathBuilder.roundedRect(x: Float, y: Float, width: Float, height: Float, radius: Float) {
    moveTo(x + radius, y)
    horizontalLineToRelative(width - 2 * radius)
    arcToRelative(radius, radius, 0f, false, true, radius, radius)
    verticalLineToRelative(height - 2 * radius)
    arcToRelative(radius, radius, 0f, false, true, -radius, radius)
    horizontalLineToRelative(-(width - 2 * radius))
    arcToRelative(radius, radius, 0f, false, true, -radius, -radius)
    verticalLineToRelative(-(height - 2 * radius))
    arcToRelative(radius, radius, 0f, false, true, radius, -radius)
    close()
}

private fun PathBuilder.speaker() {
    moveTo(3f, 9f)
    horizontalLineTo(7f)
    lineTo(12f, 5f)
    verticalLineTo(19f)
    lineTo(7f, 15f)
    horizontalLineTo(3f)
    close()
}
