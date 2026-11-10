package io.github.vikas0706.openblur

import android.app.Application
import io.github.vikas0706.openblur.analysis.AssetModels
import io.github.vikas0706.openblur.analysis.FileProjectStore
import io.github.vikas0706.openblur.analysis.Media3VideoAnalyzer
import io.github.vikas0706.openblur.api.AppServices
import io.github.vikas0706.openblur.api.ExportController
import io.github.vikas0706.openblur.api.MaskEffectFactory
import io.github.vikas0706.openblur.api.ProjectStore
import io.github.vikas0706.openblur.api.SourceInspector
import io.github.vikas0706.openblur.api.VideoAnalyzer
import io.github.vikas0706.openblur.core.FaceDetector
import io.github.vikas0706.openblur.core.FaceDetectorFactory
import io.github.vikas0706.openblur.core.MaskTimelineFactory
import io.github.vikas0706.openblur.editing.BinaryEditStateCodec
import io.github.vikas0706.openblur.editing.DefaultEncoderPolicy
import io.github.vikas0706.openblur.editing.DefaultMaskTimelineFactory
import io.github.vikas0706.openblur.export.DefaultExportController
import io.github.vikas0706.openblur.export.Media3SourceInspector
import io.github.vikas0706.openblur.render.GlMaskEffectFactory
import io.github.vikas0706.openblur.tracking.BinaryAnalysisResultCodec
import io.github.vikas0706.openblur.tracking.DefaultFaceAnalyzerFactory
import io.github.vikas0706.openblur.vision.OrtFaceDetectorFactory
import io.github.vikas0706.openblur.vision.SFaceEmbedder

internal object Wiring {
    /** Reads the model on the scan's own thread the first time a detector is needed, never on the main thread. */
    private class LazyDetectors(load: () -> FaceDetectorFactory) : FaceDetectorFactory {
        private val factory by lazy(load)

        override fun create(inputWidth: Int, inputHeight: Int): FaceDetector = factory.create(inputWidth, inputHeight)
    }

    fun create(app: Application): AppServices = RealServices(app)

    private class RealServices(app: Application) : AppServices {
        private val models = AssetModels(app)

        // Leaves the other cores to the decoder and the UI thread.
        private val detectorThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

        override val inspector: SourceInspector = Media3SourceInspector(app)

        override val timelines: MaskTimelineFactory = DefaultMaskTimelineFactory()

        override val maskEffects: MaskEffectFactory = GlMaskEffectFactory(app)

        override val analyzer: VideoAnalyzer by lazy {
            Media3VideoAnalyzer(
                context = app,
                detectors = LazyDetectors { OrtFaceDetectorFactory(models.detectorModel(), detectorThreads) },
                embedderProvider = { SFaceEmbedder(models.embedderModel()) },
                analyzers = DefaultFaceAnalyzerFactory(),
            )
        }

        override val exporter: ExportController = DefaultExportController(
            context = app,
            policy = DefaultEncoderPolicy(),
            maskEffects = maskEffects,
            inspector = inspector,
        )

        override val projects: ProjectStore = FileProjectStore(
            context = app,
            resultCodec = BinaryAnalysisResultCodec(),
            editCodec = BinaryEditStateCodec(),
        )
    }
}
