package io.github.vikas0706.openblur.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vikas0706.openblur.core.BlurChoice
import io.github.vikas0706.openblur.core.EditOps
import io.github.vikas0706.openblur.core.ManualRegion
import io.github.vikas0706.openblur.core.MaskAppearance
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.MaskStyle
import io.github.vikas0706.openblur.designsystem.BlurState
import io.github.vikas0706.openblur.designsystem.FaceThumbnail
import io.github.vikas0706.openblur.designsystem.IconAction
import io.github.vikas0706.openblur.designsystem.LabeledSlider
import io.github.vikas0706.openblur.designsystem.OpenBlurDimens
import io.github.vikas0706.openblur.designsystem.OpenBlurIcons
import io.github.vikas0706.openblur.designsystem.OpenBlurShapes
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme
import io.github.vikas0706.openblur.designsystem.SecondaryButton
import io.github.vikas0706.openblur.designsystem.SectionHeading
import io.github.vikas0706.openblur.designsystem.SegmentedChoice
import io.github.vikas0706.openblur.designsystem.TextAction
import io.github.vikas0706.openblur.designsystem.ToggleChip
import io.github.vikas0706.openblur.designsystem.formatTimeUs
import io.github.vikas0706.openblur.designsystem.tabularFigures
import kotlin.math.roundToInt

@Composable
internal fun EditorTabs(state: EditorState, onSelect: (EditorTab) -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.editor_tabs)
    SegmentedChoice(
        options = listOf(
            stringResource(R.string.editor_tab_people),
            stringResource(R.string.editor_tab_style),
            stringResource(R.string.editor_tab_areas),
        ),
        selectedIndex = state.tab.ordinal,
        onSelect = { onSelect(EditorTab.entries[it]) },
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
    )
}

/** The body of the tab that is open. The caller makes it scroll. */
@Composable
internal fun TabContent(
    vm: EditorViewModel,
    state: EditorState,
    thumbnails: ThumbnailCache,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap)) {
        when (state.tab) {
            EditorTab.PEOPLE -> PeopleTab(vm, state, thumbnails, onRescan)
            EditorTab.STYLE -> StyleTab(vm, state)
            EditorTab.AREAS -> AreasTab(vm, state)
        }
    }
}

@Composable
private fun personName(number: Int): String = stringResource(R.string.editor_person, number)

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, color = OpenBlurTheme.colors.textMuted, style = MaterialTheme.typography.bodySmall)
}

private fun BlurChoice.toBlurState(): BlurState = when (this) {
    BlurChoice.ALL -> BlurState.Blurred
    BlurChoice.NONE -> BlurState.Visible
    BlurChoice.MIXED -> BlurState.Mixed
}

@Composable
private fun PeopleTab(vm: EditorViewModel, state: EditorState, thumbnails: ThumbnailCache, onRescan: () -> Unit) {
    val people = remember(vm, state.edit) { peopleList(vm.result, state.edit) }
    val open = state.openPersonId?.let { id -> (people.main + people.brief).firstOrNull { it.person.id == id } }
    when {
        people.isEmpty -> NoFaces(onRescan = onRescan, onAddArea = vm::addArea)
        open != null -> PersonAppearances(vm, state, open, thumbnails)
        else -> PeopleOverview(vm, state, people, thumbnails)
    }
}

@Composable
private fun NoFaces(onRescan: () -> Unit, onAddArea: () -> Unit) {
    val colors = OpenBlurTheme.colors
    // Five empty mosaic tiles: the app's motif, with nothing in it.
    Canvas(modifier = Modifier.size(width = 92.dp, height = 16.dp)) {
        val tile = 16.dp.toPx()
        val gap = 3.dp.toPx()
        val line = 1.5.dp.toPx()
        for (i in 0 until 5) {
            drawRoundRect(
                color = colors.outlineStrong,
                topLeft = Offset(i * (tile + gap) + line / 2, line / 2),
                size = Size(tile - line, tile - line),
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(line),
            )
        }
    }
    Text(
        text = stringResource(R.string.editor_no_faces_title),
        modifier = Modifier.semantics { heading() },
        color = colors.text,
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(R.string.editor_no_faces_text),
        color = colors.textMuted,
        style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SecondaryButton(
            text = stringResource(R.string.editor_no_faces_rescan),
            onClick = onRescan,
            icon = OpenBlurIcons.Rescan,
        )
        SecondaryButton(
            text = stringResource(R.string.editor_no_faces_area),
            onClick = onAddArea,
            icon = OpenBlurIcons.Area,
        )
    }
}

@Composable
private fun PeopleOverview(vm: EditorViewModel, state: EditorState, people: PeopleList, thumbnails: ThumbnailCache) {
    val colors = OpenBlurTheme.colors
    val anyVisible = state.edit.visibleTrackIds.isNotEmpty()
    val anyBlurred = vm.result.tracks.any { state.edit.isBlurred(it.id) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SecondaryButton(
            text = stringResource(R.string.editor_blur_everyone),
            onClick = { vm.setEveryoneBlurred(true) },
            enabled = anyVisible,
            icon = OpenBlurIcons.Blurred,
        )
        SecondaryButton(
            text = stringResource(R.string.editor_show_everyone),
            onClick = { vm.setEveryoneBlurred(false) },
            enabled = anyBlurred,
            icon = OpenBlurIcons.Visible,
        )
    }
    Hint(stringResource(R.string.editor_people_hint))
    if (people.main.isNotEmpty()) {
        PersonCells(vm, state, people.main, thumbnails)
    }
    if (people.brief.isNotEmpty()) {
        val expanded = state.briefExpanded
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.outline, OpenBlurShapes.Control)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { vm.setBriefExpanded(!expanded) }
                    .defaultMinSize(minHeight = 56.dp)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.editor_brief_faces),
                        color = colors.text,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Hint(pluralStringResource(R.plurals.editor_brief_count, people.brief.size, people.brief.size))
                }
                Text(
                    text = stringResource(if (expanded) R.string.editor_hide else R.string.editor_show),
                    color = colors.text,
                    style = MaterialTheme.typography.labelLarge,
                    textDecoration = TextDecoration.Underline,
                )
            }
            ToggleChip(
                text = stringResource(R.string.editor_brief_blur_all),
                selected = groupChoice(people.brief) == BlurChoice.ALL,
                onSelectedChange = { blur -> vm.setPeopleBlurred(people.brief.map { it.person }, blur) },
                controlsBlur = true,
            )
            if (expanded) {
                PersonCells(vm, state, people.brief, thumbnails, Modifier.padding(top = 8.dp, bottom = 8.dp))
            }
        }
    }
}

@Composable
private fun PersonCells(
    vm: EditorViewModel,
    state: EditorState,
    rows: List<PersonRow>,
    thumbnails: ThumbnailCache,
    modifier: Modifier = Modifier,
) {
    val colors = OpenBlurTheme.colors
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in rows) {
            val id = row.person.id
            val name = personName(row.number)
            val count = row.person.trackIds.size
            val countText = pluralStringResource(R.plurals.editor_appearances, count, count)
            val openLabel = stringResource(R.string.editor_open_appearances, name) + ", " + countText
            Column(modifier = Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FaceThumbnail(
                    image = rememberThumbnail(thumbnails, row.person.thumbnailHandle),
                    blurState = row.choice.toBlurState(),
                    contentDescription = name,
                    selected = state.selection == Selection.OfPerson(id),
                    size = 80.dp,
                    onClick = { vm.togglePerson(id) },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { vm.openPerson(id) }
                        .semantics { contentDescription = openLabel }
                        .defaultMinSize(minHeight = OpenBlurDimens.MinTouchTarget)
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = name,
                        modifier = Modifier.clearAndSetSemantics { },
                        color = colors.text,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = countText,
                        modifier = Modifier.clearAndSetSemantics { },
                        color = colors.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                        textDecoration = TextDecoration.Underline,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun onScreenText(visibleUs: Long): String {
    val seconds = (visibleUs / 1_000_000L).toInt()
    return when {
        visibleUs < 1_000_000L -> stringResource(R.string.editor_on_screen_brief)
        seconds < 60 -> pluralStringResource(R.plurals.editor_on_screen_seconds, seconds, seconds)
        else -> stringResource(R.string.editor_on_screen_time, formatTimeUs(visibleUs))
    }
}

@Composable
private fun PersonAppearances(vm: EditorViewModel, state: EditorState, row: PersonRow, thumbnails: ThumbnailCache) {
    val colors = OpenBlurTheme.colors
    val name = personName(row.number)
    val appearances = remember(vm, row.person, state.edit) { appearancesOf(vm.result, row.person, state.edit) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconAction(
            icon = OpenBlurIcons.Back,
            contentDescription = stringResource(R.string.editor_close_appearances),
            onClick = vm::closePerson,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                modifier = Modifier.semantics { heading() },
                color = colors.text,
                style = MaterialTheme.typography.titleMedium,
            )
            Hint(onScreenText(row.person.visibleUs))
        }
    }
    Hint(stringResource(R.string.editor_appearances_hint))
    SecondaryButton(
        text = stringResource(R.string.editor_change_person_style),
        onClick = { vm.selectPerson(row.person.id, openStyle = true) },
    )
    for (appearance in appearances) {
        val track = appearance.track
        val startText = formatTenthsUs(track.startUs)
        val range = stringResource(R.string.editor_range, startText, formatTenthsUs(track.endUs))
        val chipLabel = stringResource(R.string.editor_blur_appearance, name, startText)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The picture repeats what the two controls beside it already say.
            Box(modifier = Modifier.clearAndSetSemantics { }) {
                FaceThumbnail(
                    image = rememberThumbnail(thumbnails, track.thumbnailHandle),
                    blurState = if (appearance.blurred) BlurState.Blurred else BlurState.Visible,
                    contentDescription = range,
                    size = 56.dp,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        onClickLabel = stringResource(R.string.editor_go_to_moment),
                        role = Role.Button,
                    ) { vm.seekToTrack(track.id) }
                    .defaultMinSize(minHeight = OpenBlurDimens.MinTouchTarget)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = range,
                    color = colors.text,
                    style = MaterialTheme.typography.bodyMedium.tabularFigures(),
                    textDecoration = TextDecoration.Underline,
                )
            }
            ToggleChip(
                text = stringResource(R.string.editor_blur_toggle),
                selected = appearance.blurred,
                onSelectedChange = { vm.setTrackBlurred(track.id, it) },
                modifier = Modifier.semantics { contentDescription = chipLabel },
                controlsBlur = true,
            )
        }
    }
}

private val SolidColors = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt())

@Composable
private fun percent(fraction: Float): String = stringResource(R.string.editor_percent, (fraction * 100f).roundToInt())

private fun hundredths(value: Float): Float = (value * 100f).roundToInt() / 100f

@Composable
private fun StyleTab(vm: EditorViewModel, state: EditorState) {
    val colors = OpenBlurTheme.colors
    val selection = state.selection
    val appearance: MaskAppearance = appearanceFor(state.edit, selection)
    val forArea = selection is Selection.OfRegion

    val targetName = when (selection) {
        Selection.None -> null
        is Selection.OfPerson -> personName(vm.personNumber(selection.personId))
        is Selection.OfRegion -> stringResource(R.string.editor_area, selection.regionId)
    }
    Column {
        Text(
            text = if (targetName == null) {
                stringResource(R.string.editor_style_for_everyone)
            } else {
                stringResource(R.string.editor_style_for_one, targetName)
            },
            modifier = Modifier.semantics { heading() },
            color = colors.text,
            style = MaterialTheme.typography.titleMedium,
        )
        when (selection) {
            Selection.None -> Hint(stringResource(R.string.editor_style_everyone_hint), Modifier.padding(top = 4.dp))
            is Selection.OfPerson -> {
                val own = selection.personId in state.edit.personAppearance
                Hint(
                    stringResource(if (own) R.string.editor_style_own else R.string.editor_style_follows),
                    Modifier.padding(top = 4.dp),
                )
            }
            is Selection.OfRegion -> Unit
        }
        if (selection != Selection.None) {
            // Shifted by the actions' own side padding so their labels line up with the text above.
            FlowRow(modifier = Modifier.offset(x = (-12).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (selection is Selection.OfPerson && selection.personId in state.edit.personAppearance) {
                    TextAction(text = stringResource(R.string.editor_style_reset_person), onClick = vm::resetPersonStyle)
                }
                TextAction(text = stringResource(R.string.editor_style_change_everyone), onClick = vm::clearSelection)
            }
        }
    }

    Column {
        SectionHeading(stringResource(R.string.editor_style_heading))
        SegmentedChoice(
            options = listOf(
                stringResource(R.string.editor_style_blur),
                stringResource(R.string.editor_style_mosaic),
                stringResource(R.string.editor_style_solid),
            ),
            selectedIndex = appearance.style.ordinal,
            onSelect = { index -> vm.updateAppearance { it.copy(style = MaskStyle.entries[index]) } },
            modifier = Modifier.fillMaxWidth(),
            controlsBlur = true,
        )
    }

    Column {
        SectionHeading(stringResource(R.string.editor_shape_heading))
        val shapes = buildList {
            add(stringResource(R.string.editor_shape_oval))
            add(stringResource(R.string.editor_shape_rect))
            if (!forArea) add(stringResource(R.string.editor_shape_eyes))
        }
        SegmentedChoice(
            options = shapes,
            selectedIndex = appearance.shape.ordinal.takeIf { it < shapes.size } ?: -1,
            onSelect = { index -> vm.updateAppearance { it.copy(shape = MaskShape.entries[index]) } },
            modifier = Modifier.fillMaxWidth(),
            controlsBlur = true,
        )
    }

    if (appearance.style == MaskStyle.SOLID) {
        Column {
            SectionHeading(stringResource(R.string.editor_color_heading))
            SegmentedChoice(
                options = listOf(
                    stringResource(R.string.editor_color_black),
                    stringResource(R.string.editor_color_white),
                    stringResource(R.string.editor_color_grey),
                ),
                selectedIndex = SolidColors.indexOf(appearance.solidColor),
                onSelect = { index -> vm.updateAppearance { it.copy(solidColor = SolidColors[index]) } },
                modifier = Modifier.fillMaxWidth(),
                controlsBlur = true,
            )
        }
    } else {
        LabeledSlider(
            label = stringResource(R.string.editor_strength),
            value = appearance.strength.coerceIn(0f, 1f),
            onValueChange = { value -> vm.updateAppearance("strength") { it.copy(strength = hundredths(value)) } },
            valueText = percent(appearance.strength.coerceIn(0f, 1f)),
            controlsBlur = true,
            onValueChangeFinished = vm::endGesture,
        )
    }

    if (!forArea) {
        val scale = appearance.scale.coerceIn(MaskAppearance.MIN_SCALE, MaskAppearance.MAX_SCALE)
        LabeledSlider(
            label = stringResource(R.string.editor_size),
            value = scale,
            onValueChange = { value -> vm.updateAppearance("size") { it.copy(scale = hundredths(value)) } },
            valueText = percent(scale),
            valueRange = MaskAppearance.MIN_SCALE..MaskAppearance.MAX_SCALE,
            controlsBlur = true,
            onValueChangeFinished = vm::endGesture,
        )
    }
}

@Composable
private fun AreasTab(vm: EditorViewModel, state: EditorState) {
    val colors = OpenBlurTheme.colors
    Text(
        text = stringResource(R.string.editor_areas_intro),
        color = colors.textMuted,
        style = MaterialTheme.typography.bodyMedium,
    )
    SecondaryButton(
        text = stringResource(R.string.editor_add_area),
        onClick = vm::addArea,
        icon = OpenBlurIcons.Add,
    )
    for (region in state.edit.regions) {
        val selected = state.selection == Selection.OfRegion(region.id)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) colors.surfaceHigh else colors.surfaceRaised, OpenBlurShapes.Control)
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.text else colors.outlineStrong, OpenBlurShapes.Control),
        ) {
            AreaHeader(vm, region, selected)
            if (selected) AreaControls(vm, state, region)
        }
    }
}

@Composable
private fun AreaHeader(vm: EditorViewModel, region: ManualRegion, selected: Boolean) {
    val colors = OpenBlurTheme.colors
    val wholeVideo = region.startUs <= 0L && region.endUs >= vm.durationUs
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.Button) {
                if (selected) vm.clearSelection() else vm.selectRegion(region.id)
            }
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = OpenBlurIcons.Area,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = colors.amber,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.editor_area, region.id),
                color = colors.text,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = if (wholeVideo) {
                    stringResource(R.string.editor_area_whole_video)
                } else {
                    stringResource(R.string.editor_range, formatTenthsUs(region.startUs), formatTenthsUs(region.endUs))
                },
                color = colors.textMuted,
                style = MaterialTheme.typography.bodySmall.tabularFigures(),
            )
        }
    }
}

@Composable
private fun AreaControls(vm: EditorViewModel, state: EditorState, region: ManualRegion) {
    val id = region.id
    val position = vm.positionUs.collectAsState()
    val shownNow by remember(region.startUs, region.endUs) {
        derivedStateOf { position.value >= region.startUs && position.value <= region.endUs }
    }
    val follows = region.keyframes.size > 1 || state.motionArmedRegionId == id
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap),
    ) {
        Hint(stringResource(if (shownNow) R.string.editor_area_drag_hint else R.string.editor_area_hidden_now))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryButton(text = stringResource(R.string.editor_area_starts_here), onClick = { vm.setAreaStartHere(id) })
            SecondaryButton(text = stringResource(R.string.editor_area_ends_here), onClick = { vm.setAreaEndHere(id) })
        }
        Column {
            ToggleChip(
                text = stringResource(R.string.editor_area_follow),
                selected = follows,
                onSelectedChange = { vm.setFollowMotion(id, it) },
                controlsBlur = true,
            )
            if (follows) Hint(stringResource(R.string.editor_area_follow_hint))
        }
        if (region.keyframes.size > 1) {
            Column {
                SectionHeading(stringResource(R.string.editor_area_positions))
                for (frame in region.keyframes) {
                    val time = formatTenthsUs(frame.timeUs)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextAction(
                            text = stringResource(R.string.editor_area_position_at, time),
                            onClick = { vm.seekToKeyframe(frame.timeUs) },
                        )
                        Spacer(Modifier.weight(1f))
                        IconAction(
                            icon = OpenBlurIcons.Delete,
                            contentDescription = stringResource(R.string.editor_area_position_remove, time),
                            onClick = { vm.removeKeyframe(id, frame.timeUs) },
                        )
                    }
                }
            }
        }
        AreaPlaceSliders(vm, region)
        SecondaryButton(
            text = stringResource(R.string.editor_area_delete),
            onClick = { vm.deleteArea(id) },
            icon = OpenBlurIcons.Delete,
            destructive = true,
        )
    }
}

/** Moves and resizes the area without a drag, for keyboards, switches and screen readers. */
@Composable
private fun AreaPlaceSliders(vm: EditorViewModel, region: ManualRegion) {
    val position by vm.positionUs.collectAsState()
    val now = EditOps.regionAt(region, position)
    val id = region.id

    fun place(gesture: String, cx: Float = now.cx, cy: Float = now.cy, w: Float = now.w, h: Float = now.h) {
        // Holding the playhead for the whole slider move keeps every step on the same position of the area.
        if (!vm.isGestureActive) vm.beginAreaGesture(id)
        vm.placeArea(id, cx, cy, w, h, gesture)
    }

    Column {
        SectionHeading(stringResource(R.string.editor_area_place_heading))
        LabeledSlider(
            label = stringResource(R.string.editor_area_x),
            value = now.cx,
            onValueChange = { place("area-x", cx = hundredths(it)) },
            valueText = percent(now.cx),
            controlsBlur = true,
            onValueChangeFinished = vm::endGesture,
        )
        LabeledSlider(
            label = stringResource(R.string.editor_area_y),
            value = now.cy,
            onValueChange = { place("area-y", cy = hundredths(it)) },
            valueText = percent(now.cy),
            controlsBlur = true,
            onValueChangeFinished = vm::endGesture,
        )
        LabeledSlider(
            label = stringResource(R.string.editor_area_width),
            value = now.w,
            onValueChange = { place("area-w", w = hundredths(it)) },
            valueText = percent(now.w),
            valueRange = EditOps.MIN_REGION_SIZE..1f,
            controlsBlur = true,
            onValueChangeFinished = vm::endGesture,
        )
        LabeledSlider(
            label = stringResource(R.string.editor_area_height),
            value = now.h,
            onValueChange = { place("area-h", h = hundredths(it)) },
            valueText = percent(now.h),
            valueRange = EditOps.MIN_REGION_SIZE..1f,
            controlsBlur = true,
            onValueChangeFinished = vm::endGesture,
        )
    }
}
