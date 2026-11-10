package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.FaceAnalyzer
import io.github.vikas0706.openblur.core.FaceAnalyzerFactory
import io.github.vikas0706.openblur.core.FaceDetectorFactory
import io.github.vikas0706.openblur.core.FaceEmbedder
import io.github.vikas0706.openblur.core.ThumbnailStore
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Builds the analyzer that tracks faces through a video and groups them into people. */
class DefaultFaceAnalyzerFactory : FaceAnalyzerFactory {
    override fun create(
        frameWidth: Int,
        frameHeight: Int,
        options: AnalysisOptions,
        detectors: FaceDetectorFactory,
        embedder: FaceEmbedder?,
        thumbnails: ThumbnailStore,
    ): FaceAnalyzer {
        require(frameWidth > 0 && frameHeight > 0) { "frame size must be positive, got ${frameWidth}x$frameHeight" }
        require(options.detectorLongSide > 0) { "detectorLongSide must be positive, got ${options.detectorLongSide}" }
        require(options.maxDetectionsPerSecond > 0f) {
            "maxDetectionsPerSecond must be positive, got ${options.maxDetectionsPerSecond}"
        }
        val grouping = if (options.groupPeople) embedder else null
        if (grouping != null) {
            require(grouping.cropSize > 0 && grouping.embeddingSize > 0) {
                "embedder reports crop size ${grouping.cropSize} and embedding size ${grouping.embeddingSize}"
            }
        }
        val geometry = DetectorGeometry.of(frameWidth, frameHeight, options.detectorLongSide)
        val detector = detectors.create(geometry.inputWidth, geometry.inputHeight)
        try {
            check(detector.inputWidth == geometry.inputWidth && detector.inputHeight == geometry.inputHeight) {
                "detector was built for ${detector.inputWidth}x${detector.inputHeight}, " +
                    "asked for ${geometry.inputWidth}x${geometry.inputHeight}"
            }
            return DefaultFaceAnalyzer(frameWidth, frameHeight, geometry, options, detector, grouping, thumbnails)
        } catch (failure: Throwable) {
            try {
                detector.close()
            } catch (onClose: Exception) {
                failure.addSuppressed(onClose)
            }
            throw failure
        }
    }
}

/** Size of the picture handed to the detector: the frame scaled down, inside a buffer padded to multiples of 32. */
internal class DetectorGeometry(
    val contentWidth: Int,
    val contentHeight: Int,
    val inputWidth: Int,
    val inputHeight: Int,
) {
    companion object {
        fun of(frameWidth: Int, frameHeight: Int, detectorLongSide: Int): DetectorGeometry {
            val scale = min(1.0, detectorLongSide.toDouble() / max(frameWidth, frameHeight))
            val contentWidth = max(1, (frameWidth * scale).roundToInt())
            val contentHeight = max(1, (frameHeight * scale).roundToInt())
            return DetectorGeometry(contentWidth, contentHeight, roundUp32(contentWidth), roundUp32(contentHeight))
        }

        private fun roundUp32(value: Int): Int = (value + 31) / 32 * 32
    }
}
