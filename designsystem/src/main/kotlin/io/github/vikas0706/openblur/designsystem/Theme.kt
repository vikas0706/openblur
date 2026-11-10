package io.github.vikas0706.openblur.designsystem

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** DESIGN.md colours as ARGB, kept free of Compose types so the contrast test runs on the JVM. */
internal object Palette {
    const val SURFACE = 0xFF0E0F11
    const val SURFACE_RAISED = 0xFF17191C
    const val SURFACE_HIGH = 0xFF22252A
    const val OUTLINE = 0xFF33373D
    const val OUTLINE_STRONG = 0xFF7D838C
    const val TEXT = 0xFFF2F3F5
    const val TEXT_MUTED = 0xFFA9AEB6
    const val AMBER = 0xFFFFB020
    const val ON_AMBER = 0xFF1A1300
    const val DANGER = 0xFFFF6B5E
}

@Immutable
class OpenBlurColors internal constructor() {
    /** Screen background. */
    val surface = Color(Palette.SURFACE)

    /** Panels, sheets, dialogs. */
    val surfaceRaised = Color(Palette.SURFACE_RAISED)

    /** Selected fills, notices, image placeholders. */
    val surfaceHigh = Color(Palette.SURFACE_HIGH)

    /** Decorative dividers only: 1.6:1 on [surface], too faint to mark a control. */
    val outline = Color(Palette.OUTLINE)

    /** Borders that identify a control or its state: at least 4:1 on all three surfaces. */
    val outlineStrong = Color(Palette.OUTLINE_STRONG)

    val text = Color(Palette.TEXT)

    /** Secondary text: at least 6.8:1 on all three surfaces. */
    val textMuted = Color(Palette.TEXT_MUTED)

    /** Text and icons of disabled controls: at least 4:1 on all three surfaces. */
    val textDisabled = Color(Palette.OUTLINE_STRONG)

    /** Reserved for blur: a blurred face, a control that changes blur, the one main action of a screen. */
    val amber = Color(Palette.AMBER)

    val onAmber = Color(Palette.ON_AMBER)

    /** Errors and destructive confirmation only; always paired with an icon or words. */
    val danger = Color(Palette.DANGER)
}

object OpenBlurShapes {
    /** Mosaic tiles, state marks, check boxes. */
    val Tile = RoundedCornerShape(4.dp)

    /** Buttons, chips, thumbnails, notices. */
    val Control = RoundedCornerShape(8.dp)

    /** Sheets, panels, dialogs. */
    val Panel = RoundedCornerShape(12.dp)
}

object OpenBlurDimens {
    /** Smallest size of anything that can be tapped. */
    val MinTouchTarget: Dp = 48.dp

    /** Space between the screen edge and content. [BottomPanel] already applies it. */
    val ScreenPadding: Dp = 16.dp

    /** Space between neighbouring controls of one group. */
    val ItemGap: Dp = 12.dp

    /** Space between groups of content. */
    val SectionGap: Dp = 24.dp
}

/**
 * Tokens Material has no slot for. Colours are constants because the app is dark only.
 * Type comes through `MaterialTheme.typography`: `titleLarge` 20 sp for screen titles, `bodyLarge` 16 for body,
 * `bodyMedium` 14 for supporting text, `bodySmall` 12 for captions and timecodes, `headlineMedium` 28.
 */
object OpenBlurTheme {
    val colors: OpenBlurColors = OpenBlurColors()

    /** True when the system "Remove animations" setting is on. Compose animations already snap to their end then. */
    val reducedMotion: Boolean
        @Composable get() {
            val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
            return scale != null && scale.scaleFactor == 0f
        }
}

/** Numbers that must not shift sideways as they change: timecodes, sizes, bitrates, percentages. */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")

/** [size] dp at the default font size, growing with the system font size: for a glyph that sits in a line of text. */
@Composable
internal fun textScaled(size: Int): Dp = with(LocalDensity.current) { size.sp.toDp() }

/**
 * The only theme: dark, with Material 3 slots filled from DESIGN.md. Material `primary` is near-white,
 * not amber, so a stock Material component never turns amber by accident.
 */
@Composable
fun OpenBlurTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MaterialColors, typography = MaterialType, shapes = MaterialShapes) {
        CompositionLocalProvider(
            LocalContentColor provides OpenBlurTheme.colors.text,
            LocalIndication provides ControlIndication,
            content = content,
        )
    }
}

private val MaterialColors = with(OpenBlurTheme.colors) {
    darkColorScheme(
        primary = text,
        onPrimary = surface,
        primaryContainer = surfaceHigh,
        onPrimaryContainer = text,
        inversePrimary = surface,
        secondary = textMuted,
        onSecondary = surface,
        secondaryContainer = surfaceHigh,
        onSecondaryContainer = text,
        tertiary = amber,
        onTertiary = onAmber,
        tertiaryContainer = amber,
        onTertiaryContainer = onAmber,
        background = surface,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceHigh,
        onSurfaceVariant = textMuted,
        // Same as surface so Material's tonal elevation tints nothing: elevation here is the three surface steps.
        surfaceTint = surface,
        inverseSurface = text,
        inverseOnSurface = surface,
        error = danger,
        onError = surface,
        errorContainer = surfaceHigh,
        onErrorContainer = danger,
        outline = outlineStrong,
        outlineVariant = outline,
        scrim = Color.Black,
        surfaceBright = surfaceHigh,
        surfaceContainer = surfaceRaised,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceHigh,
        surfaceContainerLow = surfaceRaised,
        surfaceContainerLowest = surface,
        surfaceDim = surface,
        primaryFixed = text,
        primaryFixedDim = textMuted,
        onPrimaryFixed = surface,
        onPrimaryFixedVariant = surfaceHigh,
        secondaryFixed = text,
        secondaryFixedDim = textMuted,
        onSecondaryFixed = surface,
        onSecondaryFixedVariant = surfaceHigh,
        tertiaryFixed = amber,
        tertiaryFixedDim = amber,
        onTertiaryFixed = onAmber,
        onTertiaryFixedVariant = onAmber,
    )
}

private fun type(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    // Every line is exactly lineHeight tall, first and last included, so rows keep one height in any font.
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

private val Large = type(28, 36)
private val Title = type(20, 28, FontWeight.Medium)

// Five sizes only (28, 20, 16, 14, 12 sp); Material slots above 28 sp fold down to it.
private val MaterialType = Typography(
    displayLarge = Large,
    displayMedium = Large,
    displaySmall = Large,
    headlineLarge = Large,
    headlineMedium = Large,
    headlineSmall = Title,
    titleLarge = Title,
    titleMedium = type(16, 24, FontWeight.Medium),
    titleSmall = type(14, 20, FontWeight.Medium),
    bodyLarge = type(16, 24),
    bodyMedium = type(14, 20),
    bodySmall = type(12, 16),
    labelLarge = type(14, 20, FontWeight.Medium),
    labelMedium = type(12, 16, FontWeight.Medium),
    labelSmall = type(12, 16, FontWeight.Medium),
)

private val MaterialShapes = Shapes(
    extraSmall = OpenBlurShapes.Tile,
    small = OpenBlurShapes.Control,
    medium = OpenBlurShapes.Panel,
    large = OpenBlurShapes.Panel,
    extraLarge = OpenBlurShapes.Panel,
)
