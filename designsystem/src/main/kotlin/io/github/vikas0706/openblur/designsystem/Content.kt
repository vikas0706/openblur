package io.github.vikas0706.openblur.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The title of a group of controls or rows. Read as a heading by screen readers. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        // More room above than below, so the heading reads as part of the group it names.
        modifier = modifier
            .padding(top = 8.dp, bottom = 4.dp)
            .semantics { heading() },
        color = OpenBlurTheme.colors.textMuted,
        style = MaterialTheme.typography.titleSmall,
    )
}

/** One line of a facts table: what it is at the start, its value at the end in tabular figures. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = OpenBlurTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(2f),
            color = colors.textMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            modifier = Modifier.weight(3f),
            color = colors.text,
            style = MaterialTheme.typography.bodyMedium.tabularFigures(),
            textAlign = TextAlign.End,
        )
    }
}

enum class NoticeTone { Neutral, Warning, Error }

/**
 * A message that stays on screen next to what it is about. Each tone has its own icon outline and
 * border, and screen readers hear "Warning" or "Error" first, so the tone never rests on colour.
 * Give [actionLabel] and [onAction] together when the user can do something about it, such as trying again.
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.Neutral,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = OpenBlurTheme.colors
    val icon = when (tone) {
        NoticeTone.Neutral -> OpenBlurIcons.Info
        NoticeTone.Warning -> OpenBlurIcons.Warning
        NoticeTone.Error -> OpenBlurIcons.Error
    }
    val iconTint = when (tone) {
        NoticeTone.Neutral -> colors.textMuted
        NoticeTone.Warning -> colors.text
        NoticeTone.Error -> colors.danger
    }
    val border = when (tone) {
        NoticeTone.Neutral -> colors.outline
        NoticeTone.Warning -> colors.outlineStrong
        NoticeTone.Error -> colors.danger
    }
    val toneName = when (tone) {
        NoticeTone.Neutral -> null
        NoticeTone.Warning -> stringResource(R.string.designsystem_notice_warning)
        NoticeTone.Error -> stringResource(R.string.designsystem_notice_error)
    }
    val hasAction = actionLabel != null && onAction != null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceHigh, OpenBlurShapes.Control)
            .border(1.dp, border, OpenBlurShapes.Control)
            .semantics(mergeDescendants = true) {
                // An error that appears while the user is elsewhere on the screen is read out once.
                if (tone == NoticeTone.Error) liveRegion = LiveRegionMode.Polite
            }
            .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = if (hasAction) 0.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = toneName,
            modifier = Modifier.size(textScaled(20)),
            tint = iconTint,
        )
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                Text(text = title, color = colors.text, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text = text,
                color = if (title != null) colors.textMuted else colors.text,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (actionLabel != null && onAction != null) {
                // Shifted by the action's own side padding so its label lines up with the message.
                TextAction(text = actionLabel, onClick = onAction, modifier = Modifier.offset(x = (-12).dp))
            }
        }
    }
}

/**
 * A time in microseconds as `m:ss` or `h:mm:ss`, in tabular figures so it does not shift while it counts.
 * Screen readers hear it in words ("1 minute 5 seconds").
 */
@Composable
fun TimeText(
    timeUs: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = OpenBlurTheme.colors.textMuted,
) {
    val spoken = spokenTime(timeUs)
    Text(
        text = formatTimeUs(timeUs),
        modifier = modifier.semantics { contentDescription = spoken },
        color = color,
        style = style.tabularFigures(),
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun spokenTime(timeUs: Long): String {
    val parts = timeParts(timeUs)
    val hours = parts.hours.toInt()
    val words = ArrayList<String>(3)
    if (hours > 0) words += pluralStringResource(R.plurals.designsystem_hours, hours, hours)
    if (parts.minutes > 0) words += pluralStringResource(R.plurals.designsystem_minutes, parts.minutes, parts.minutes)
    if (parts.seconds > 0 || words.isEmpty()) {
        words += pluralStringResource(R.plurals.designsystem_seconds, parts.seconds, parts.seconds)
    }
    return words.joinToString(" ")
}
