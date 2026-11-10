package io.github.vikas0706.openblur.editor

import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.BlurChoice
import io.github.vikas0706.openblur.core.EditOps
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.FaceTrack
import io.github.vikas0706.openblur.core.MaskAppearance
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.MaskStyle
import io.github.vikas0706.openblur.core.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun track(id: Int, personId: Int, startUs: Long, endUs: Long) = FaceTrack(
    id = id,
    personId = personId,
    timesUs = longArrayOf(startUs, endUs),
    boxes = floatArrayOf(0.5f, 0.5f, 0.1f, 0.1f, 0.5f, 0.5f, 0.1f, 0.1f),
    eyes = FloatArray(8) { Float.NaN },
    sampleKinds = ByteArray(2),
    thumbnailHandle = -1,
    meanScore = 0.9f,
)

internal fun person(id: Int, vararg tracks: FaceTrack) = Person(
    id = id,
    trackIds = IntArray(tracks.size) { tracks[it].id },
    thumbnailHandle = -1,
    firstSeenUs = tracks.minOf { it.startUs },
    visibleUs = tracks.sumOf { it.endUs - it.startUs },
)

/** Ten seconds at 25 fps: Anna (6 s, two tracks), Ben (2 s), and two faces seen for under 0.7 s. */
internal fun sampleResult(): AnalysisResult {
    val t1 = track(1, 10, 0L, 4_000_000L)
    val t2 = track(2, 10, 6_000_000L, 8_000_000L)
    val t3 = track(3, 20, 1_000_000L, 3_000_000L)
    val t4 = track(4, 30, 5_000_000L, 5_500_000L)
    val t5 = track(5, 40, 9_000_000L, 9_300_000L)
    return AnalysisResult(
        frameWidth = 1920,
        frameHeight = 1080,
        durationUs = 10_000_000L,
        tracks = listOf(t1, t2, t3, t4, t5),
        people = listOf(person(10, t1, t2), person(20, t3), person(30, t4), person(40, t5)),
        cutsUs = LongArray(0),
        framesSeen = 250,
        framesDetected = 250,
    )
}

private class FakePlayback : Playback {
    val seeks = ArrayList<Long>()
    var pauses = 0
    var plays = 0

    override fun seekTo(positionUs: Long) {
        seeks += positionUs
    }

    override fun play() {
        plays++
    }

    override fun pause() {
        pauses++
    }
}

class EditorViewModelTest {
    private val result = sampleResult()
    private val playback = FakePlayback()

    private fun model(edit: EditState = EditState(), historyLimit: Int = 100) =
        EditorViewModel(result, edit, frameRate = 25f, historyLimit = historyLimit).also { it.playback = playback }

    private val EditorViewModel.edit get() = state.value.edit

    @Test
    fun startsFromTheGivenEditWithNothingToUndo() {
        val start = EditState(visibleTrackIds = setOf(3))
        val vm = model(start)
        assertEquals(start, vm.edit)
        assertFalse(vm.state.value.canUndo)
        assertFalse(vm.state.value.canRedo)
        assertEquals(40_000L, vm.frameDurationUs)
    }

    @Test
    fun undoAndRedoWalkThroughEdits() {
        val vm = model()
        vm.setTrackBlurred(3, false)
        vm.setTrackBlurred(1, false)
        assertEquals(setOf(3, 1), vm.edit.visibleTrackIds)
        assertTrue(vm.state.value.canUndo)

        vm.undo()
        assertEquals(setOf(3), vm.edit.visibleTrackIds)
        assertTrue(vm.state.value.canRedo)
        vm.undo()
        assertEquals(emptySet<Int>(), vm.edit.visibleTrackIds)
        assertFalse(vm.state.value.canUndo)
        vm.undo()
        assertEquals(emptySet<Int>(), vm.edit.visibleTrackIds)

        vm.redo()
        vm.redo()
        assertEquals(setOf(3, 1), vm.edit.visibleTrackIds)
        assertFalse(vm.state.value.canRedo)
        assertEquals(EditorEvent.Redone, vm.state.value.announcement?.event)
    }

    @Test
    fun aNewEditDropsTheRedoHistory() {
        val vm = model()
        vm.setTrackBlurred(3, false)
        vm.undo()
        vm.setTrackBlurred(1, false)
        assertFalse(vm.state.value.canRedo)
        vm.redo()
        assertEquals(setOf(1), vm.edit.visibleTrackIds)
    }

    @Test
    fun anEditThatChangesNothingAddsNoUndoStep() {
        val vm = model()
        vm.setTrackBlurred(3, true)
        vm.setEveryoneBlurred(true)
        assertFalse(vm.state.value.canUndo)
    }

    @Test
    fun historyIsBounded() {
        val vm = model(historyLimit = 3)
        for (id in 1..5) vm.setTrackBlurred(id, false)
        repeat(10) { vm.undo() }
        assertEquals(setOf(1, 2), vm.edit.visibleTrackIds)
        assertFalse(vm.state.value.canUndo)
    }

    @Test
    fun oneSliderDragIsOneUndoStep() {
        val vm = model()
        for (step in 1..5) vm.updateAppearance("strength") { it.copy(strength = step / 10f) }
        vm.endGesture()
        vm.updateAppearance("strength") { it.copy(strength = 0.9f) }
        vm.endGesture()
        assertEquals(0.9f, vm.edit.appearance.strength, 0f)
        vm.undo()
        assertEquals(0.5f, vm.edit.appearance.strength, 0f)
        vm.undo()
        assertEquals(MaskAppearance().strength, vm.edit.appearance.strength, 0f)
        assertFalse(vm.state.value.canUndo)
    }

    @Test
    fun differentSlidersDoNotMerge() {
        val vm = model()
        vm.updateAppearance("strength") { it.copy(strength = 0.2f) }
        vm.updateAppearance("size") { it.copy(scale = 1.5f) }
        vm.undo()
        assertEquals(0.2f, vm.edit.appearance.strength, 0f)
        assertEquals(1f, vm.edit.appearance.scale, 0f)
    }

    @Test
    fun togglingAPersonFlipsBetweenBlurredAndVisible() {
        val vm = model()
        vm.seekTo(2_000_000L)
        vm.togglePerson(10)
        assertEquals(setOf(1, 2), vm.edit.visibleTrackIds)
        assertEquals(EditorEvent.PersonBlur(1, BlurChoice.NONE), vm.state.value.announcement?.event)
        vm.togglePerson(10)
        assertEquals(emptySet<Int>(), vm.edit.visibleTrackIds)
        assertEquals(EditorEvent.PersonBlur(1, BlurChoice.ALL), vm.state.value.announcement?.event)
    }

    @Test
    fun togglingAPartlyBlurredPersonBlursThem() {
        val vm = model(EditState(visibleTrackIds = setOf(2)))
        vm.seekTo(2_000_000L)
        vm.togglePerson(10)
        assertEquals(emptySet<Int>(), vm.edit.visibleTrackIds)
    }

    @Test
    fun togglingAPersonWhoIsOffScreenSeeksToTheirFirstAppearance() {
        val vm = model()
        vm.seekTo(5_000_000L)
        playback.seeks.clear()
        playback.pauses = 0
        vm.togglePerson(20)
        assertEquals(listOf(1_000_000L), playback.seeks)
        assertEquals(1, playback.pauses)
        assertEquals(1_000_000L, vm.positionUs.value)
    }

    @Test
    fun togglingAPersonWhoIsOnScreenDoesNotSeek() {
        val vm = model()
        vm.seekTo(2_000_000L)
        playback.seeks.clear()
        vm.togglePerson(20)
        assertTrue(playback.seeks.isEmpty())
        assertEquals(2_000_000L, vm.positionUs.value)
    }

    @Test
    fun tappingAFaceTogglesItsWholePersonFromWhatThatFaceShows() {
        val vm = model(EditState(visibleTrackIds = setOf(2)))
        vm.faceTapped(1)
        assertEquals(setOf(1, 2), vm.edit.visibleTrackIds)
        vm.faceTapped(2)
        assertEquals(emptySet<Int>(), vm.edit.visibleTrackIds)
        vm.faceTapped(99)
        assertEquals(emptySet<Int>(), vm.edit.visibleTrackIds)
    }

    @Test
    fun longPressSelectsThePersonAndOpensStyle() {
        val vm = model()
        vm.faceLongPressed(3)
        assertEquals(Selection.OfPerson(20), vm.state.value.selection)
        assertEquals(EditorTab.STYLE, vm.state.value.tab)
        assertEquals(EditorEvent.PersonSelected(2), vm.state.value.announcement?.event)
    }

    @Test
    fun oneAppearanceCanBeToggledAlone() {
        val vm = model()
        vm.setTrackBlurred(2, false)
        assertEquals(BlurChoice.MIXED, EditOps.blurChoice(vm.edit, result.person(10)!!))
        vm.seekToTrack(2)
        assertEquals(6_000_000L, vm.positionUs.value)
    }

    @Test
    fun blurEveryoneAndShowEveryone() {
        val vm = model()
        vm.setEveryoneBlurred(false)
        assertEquals(setOf(1, 2, 3, 4, 5), vm.edit.visibleTrackIds)
        vm.setEveryoneBlurred(true)
        assertEquals(emptySet<Int>(), vm.edit.visibleTrackIds)
    }

    @Test
    fun briefFacesAreGroupedAndTheRestOrderedByTimeOnScreen() {
        val list = peopleList(result, EditState(visibleTrackIds = setOf(4)))
        assertEquals(listOf(10, 20), list.main.map { it.person.id })
        assertEquals(listOf(30, 40), list.brief.map { it.person.id })
        assertEquals(listOf(1, 2), list.main.map { it.number })
        assertEquals(listOf(3, 4), list.brief.map { it.number })
        assertEquals(BlurChoice.MIXED, groupChoice(list.brief))
        assertEquals(BlurChoice.ALL, groupChoice(list.main))
        assertFalse(list.isEmpty)
    }

    @Test
    fun aFaceSeenForExactlyTheThresholdIsNotBrief() {
        val t = track(1, 1, 0L, BRIEF_FACE_US)
        val r = AnalysisResult(100, 100, 1_000_000L, listOf(t), listOf(person(1, t)), LongArray(0), 1, 1)
        val list = peopleList(r, EditState())
        assertEquals(1, list.main.size)
        assertTrue(list.brief.isEmpty())
    }

    @Test
    fun theBriefGroupTogglesTogether() {
        val vm = model()
        val brief = peopleList(result, vm.edit).brief.map { it.person }
        vm.setPeopleBlurred(brief, false)
        assertEquals(setOf(4, 5), vm.edit.visibleTrackIds)
        vm.undo()
        assertEquals(emptySet<Int>(), vm.edit.visibleTrackIds)
    }

    @Test
    fun styleAppliesToEveryoneWhenNothingIsSelected() {
        val vm = model()
        vm.updateAppearance { it.copy(style = MaskStyle.MOSAIC) }
        assertEquals(MaskStyle.MOSAIC, vm.edit.appearance.style)
        assertTrue(vm.edit.personAppearance.isEmpty())
    }

    @Test
    fun styleAppliesToTheSelectedPersonAndCanBeReset() {
        val vm = model()
        vm.selectPerson(20)
        vm.updateAppearance { it.copy(style = MaskStyle.SOLID, solidColor = 0xFFFFFFFF.toInt()) }
        assertEquals(MaskStyle.BLUR, vm.edit.appearance.style)
        assertEquals(MaskStyle.SOLID, vm.edit.appearanceFor(20).style)
        assertEquals(MaskStyle.SOLID, appearanceFor(vm.edit, vm.state.value.selection).style)
        assertTrue(peopleList(result, vm.edit).main.first { it.person.id == 20 }.hasOwnStyle)

        vm.resetPersonStyle()
        assertTrue(vm.edit.personAppearance.isEmpty())
        vm.undo()
        assertEquals(MaskStyle.SOLID, vm.edit.appearanceFor(20).style)
    }

    @Test
    fun styleAppliesToTheSelectedAreaAndNeverUsesEyes() {
        val vm = model(EditState(appearance = MaskAppearance(shape = MaskShape.EYES)))
        vm.addArea()
        val id = (vm.state.value.selection as Selection.OfRegion).regionId
        assertEquals(MaskShape.RECT, regionOf(vm.edit, id)!!.appearance.shape)
        vm.updateAppearance { it.copy(style = MaskStyle.MOSAIC, shape = MaskShape.EYES) }
        val region = regionOf(vm.edit, id)!!
        assertEquals(MaskStyle.MOSAIC, region.appearance.style)
        assertEquals(MaskShape.RECT, region.appearance.shape)
        assertEquals(MaskShape.EYES, vm.edit.appearance.shape)
    }

    @Test
    fun addingAnAreaSelectsItAndCoversTheWholeVideo() {
        val vm = model()
        vm.seekTo(3_000_000L)
        vm.addArea()
        val state = vm.state.value
        val region = state.edit.regions.single()
        assertEquals(Selection.OfRegion(region.id), state.selection)
        assertEquals(EditorTab.AREAS, state.tab)
        assertEquals(0L, region.startUs)
        assertEquals(10_000_000L, region.endUs)
        assertEquals(3_000_000L, region.keyframes.single().timeUs)
        assertEquals(0.5f, region.keyframes.single().cx, 0f)
        assertEquals(EditorEvent.AreaAdded(region.id), state.announcement?.event)

        vm.undo()
        assertTrue(vm.edit.regions.isEmpty())
        assertEquals(Selection.None, vm.state.value.selection)
        vm.redo()
        assertEquals(1, vm.edit.regions.size)
    }

    @Test
    fun startsHereAndEndsHereUseThePlayhead() {
        val vm = model()
        vm.addArea()
        val id = vm.edit.regions.single().id
        vm.seekTo(2_000_000L)
        vm.setAreaStartHere(id)
        vm.seekTo(7_000_000L)
        vm.setAreaEndHere(id)
        assertEquals(2_000_000L, regionOf(vm.edit, id)!!.startUs)
        assertEquals(7_000_000L, regionOf(vm.edit, id)!!.endUs)

        vm.seekTo(9_000_000L)
        vm.setAreaStartHere(id)
        assertEquals(7_000_000L, regionOf(vm.edit, id)!!.startUs)
        vm.seekTo(1_000_000L)
        vm.setAreaEndHere(id)
        assertEquals(7_000_000L, regionOf(vm.edit, id)!!.endUs)
    }

    @Test
    fun draggingAStillAreaMovesItEverywhereInOneUndoStep() {
        val vm = model()
        vm.addArea()
        val id = vm.edit.regions.single().id
        vm.seekTo(4_000_000L)
        val start = vm.beginAreaGesture(id)
        assertNotNull(start)
        for (i in 1..4) vm.placeArea(id, 0.5f + i * 0.05f, 0.5f, 0.3f, 0.2f)
        vm.endGesture()
        val region = regionOf(vm.edit, id)!!
        assertEquals(1, region.keyframes.size)
        assertEquals(0.7f, region.keyframes[0].cx, 1e-6f)
        assertEquals(0L, region.keyframes[0].timeUs)
        vm.undo()
        assertEquals(0.5f, regionOf(vm.edit, id)!!.keyframes[0].cx, 0f)
    }

    @Test
    fun followMotionRecordsPositionsAtOtherMoments() {
        val vm = model()
        vm.addArea()
        val id = vm.edit.regions.single().id
        assertFalse(vm.followsMotion(id))

        vm.setFollowMotion(id, true)
        assertTrue(vm.followsMotion(id))
        assertEquals(1, regionOf(vm.edit, id)!!.keyframes.size)
        assertTrue(vm.isAtKeyframe(id))

        vm.seekTo(5_000_000L)
        assertFalse(vm.isAtKeyframe(id))
        vm.beginAreaGesture(id)
        vm.placeArea(id, 0.8f, 0.4f, 0.3f, 0.2f)
        vm.placeArea(id, 0.9f, 0.4f, 0.3f, 0.2f)
        vm.endGesture()
        val frames = regionOf(vm.edit, id)!!.keyframes
        assertEquals(listOf(0L, 5_000_000L), frames.map { it.timeUs })
        assertEquals(0.5f, frames[0].cx, 0f)
        assertEquals(0.9f, frames[1].cx, 0f)
        assertEquals(listOf(0L, 5_000_000L), keyframeMarks(vm.edit, vm.state.value.selection))
        assertEquals(0.7f, vm.areaNow(id, positionUs = 2_500_000L)!!.cx, 1e-5f)
    }

    @Test
    fun followMotionAwayFromTheFirstPositionAddsOneAtOnce() {
        val vm = model()
        vm.addArea()
        val id = vm.edit.regions.single().id
        vm.seekTo(6_000_000L)
        vm.setFollowMotion(id, true)
        assertEquals(listOf(0L, 6_000_000L), regionOf(vm.edit, id)!!.keyframes.map { it.timeUs })
    }

    @Test
    fun positionsCanBeRemovedAndMotionTurnedOff() {
        val vm = model()
        vm.addArea()
        val id = vm.edit.regions.single().id
        vm.setFollowMotion(id, true)
        for (second in listOf(3L, 6L)) {
            vm.seekTo(second * 1_000_000L)
            vm.placeArea(id, 0.1f * second, 0.5f, 0.3f, 0.2f)
            vm.endGesture()
        }
        assertEquals(3, regionOf(vm.edit, id)!!.keyframes.size)

        vm.removeKeyframe(id, 3_000_000L)
        assertEquals(listOf(0L, 6_000_000L), regionOf(vm.edit, id)!!.keyframes.map { it.timeUs })

        vm.seekTo(6_000_000L)
        vm.setFollowMotion(id, false)
        val frames = regionOf(vm.edit, id)!!.keyframes
        assertEquals(1, frames.size)
        assertEquals(0.6f, frames[0].cx, 1e-6f)
        assertFalse(vm.followsMotion(id))
        assertTrue(keyframeMarks(vm.edit, vm.state.value.selection).isEmpty())
    }

    @Test
    fun deletingTheSelectedAreaClearsTheSelection() {
        val vm = model()
        vm.addArea()
        val id = vm.edit.regions.single().id
        vm.deleteArea(id)
        assertTrue(vm.edit.regions.isEmpty())
        assertEquals(Selection.None, vm.state.value.selection)
        assertEquals(EditorEvent.AreaDeleted(id), vm.state.value.announcement?.event)
        vm.undo()
        assertEquals(id, vm.edit.regions.single().id)
    }

    @Test
    fun presenceFollowsTheSelection() {
        val vm = model(EditState(visibleTrackIds = setOf(2)))
        assertTrue(presenceRanges(result, vm.edit, Selection.None).isEmpty())
        assertEquals(
            listOf(PresenceRange(0L, 4_000_000L, true), PresenceRange(6_000_000L, 8_000_000L, false)),
            presenceRanges(result, vm.edit, Selection.OfPerson(10)),
        )
        vm.addArea()
        assertEquals(
            listOf(PresenceRange(0L, 10_000_000L, true)),
            presenceRanges(result, vm.edit, vm.state.value.selection),
        )
    }

    @Test
    fun backClosesTheAppearanceListThenTheSelectionThenNothing() {
        val vm = model()
        vm.selectPerson(10)
        vm.openPerson(10)
        assertTrue(backClosesSomething(vm.state.value))
        assertTrue(vm.handleBack())
        assertNull(vm.state.value.openPersonId)
        assertEquals(Selection.OfPerson(10), vm.state.value.selection)
        assertTrue(vm.handleBack())
        assertEquals(Selection.None, vm.state.value.selection)
        assertFalse(backClosesSomething(vm.state.value))
        assertFalse(vm.handleBack())
    }

    @Test
    fun backSkipsAnAppearanceListHiddenBehindAnotherTab() {
        val vm = model()
        vm.openPerson(10)
        vm.selectPerson(10, openStyle = true)
        assertTrue(vm.handleBack())
        assertEquals(Selection.None, vm.state.value.selection)
        assertEquals(10, vm.state.value.openPersonId)
        assertFalse(backClosesSomething(vm.state.value))
        assertFalse(vm.handleBack())
    }

    @Test
    fun pausingMovesThePlayheadOntoTheFrameOnScreen() {
        val vm = model()
        vm.onPlayingChanged(true)
        vm.onFrameShown(2_000_000L)
        vm.onPlayerPosition(2_013_000L)
        vm.onPlayingChanged(false)
        assertEquals(2_000_000L, vm.positionUs.value)
    }

    @Test
    fun frameStepsUseTheFrameRateUntilFrameTimesAreKnown() {
        val vm = model()
        vm.seekTo(1_000_000L)
        vm.stepFrame(1)
        assertEquals(1_040_000L, vm.positionUs.value)
        vm.stepFrame(-1)
        vm.stepFrame(-1)
        assertEquals(960_000L, vm.positionUs.value)
        vm.seekTo(0L)
        vm.stepFrame(-1)
        assertEquals(0L, vm.positionUs.value)
        vm.seekTo(10_000_000L)
        vm.stepFrame(1)
        assertEquals(10_000_000L, vm.positionUs.value)
    }

    @Test
    fun frameStepsLandOnRealFrameTimesOnceTheyAreKnown() {
        val vm = model()
        vm.seekTo(1_000_000L)
        vm.onFrameShown(1_000_000L)
        vm.stepFrame(1)
        assertEquals(1_020_000L, playback.seeks.last())
        vm.onFrameShown(1_040_017L)
        assertEquals(1_040_017L, vm.positionUs.value)
        vm.stepFrame(-1)
        assertEquals(980_017L, playback.seeks.last())
        vm.onFrameShown(1_000_000L)
        assertEquals(1_000_000L, vm.positionUs.value)
    }

    @Test
    fun frameTimesAreIgnoredWhilePlayingOrFarFromThePlayhead() {
        val vm = model()
        vm.seekTo(1_000_000L)
        vm.onFrameShown(5_000_000L)
        assertEquals(1_000_000L, vm.positionUs.value)
        vm.onPlayingChanged(true)
        vm.onFrameShown(1_010_000L)
        assertEquals(1_000_000L, vm.positionUs.value)
        vm.onPlayerPosition(1_500_000L)
        assertEquals(1_500_000L, vm.positionUs.value)
    }

    @Test
    fun scrubbingPausesAndResumesPlayback() {
        val vm = model()
        vm.onPlayingChanged(true)
        vm.beginScrub()
        vm.onPlayingChanged(false)
        vm.seekTo(4_000_000L)
        vm.onPlayerPosition(100L)
        assertEquals(4_000_000L, vm.positionUs.value)
        vm.endScrub()
        assertEquals(1, playback.pauses)
        assertEquals(1, playback.plays)

        vm.beginScrub()
        vm.endScrub()
        assertEquals(1, playback.plays)
    }

    @Test
    fun seeksAreClampedToTheVideo() {
        val vm = model()
        vm.seekTo(-5L)
        assertEquals(0L, vm.positionUs.value)
        vm.seekTo(99_000_000L)
        assertEquals(10_000_000L, vm.positionUs.value)
    }

    @Test
    fun worksWithoutAPlayerAttached() {
        val vm = EditorViewModel(result, EditState(), frameRate = 0f)
        assertEquals(33_333L, vm.frameDurationUs)
        vm.seekTo(2_000_000L)
        vm.togglePlay()
        vm.togglePerson(20)
        vm.addArea()
        assertEquals(1, vm.state.value.edit.regions.size)
    }
}
