package io.github.vikas0706.openblur.core

enum class BlurChoice { ALL, NONE, MIXED }

/** Pure edits on [EditState]. Every function returns a new state and leaves its input untouched. */
object EditOps {
    /** Smallest manual area, as a fraction of the frame, so its handles stay grabbable. */
    const val MIN_REGION_SIZE = 0.03f

    /** A drag within this distance of an existing keyframe edits that keyframe instead of adding one. */
    const val KEYFRAME_SNAP_US = 50_000L

    fun setTrackBlurred(state: EditState, trackId: Int, blurred: Boolean): EditState {
        val visible = if (blurred) state.visibleTrackIds - trackId else state.visibleTrackIds + trackId
        return if (visible == state.visibleTrackIds) state else state.copy(visibleTrackIds = visible)
    }

    fun setPersonBlurred(state: EditState, person: Person, blurred: Boolean): EditState {
        val ids = person.trackIds.toSet()
        val visible = if (blurred) state.visibleTrackIds - ids else state.visibleTrackIds + ids
        return state.copy(visibleTrackIds = visible)
    }

    fun setAllBlurred(state: EditState, result: AnalysisResult, blurred: Boolean): EditState {
        val visible = if (blurred) emptySet() else result.tracks.mapTo(HashSet()) { it.id }
        return state.copy(visibleTrackIds = visible)
    }

    fun blurChoice(state: EditState, person: Person): BlurChoice {
        var blurred = 0
        for (id in person.trackIds) if (state.isBlurred(id)) blurred++
        return when (blurred) {
            person.trackIds.size -> BlurChoice.ALL
            0 -> BlurChoice.NONE
            else -> BlurChoice.MIXED
        }
    }

    /** Null [appearance] removes the override so the person follows the shared appearance again. */
    fun setPersonAppearance(state: EditState, personId: Int, appearance: MaskAppearance?): EditState {
        val map = if (appearance == null) state.personAppearance - personId else state.personAppearance + (personId to appearance)
        return state.copy(personAppearance = map)
    }

    /** Adds an area covering the whole video with one keyframe at [timeUs]. Returns the new state and the area's id. */
    fun addRegion(
        state: EditState,
        timeUs: Long,
        durationUs: Long,
        appearance: MaskAppearance,
        cx: Float = 0.5f,
        cy: Float = 0.5f,
        w: Float = 0.3f,
        h: Float = 0.2f,
    ): Pair<EditState, Int> {
        val id = (state.regions.maxOfOrNull { it.id } ?: 0) + 1
        val region = ManualRegion(
            id = id,
            appearance = appearance.copy(shape = if (appearance.shape == MaskShape.EYES) MaskShape.RECT else appearance.shape),
            startUs = 0L,
            endUs = durationUs,
            keyframes = listOf(clampKeyframe(RegionKeyframe(timeUs, cx, cy, w, h))),
        )
        return state.copy(regions = state.regions + region) to id
    }

    fun removeRegion(state: EditState, regionId: Int): EditState =
        state.copy(regions = state.regions.filterNot { it.id == regionId })

    fun setRegionAppearance(state: EditState, regionId: Int, appearance: MaskAppearance): EditState =
        updateRegion(state, regionId) {
            it.copy(appearance = appearance.copy(shape = if (appearance.shape == MaskShape.EYES) MaskShape.RECT else appearance.shape))
        }

    /**
     * Places the area at the given rectangle at [timeUs]. With a single keyframe the area simply moves;
     * otherwise this edits the keyframe within [KEYFRAME_SNAP_US] of [timeUs] or adds a new one there.
     * Pass [asKeyframe] = true to add a second keyframe to an area that has only one, which starts motion.
     */
    fun placeRegion(
        state: EditState,
        regionId: Int,
        timeUs: Long,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        asKeyframe: Boolean = false,
    ): EditState = updateRegion(state, regionId) { region ->
        val placed = clampKeyframe(RegionKeyframe(timeUs, cx, cy, w, h))
        val frames = region.keyframes
        if (frames.size == 1 && !asKeyframe) {
            return@updateRegion region.copy(keyframes = listOf(placed.copy(timeUs = frames[0].timeUs)))
        }
        val near = frames.indexOfFirst { kotlin.math.abs(it.timeUs - timeUs) <= KEYFRAME_SNAP_US }
        val next = if (near >= 0) {
            frames.toMutableList().also { it[near] = placed.copy(timeUs = frames[near].timeUs) }
        } else {
            (frames + placed).sortedBy { it.timeUs }
        }
        region.copy(keyframes = next)
    }

    /** Removes the keyframe at [keyframeTimeUs]; the last remaining keyframe cannot be removed. */
    fun removeKeyframe(state: EditState, regionId: Int, keyframeTimeUs: Long): EditState =
        updateRegion(state, regionId) { region ->
            if (region.keyframes.size <= 1) region
            else region.copy(keyframes = region.keyframes.filterNot { it.timeUs == keyframeTimeUs })
        }

    /** Sets when the area starts. The start is kept at or before the end. */
    fun setRegionStart(state: EditState, regionId: Int, startUs: Long): EditState =
        updateRegion(state, regionId) { it.copy(startUs = startUs.coerceIn(0L, it.endUs)) }

    /** Sets when the area ends. The end is kept at or after the start. */
    fun setRegionEnd(state: EditState, regionId: Int, endUs: Long): EditState =
        updateRegion(state, regionId) { it.copy(endUs = maxOf(endUs, it.startUs)) }

    /** The area's rectangle at [timeUs] whether or not it is active then. The result carries [timeUs]. */
    fun regionAt(region: ManualRegion, timeUs: Long): RegionKeyframe {
        val frames = region.keyframes
        val first = frames.first()
        if (timeUs <= first.timeUs) return first.copy(timeUs = timeUs)
        val last = frames.last()
        if (timeUs >= last.timeUs) return last.copy(timeUs = timeUs)
        var i = 0
        while (frames[i + 1].timeUs < timeUs) i++
        val a = frames[i]
        val b = frames[i + 1]
        val t = (timeUs - a.timeUs).toFloat() / (b.timeUs - a.timeUs).toFloat()
        return RegionKeyframe(
            timeUs = timeUs,
            cx = a.cx + (b.cx - a.cx) * t,
            cy = a.cy + (b.cy - a.cy) * t,
            w = a.w + (b.w - a.w) * t,
            h = a.h + (b.h - a.h) * t,
        )
    }

    /** Keeps the rectangle at least [MIN_REGION_SIZE] in each direction and its centre inside the frame. */
    fun clampKeyframe(k: RegionKeyframe): RegionKeyframe = k.copy(
        cx = k.cx.coerceIn(0f, 1f),
        cy = k.cy.coerceIn(0f, 1f),
        w = k.w.coerceIn(MIN_REGION_SIZE, 1f),
        h = k.h.coerceIn(MIN_REGION_SIZE, 1f),
    )

    private inline fun updateRegion(state: EditState, regionId: Int, change: (ManualRegion) -> ManualRegion): EditState {
        var changed = false
        val next = state.regions.map { region ->
            if (region.id == regionId) change(region).also { if (it != region) changed = true } else region
        }
        return if (changed) state.copy(regions = next) else state
    }
}
