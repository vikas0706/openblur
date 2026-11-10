package io.github.vikas0706.openblur.export

import io.github.vikas0706.openblur.core.EncodePlan
import io.github.vikas0706.openblur.core.PlanNotice
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.core.TransferKind

/** A ten second 1080p30 HEVC clip with one AAC track; each test changes the one property it is about. */
internal fun video(
    mimeType: String = "video/hevc",
    codedWidth: Int = 1920,
    codedHeight: Int = 1080,
    rotationDegrees: Int = 0,
    frameRate: Float = 30f,
    bitrate: Int = 16_000_000,
    durationUs: Long = 10_000_000L,
    frameCount: Int = 300,
    keyframeIntervalSeconds: Float = 1f,
    transfer: TransferKind = TransferKind.SDR,
    bitDepth: Int = 8,
    isDolbyVision: Boolean = false,
    audioMimeType: String? = "audio/mp4a-latm",
    audioTrackCount: Int = 1,
    hasLocation: Boolean = false,
    fileSizeBytes: Long = 21_000_000L,
) = SourceVideoInfo(
    mimeType = mimeType,
    codedWidth = codedWidth,
    codedHeight = codedHeight,
    rotationDegrees = rotationDegrees,
    frameRate = frameRate,
    bitrate = bitrate,
    durationUs = durationUs,
    frameCount = frameCount,
    keyframeIntervalSeconds = keyframeIntervalSeconds,
    transfer = transfer,
    bitDepth = bitDepth,
    isDolbyVision = isDolbyVision,
    audioMimeType = audioMimeType,
    audioTrackCount = audioTrackCount,
    hasLocation = hasLocation,
    fileSizeBytes = fileSizeBytes,
)

internal fun plan(
    mimeType: String = "video/hevc",
    bitrate: Int = 20_000_000,
    keepHdr: Boolean = false,
    strictSize: Boolean = true,
    notices: Set<PlanNotice> = emptySet(),
) = EncodePlan(
    mimeType = mimeType,
    bitrate = bitrate,
    iFrameIntervalSeconds = 1f,
    keepHdr = keepHdr,
    strictSize = strictSize,
    notices = notices,
)
