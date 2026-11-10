package io.github.vikas0706.openblur.tracking

/** Thresholds of the scan. Scores are detector scores, times are microseconds, sizes are frame pixels. */
internal object Tuning {
    /** Detections at or above this score take part in the first association pass. */
    const val STRONG_SCORE = 0.5f
    const val NEW_TRACK_SCORE = 0.6f

    /** A face seen in one detector run only is kept when that detection scored at least this. */
    const val SINGLE_RUN_SCORE = 0.7f

    /** Boxes are widened by this factor before overlap is measured, so a small fast face still meets its prediction. */
    const val MATCH_EXPANSION = 1.4f

    /** First pass accepts a pair when widened overlap times score exceeds this. */
    const val STRONG_MATCH_FLOOR = 0.2f

    /** Second pass and backward recovery accept a pair when widened overlap exceeds this. */
    const val WEAK_MATCH_FLOOR = 0.5f

    /** A match after the face went unseen for longer than this, or with less widened overlap, is doubtful. */
    const val DOUBT_GAP_US = 110_000L
    const val DOUBT_OVERLAP = 0.5f

    /** Every track is also checked against its own appearance this often: a hand-over can leave no other trace. */
    const val IDENTITY_CHECK_INTERVAL_US = 1_000_000L

    /** Below this similarity to the track's embedding a detection is taken for someone else. */
    const val REJECT_COSINE = 0.25f

    /** Checks use only detections at least this good, because a poor crop embeds unreliably. */
    const val MIN_CHECK_QUALITY = 0.3f

    /** Each check costs an embedding, so a run makes at most this many: for doubtful matches, and for the routine. */
    const val MAX_DOUBT_CHECKS_PER_RUN = 3
    const val MAX_ROUTINE_CHECKS_PER_RUN = 1

    /** Longest stretch without a detection that a track survives; every gap inside a track is interpolated. */
    const val MAX_GAP_US = 670_000L

    /** A track always survives this many missed runs, so a slow detector cadence can still bridge a miss. */
    const val MIN_BRIDGED_RUNS = 2

    const val ROLL_US = 200_000L

    /** Extrapolated boxes grow by this fraction per second, about 3 percent per frame at 30 fps. */
    const val ROLL_GROWTH_PER_SECOND = 0.9

    /** Interpolated boxes are this much larger because the face was not seen there. */
    const val INTERPOLATED_INFLATION = 1.15

    /** Time over which a neighbor's weight falls to 1/e when box centers and eyes are smoothed. */
    const val CENTER_SMOOTH_TAU_US = 40_000L

    /** Box sizes are held at their largest value within this distance in time, then averaged over it. */
    const val SIZE_SMOOTH_RADIUS_US = 150_000L

    /** Smallest stored box side, as a fraction of the frame, so later divisions by a size stay safe. */
    const val MIN_BOX_SIZE = 1e-4

    const val MAX_SAMPLES_PER_TRACK = 5
    const val SAMPLE_SPACING_US = 500_000L

    /** A new crop replaces a kept one only when its quality is this many times higher, which bounds re-cropping. */
    const val SAMPLE_GAIN = 1.1f
    const val THUMBNAIL_GAIN = 1.25f

    /** Below this eye distance the aligned crop is mostly upsampling and its embedding is not trusted. */
    const val MIN_EMBED_EYE_DISTANCE_PX = 16f

    /** Eye distance of the 112 pixel alignment template; larger faces add no detail to the crop. */
    const val FULL_DETAIL_EYE_DISTANCE_PX = 35.2f

    const val THUMBNAIL_SIZE = 128
    const val THUMBNAIL_FACE_SCALE = 1.6f

    const val MERGE_COSINE = 0.5f

    /** Grouping keeps an n by n similarity table, so only this many tracks (the longest) are grouped. */
    const val MAX_GROUPED_TRACKS = 2048

    /** Detections kept per run, highest scores first. */
    const val MAX_DETECTIONS = 512

    /** Mean luma change, 0..255, below which a frame pair is never a cut. */
    const val CUT_MIN_SCORE = 12f

    /** A cut must change the picture this many times more than the runs around it do. */
    const val CUT_NEIGHBOR_RATIO = 3f

    /** A flash returns to the old picture; a cut must stay at least this fraction of its own score away from it. */
    const val CUT_PERSIST_FRACTION = 0.3f
    const val MIN_SHOT_US = 500_000L
}
