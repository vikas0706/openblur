package io.github.vikas0706.openblur.editing

import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.EditOps
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.ManualRegion
import io.github.vikas0706.openblur.core.MaskTimeline
import io.github.vikas0706.openblur.core.MaskTimelineFactory
import io.github.vikas0706.openblur.core.RegionKeyframe

/**
 * Builds timelines whose [MaskTimeline.sample] is safe from any thread and allocates only when the batch grows.
 * Building costs time proportional to the number of tracks, not samples, so it can run on every edit.
 */
class DefaultMaskTimelineFactory : MaskTimelineFactory {
    override fun build(result: AnalysisResult, edit: EditState): MaskTimeline = SnapshotMaskTimeline(result, edit)

    override fun regionAt(region: ManualRegion, timeUs: Long): RegionKeyframe = EditOps.regionAt(region, timeUs)
}
