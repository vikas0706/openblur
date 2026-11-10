package io.github.vikas0706.openblur.editor

import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.BlurChoice
import io.github.vikas0706.openblur.core.EditOps
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.FaceTrack
import io.github.vikas0706.openblur.core.ManualRegion
import io.github.vikas0706.openblur.core.MaskAppearance
import io.github.vikas0706.openblur.core.Person
import kotlin.math.abs

/** People on screen for less than this in total are gathered into the "Brief faces" group. */
internal const val BRIEF_FACE_US = 700_000L

internal class PersonRow(
    val person: Person,
    /** 1-based place in order of first appearance; the number in "Person 2". */
    val number: Int,
    val choice: BlurChoice,
    val hasOwnStyle: Boolean,
)

internal class PeopleList(val main: List<PersonRow>, val brief: List<PersonRow>) {
    val isEmpty: Boolean get() = main.isEmpty() && brief.isEmpty()
}

/** Everyone in the scan, longest on screen first, with the briefly seen split off. */
internal fun peopleList(result: AnalysisResult, edit: EditState): PeopleList {
    val rows = result.people.mapIndexed { index, person ->
        PersonRow(person, index + 1, EditOps.blurChoice(edit, person), person.id in edit.personAppearance)
    }.sortedWith(compareByDescending<PersonRow> { it.person.visibleUs }.thenBy { it.person.firstSeenUs }.thenBy { it.number })
    val (brief, main) = rows.partition { it.person.visibleUs < BRIEF_FACE_US }
    return PeopleList(main, brief)
}

/** The blur state of a group of people taken together. An empty group reads as blurred, the safe default. */
internal fun groupChoice(rows: List<PersonRow>): BlurChoice = when {
    rows.all { it.choice == BlurChoice.ALL } -> BlurChoice.ALL
    rows.all { it.choice == BlurChoice.NONE } -> BlurChoice.NONE
    else -> BlurChoice.MIXED
}

internal class Appearance(val track: FaceTrack, val blurred: Boolean)

/** One entry per track of [person], in time order. */
internal fun appearancesOf(result: AnalysisResult, person: Person, edit: EditState): List<Appearance> =
    person.trackIds.asList().mapNotNull { result.track(it) }
        .sortedBy { it.startUs }
        .map { Appearance(it, edit.isBlurred(it.id)) }

internal fun isOnScreen(result: AnalysisResult, person: Person, timeUs: Long): Boolean =
    person.trackIds.any { id ->
        val track = result.track(id)
        track != null && timeUs >= track.startUs && timeUs <= track.endUs
    }

/** Start of the person's earliest track, or [Person.firstSeenUs] when the scan lists none. */
internal fun firstAppearanceUs(result: AnalysisResult, person: Person): Long =
    person.trackIds.asList().mapNotNull { result.track(it)?.startUs }.minOrNull() ?: person.firstSeenUs

internal sealed interface Selection {
    data object None : Selection

    data class OfPerson(val personId: Int) : Selection

    data class OfRegion(val regionId: Int) : Selection
}

/** A stretch of the video in which the selected person or area is present. */
internal data class PresenceRange(val startUs: Long, val endUs: Long, val blurred: Boolean)

internal fun presenceRanges(result: AnalysisResult, edit: EditState, selection: Selection): List<PresenceRange> =
    when (selection) {
        Selection.None -> emptyList()
        is Selection.OfPerson -> result.person(selection.personId)?.let { person ->
            appearancesOf(result, person, edit).map { PresenceRange(it.track.startUs, it.track.endUs, it.blurred) }
        }.orEmpty()
        is Selection.OfRegion -> edit.regions.firstOrNull { it.id == selection.regionId }
            ?.let { listOf(PresenceRange(it.startUs, it.endUs, true)) }.orEmpty()
    }

/** Times of the selected area's keyframes once it follows motion; a still area has no marks to show. */
internal fun keyframeMarks(edit: EditState, selection: Selection): List<Long> {
    val region = (selection as? Selection.OfRegion)?.let { sel -> edit.regions.firstOrNull { it.id == sel.regionId } }
    return if (region == null || region.keyframes.size < 2) emptyList() else region.keyframes.map { it.timeUs }
}

internal fun regionOf(edit: EditState, regionId: Int): ManualRegion? = edit.regions.firstOrNull { it.id == regionId }

/** The keyframe that an edit at [timeUs] would change, or null when it would add a new one. */
internal fun keyframeNear(region: ManualRegion, timeUs: Long): Long? =
    region.keyframes.firstOrNull { abs(it.timeUs - timeUs) <= EditOps.KEYFRAME_SNAP_US }?.timeUs

/** The appearance the Style tab shows and edits for the current selection. */
internal fun appearanceFor(edit: EditState, selection: Selection): MaskAppearance = when (selection) {
    Selection.None -> edit.appearance
    is Selection.OfPerson -> edit.appearanceFor(selection.personId)
    is Selection.OfRegion -> regionOf(edit, selection.regionId)?.appearance ?: edit.appearance
}

/**
 * The text for the announcer's live region. Every other [serial] adds a trailing space, which is not spoken,
 * so the same event twice in a row still changes the text and is said again.
 */
internal fun liveRegionText(text: String, serial: Int): String = if (text.isEmpty() || serial % 2 == 0) text else "$text "

/** `m:ss.t`, for ranges where whole seconds would make a short appearance start and end at the same time. */
internal fun formatTenthsUs(timeUs: Long): String {
    val tenths = if (timeUs <= 0L) 0L else timeUs / 100_000L
    val seconds = tenths / 10L
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    val tail = (if (secs < 10L) "0$secs" else secs.toString()) + "." + (tenths % 10L)
    return if (hours > 0L) "$hours:${if (minutes < 10L) "0$minutes" else minutes.toString()}:$tail" else "$minutes:$tail"
}
