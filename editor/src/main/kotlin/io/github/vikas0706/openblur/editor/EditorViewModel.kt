package io.github.vikas0706.openblur.editor

import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.BlurChoice
import io.github.vikas0706.openblur.core.EditOps
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.MaskAppearance
import io.github.vikas0706.openblur.core.Person
import io.github.vikas0706.openblur.core.RegionKeyframe
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class EditorTab { PEOPLE, STYLE, AREAS }

/** Something that just happened, for the screen to say aloud to screen reader users. */
internal sealed interface EditorEvent {
    data class PersonBlur(val personNumber: Int, val choice: BlurChoice) : EditorEvent

    data class AppearanceBlur(val blurred: Boolean) : EditorEvent

    data class EveryoneBlur(val blurred: Boolean) : EditorEvent

    /** Every face of the "Brief faces" group at once. */
    data class GroupBlur(val blurred: Boolean) : EditorEvent

    data class PersonSelected(val personNumber: Int) : EditorEvent

    data class AreaSelected(val regionId: Int) : EditorEvent

    data class AreaAdded(val regionId: Int) : EditorEvent

    data class AreaDeleted(val regionId: Int) : EditorEvent

    data object SelectionCleared : EditorEvent

    data object Undone : EditorEvent

    data object Redone : EditorEvent
}

/** [serial] changes with every event so the same event twice in a row is still announced twice. */
internal data class Announcement(val serial: Int, val event: EditorEvent)

/** True when the People tab is open on one person's appearance list. */
internal fun showsAppearances(state: EditorState): Boolean = state.tab == EditorTab.PEOPLE && state.openPersonId != null

/** True when system back has something to close before the screen itself. */
internal fun backClosesSomething(state: EditorState): Boolean = showsAppearances(state) || state.selection != Selection.None

internal data class EditorState(
    val edit: EditState,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val selection: Selection = Selection.None,
    val tab: EditorTab = EditorTab.PEOPLE,
    /** The person whose appearances are listed, or null when the People tab shows everyone. */
    val openPersonId: Int? = null,
    val briefExpanded: Boolean = false,
    /** An area with a single position that the user asked to follow motion: its next move adds a position. */
    val motionArmedRegionId: Int? = null,
    val muted: Boolean = false,
    val announcement: Announcement? = null,
)

/** What the view model needs from the player. Null while no player is attached. */
internal interface Playback {
    fun seekTo(positionUs: Long)

    fun play()

    fun pause()
}

/**
 * All editing decisions for one scanned video: the current [EditState], its undo history, what is selected
 * and where the playhead is. Free of Android types; call it from one thread.
 */
internal class EditorViewModel(
    val result: AnalysisResult,
    initialEdit: EditState,
    frameRate: Float,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
) {
    val durationUs: Long = result.durationUs.coerceAtLeast(0L)

    /** One frame of the source; 30 fps is assumed when the source does not say. */
    val frameDurationUs: Long =
        if (frameRate.isFinite() && frameRate >= 1f) (1_000_000.0 / frameRate).roundToLong() else 33_333L

    var playback: Playback? = null

    private val _state = MutableStateFlow(EditorState(edit = initialEdit))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _positionUs = MutableStateFlow(0L)
    val positionUs: StateFlow<Long> = _positionUs.asStateFlow()

    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()
    private var mergeKey: Any? = null
    private var gestureTimeUs: Long? = null
    private var announcements = 0

    private var playing = false
    private var scrubbing = false
    private var resumeAfterScrub = false
    private var frameTimesKnown = false
    private var lastFrameUs = -1L

    private val personByTrack: Map<Int, Person> = HashMap<Int, Person>().also { map ->
        for (person in result.people) for (id in person.trackIds) map[id] = person
    }
    private val numberByPerson: Map<Int, Int> = result.people.withIndex().associate { (i, p) -> p.id to i + 1 }

    private val current: EditorState get() = _state.value
    private val edit: EditState get() = _state.value.edit

    fun personNumber(personId: Int): Int = numberByPerson[personId] ?: 0

    /** True while a drag or slider move is being merged into one undo step. */
    val isGestureActive: Boolean get() = mergeKey != null

    /**
     * Makes [next] the current edit. Edits that share a non-null [key] with the one before them replace it
     * instead of adding an undo step, so a whole drag undoes at once; [endGesture] closes such a run.
     */
    private fun commit(next: EditState, key: Any? = null, event: EditorEvent? = null) {
        if (next == edit) {
            if (event != null) announce(event)
            return
        }
        if (key == null || key != mergeKey) {
            undoStack.addLast(edit)
            while (undoStack.size > historyLimit) undoStack.removeFirst()
        }
        redoStack.clear()
        mergeKey = key
        if (key == null) gestureTimeUs = null
        publish(next, event)
    }

    private fun publish(next: EditState, event: EditorEvent?, fromHistory: Boolean = false) {
        val state = current
        val selection = state.selection.takeIf { isValid(it, next) } ?: Selection.None
        // Undoing or redoing the area's positions also undoes the Follow motion switch that went with them.
        val armed = state.motionArmedRegionId?.takeIf { id ->
            selection == Selection.OfRegion(id) &&
                (!fromHistory || regionOf(state.edit, id)?.keyframes == regionOf(next, id)?.keyframes)
        }
        _state.value = state.copy(
            edit = next,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            selection = selection,
            motionArmedRegionId = armed,
            announcement = if (event != null) Announcement(++announcements, event) else state.announcement,
        )
    }

    private fun isValid(selection: Selection, edit: EditState): Boolean = when (selection) {
        Selection.None -> true
        is Selection.OfPerson -> result.person(selection.personId) != null
        is Selection.OfRegion -> regionOf(edit, selection.regionId) != null
    }

    private fun announce(event: EditorEvent) {
        _state.value = current.copy(announcement = Announcement(++announcements, event))
    }

    /** Ends the run of merged edits started by a drag or a slider, so the next edit is its own undo step. */
    fun endGesture() {
        mergeKey = null
        gestureTimeUs = null
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        endGesture()
        redoStack.addLast(edit)
        publish(undoStack.removeLast(), EditorEvent.Undone, fromHistory = true)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        endGesture()
        undoStack.addLast(edit)
        publish(redoStack.removeLast(), EditorEvent.Redone, fromHistory = true)
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
        // A held drag time is only valid while the picture stands still.
        if (isPlaying) endGesture() else snapToShownFrame()
    }

    /** The player's own position, reported while it plays. */
    fun onPlayerPosition(positionUs: Long) {
        if (!scrubbing) _positionUs.value = positionUs.coerceIn(0L, durationUs)
    }

    /**
     * The source time of the frame now on screen. While paused the playhead moves onto it, so outlines,
     * "starts here" and frame steps use real frame times and not a guess from the frame rate.
     */
    fun onFrameShown(frameTimeUs: Long) {
        if (frameTimeUs < 0L || frameTimeUs > durationUs + frameDurationUs) return
        frameTimesKnown = true
        lastFrameUs = frameTimeUs
        snapToShownFrame()
    }

    private fun snapToShownFrame() {
        if (playing || scrubbing || gestureTimeUs != null || lastFrameUs < 0L) return
        // A frame further away than this belongs to an earlier seek, not to where the playhead is now.
        if (abs(lastFrameUs - _positionUs.value) <= frameDurationUs * 3 / 2) {
            _positionUs.value = lastFrameUs.coerceAtMost(durationUs)
        }
    }

    fun seekTo(positionUs: Long) {
        endGesture()
        val target = positionUs.coerceIn(0L, durationUs)
        _positionUs.value = target
        playback?.seekTo(target)
    }

    private fun pauseAndSeek(positionUs: Long) {
        playback?.pause()
        seekTo(positionUs)
    }

    fun beginScrub() {
        if (scrubbing) return
        scrubbing = true
        resumeAfterScrub = playing
        playback?.pause()
    }

    fun endScrub() {
        if (!scrubbing) return
        scrubbing = false
        if (resumeAfterScrub) playback?.play() else snapToShownFrame()
        resumeAfterScrub = false
    }

    /** Moves one frame forward ([direction] > 0) or back and pauses. */
    fun stepFrame(direction: Int) {
        playback?.pause()
        val position = _positionUs.value
        val target = when {
            // Aim half a frame short of the wanted frame: the player then lands on it whatever its exact time.
            frameTimesKnown && direction > 0 -> position + frameDurationUs / 2
            frameTimesKnown -> position - frameDurationUs * 3 / 2
            direction > 0 -> position + frameDurationUs
            else -> position - frameDurationUs
        }
        seekTo(target)
    }

    fun togglePlay() {
        val player = playback ?: return
        if (playing) player.pause() else player.play()
    }

    fun setMuted(muted: Boolean) {
        _state.value = current.copy(muted = muted)
    }

    fun setTab(tab: EditorTab) {
        _state.value = current.copy(tab = tab)
    }

    fun selectPerson(personId: Int, openStyle: Boolean = false) {
        val person = result.person(personId) ?: return
        endGesture()
        _state.value = current.copy(
            selection = Selection.OfPerson(person.id),
            tab = if (openStyle) EditorTab.STYLE else current.tab,
            motionArmedRegionId = null,
            announcement = Announcement(++announcements, EditorEvent.PersonSelected(personNumber(person.id))),
        )
    }

    fun selectRegion(regionId: Int, openAreas: Boolean = true) {
        if (regionOf(edit, regionId) == null) return
        if (current.selection == Selection.OfRegion(regionId)) {
            if (openAreas) setTab(EditorTab.AREAS)
            return
        }
        endGesture()
        _state.value = current.copy(
            selection = Selection.OfRegion(regionId),
            tab = if (openAreas) EditorTab.AREAS else current.tab,
            motionArmedRegionId = null,
            announcement = Announcement(++announcements, EditorEvent.AreaSelected(regionId)),
        )
    }

    fun clearSelection() {
        if (current.selection == Selection.None) return
        endGesture()
        _state.value = current.copy(
            selection = Selection.None,
            motionArmedRegionId = null,
            announcement = Announcement(++announcements, EditorEvent.SelectionCleared),
        )
    }

    fun openPerson(personId: Int) {
        if (result.person(personId) != null) _state.value = current.copy(openPersonId = personId, tab = EditorTab.PEOPLE)
    }

    fun closePerson() {
        _state.value = current.copy(openPersonId = null)
    }

    fun setBriefExpanded(expanded: Boolean) {
        _state.value = current.copy(briefExpanded = expanded)
    }

    /** Closes the appearance list if it is showing, else clears the selection. Returns false when there was neither. */
    fun handleBack(): Boolean {
        if (showsAppearances(current)) {
            closePerson()
            return true
        }
        if (current.selection != Selection.None) {
            clearSelection()
            return true
        }
        return false
    }

    /** Blurs the person unless every appearance is blurred already, then shows them where they first appear. */
    fun togglePerson(personId: Int) {
        val person = result.person(personId) ?: return
        setPersonBlurred(person, EditOps.blurChoice(edit, person) != BlurChoice.ALL)
        if (!isOnScreen(result, person, _positionUs.value)) pauseAndSeek(firstAppearanceUs(result, person))
    }

    private fun setPersonBlurred(person: Person, blurred: Boolean) {
        val next = EditOps.setPersonBlurred(edit, person, blurred)
        commit(next, event = EditorEvent.PersonBlur(personNumber(person.id), EditOps.blurChoice(next, person)))
    }

    /** A tap on a face in the preview: the whole person takes the opposite of what that face shows now. */
    fun faceTapped(trackId: Int) {
        if (result.track(trackId) == null) return
        val blurred = !edit.isBlurred(trackId)
        val person = personByTrack[trackId]
        if (person != null) setPersonBlurred(person, blurred) else setTrackBlurred(trackId, blurred)
    }

    fun faceLongPressed(trackId: Int) {
        val person = personByTrack[trackId] ?: return
        selectPerson(person.id, openStyle = true)
    }

    fun setTrackBlurred(trackId: Int, blurred: Boolean) {
        if (result.track(trackId) == null) return
        commit(EditOps.setTrackBlurred(edit, trackId, blurred), event = EditorEvent.AppearanceBlur(blurred))
    }

    fun seekToTrack(trackId: Int) {
        val track = result.track(trackId) ?: return
        pauseAndSeek(track.startUs)
    }

    fun setEveryoneBlurred(blurred: Boolean) {
        commit(EditOps.setAllBlurred(edit, result, blurred), event = EditorEvent.EveryoneBlur(blurred))
    }

    fun setPeopleBlurred(people: List<Person>, blurred: Boolean) {
        var next = edit
        for (person in people) next = EditOps.setPersonBlurred(next, person, blurred)
        commit(next, event = EditorEvent.GroupBlur(blurred))
    }

    /**
     * Changes the style of the selected person or area, or of everyone when nothing is selected.
     * Pass a [gesture] name for slider moves so one drag is one undo step.
     */
    fun updateAppearance(gesture: String? = null, change: (MaskAppearance) -> MaskAppearance) {
        val selection = current.selection
        val changed = change(appearanceFor(edit, selection))
        val next = when (selection) {
            Selection.None -> edit.copy(appearance = changed)
            is Selection.OfPerson -> EditOps.setPersonAppearance(edit, selection.personId, changed)
            is Selection.OfRegion -> EditOps.setRegionAppearance(edit, selection.regionId, changed)
        }
        commit(next, key = gesture?.let { it to selection })
    }

    /** Drops the selected person's own style so they follow everyone's again. */
    fun resetPersonStyle() {
        val selection = current.selection as? Selection.OfPerson ?: return
        commit(EditOps.setPersonAppearance(edit, selection.personId, null))
    }

    /** Adds an area in the middle of the picture at the playhead, covering the whole video, and selects it. */
    fun addArea() {
        playback?.pause()
        val (next, id) = EditOps.addRegion(edit, _positionUs.value, durationUs, edit.appearance)
        commit(next)
        _state.value = current.copy(
            selection = Selection.OfRegion(id),
            tab = EditorTab.AREAS,
            openPersonId = null,
            motionArmedRegionId = null,
            announcement = Announcement(++announcements, EditorEvent.AreaAdded(id)),
        )
    }

    fun deleteArea(regionId: Int) {
        if (regionOf(edit, regionId) == null) return
        commit(EditOps.removeRegion(edit, regionId), event = EditorEvent.AreaDeleted(regionId))
    }

    fun setAreaStartHere(regionId: Int) {
        commit(EditOps.setRegionStart(edit, regionId, _positionUs.value))
    }

    fun setAreaEndHere(regionId: Int) {
        commit(EditOps.setRegionEnd(edit, regionId, _positionUs.value.coerceAtMost(durationUs)))
    }

    /** True when the area moves between several positions, or was asked to and waits for its second one. */
    fun followsMotion(regionId: Int): Boolean {
        val region = regionOf(edit, regionId) ?: return false
        return region.keyframes.size > 1 || current.motionArmedRegionId == regionId
    }

    /**
     * Turning motion on records the area's place at the playhead; from then on, moving the area at another
     * moment adds a position there. Turning it off keeps only the place the area has at the playhead.
     */
    fun setFollowMotion(regionId: Int, follow: Boolean) {
        val region = regionOf(edit, regionId) ?: return
        val time = _positionUs.value
        if (follow) {
            val here = EditOps.regionAt(region, time)
            commit(EditOps.placeRegion(edit, regionId, time, here.cx, here.cy, here.w, here.h, asKeyframe = true))
            _state.value = current.copy(motionArmedRegionId = regionId)
        } else {
            val here = EditOps.regionAt(region, time)
            var next = edit
            for (frame in region.keyframes.drop(1)) next = EditOps.removeKeyframe(next, regionId, frame.timeUs)
            next = EditOps.placeRegion(next, regionId, time, here.cx, here.cy, here.w, here.h)
            commit(next)
            _state.value = current.copy(motionArmedRegionId = null)
        }
    }

    fun removeKeyframe(regionId: Int, keyframeTimeUs: Long) {
        commit(EditOps.removeKeyframe(edit, regionId, keyframeTimeUs))
    }

    fun seekToKeyframe(keyframeTimeUs: Long) {
        pauseAndSeek(keyframeTimeUs)
    }

    /**
     * Starts a drag of the area and returns where it is now, or null when the area is gone.
     * The playhead time is held for the whole drag so every move lands on the same keyframe.
     */
    fun beginAreaGesture(regionId: Int): RegionKeyframe? {
        val region = regionOf(edit, regionId) ?: return null
        playback?.pause()
        endGesture()
        val time = _positionUs.value
        gestureTimeUs = time
        return EditOps.regionAt(region, time)
    }

    /** Puts the area at the given rectangle at the playhead. Calls sharing a [gesture] name merge into one undo step. */
    fun placeArea(regionId: Int, cx: Float, cy: Float, w: Float, h: Float, gesture: String = "area") {
        if (regionOf(edit, regionId) == null) return
        val time = gestureTimeUs ?: _positionUs.value
        val armed = current.motionArmedRegionId == regionId
        commit(EditOps.placeRegion(edit, regionId, time, cx, cy, w, h, asKeyframe = armed), key = gesture to regionId)
    }

    /** The area's rectangle at the playhead, for handles and sliders. */
    fun areaNow(regionId: Int, edit: EditState = this.edit, positionUs: Long = _positionUs.value): RegionKeyframe? =
        regionOf(edit, regionId)?.let { EditOps.regionAt(it, gestureTimeUs ?: positionUs) }

    /** True when the playhead is close enough to one of the area's positions that a move edits it. */
    fun isAtKeyframe(regionId: Int, positionUs: Long = _positionUs.value): Boolean {
        val region = regionOf(edit, regionId) ?: return false
        return region.keyframes.any { abs(it.timeUs - positionUs) <= EditOps.KEYFRAME_SNAP_US }
    }

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 100
    }
}
