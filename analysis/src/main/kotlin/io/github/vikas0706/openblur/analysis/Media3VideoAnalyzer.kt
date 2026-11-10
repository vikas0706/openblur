// Analyzer mode and the last-resort HDR mode are marked experimental in Media3 1.11.
@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package io.github.vikas0706.openblur.analysis

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExperimentalAnalyzerModeFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import io.github.vikas0706.openblur.api.AnalysisEvent
import io.github.vikas0706.openblur.api.AnalysisException
import io.github.vikas0706.openblur.api.AnalysisException.Kind
import io.github.vikas0706.openblur.api.VideoAnalyzer
import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.FaceAnalyzerFactory
import io.github.vikas0706.openblur.core.FaceDetectorFactory
import io.github.vikas0706.openblur.core.FaceEmbedder
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.core.ThumbnailStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * Decodes with a Media3 Transformer in analyzer mode: the export's extractor, decoder and frame pipeline with
 * nothing encoded or written, so the scan sees the timestamps and upright frames the export will see.
 */
class Media3VideoAnalyzer(
    context: Context,
    private val detectors: FaceDetectorFactory,
    private val embedderProvider: () -> FaceEmbedder?,
    private val analyzers: FaceAnalyzerFactory,
) : VideoAnalyzer {
    private val appContext: Context = context.applicationContext
    private val teardowns = TeardownGate()

    override fun analyze(
        uri: Uri,
        info: SourceVideoInfo,
        options: AnalysisOptions,
        thumbnails: ThumbnailStore,
    ): Flow<AnalysisEvent> = callbackFlow {
        val scan = Scan(
            context = appContext,
            uri = uri,
            info = info,
            options = options,
            thumbnails = thumbnails,
            detectors = detectors,
            embedderProvider = embedderProvider,
            analyzers = analyzers,
            teardowns = teardowns,
            sink = object : Scan.Sink {
                override fun progress(event: AnalysisEvent.Progress) {
                    trySend(event)
                }

                override fun finished(result: AnalysisResult) {
                    trySend(AnalysisEvent.Finished(result))
                    close()
                }

                override fun failed(error: AnalysisException) {
                    close(error)
                }
            },
        )
        try {
            scan.start()
        } catch (t: Throwable) {
            throw ScanErrors.fromThrowable(null, "Could not start the scan", t)
        }
        awaitClose { scan.stop() }
    }
        // A slow collector skips stale progress, and the newest event, which is the final one, always fits.
        .buffer(Channel.CONFLATED)
}

/**
 * One collection of the analyzer's flow. Owns a looper thread, because a Transformer must be created and
 * driven on one, and everything it holds: Transformer, analyzer, embedder and wake lock.
 */
internal class Scan(
    private val context: Context,
    private val uri: Uri,
    private val info: SourceVideoInfo,
    private val options: AnalysisOptions,
    private val thumbnails: ThumbnailStore,
    private val detectors: FaceDetectorFactory,
    private val embedderProvider: () -> FaceEmbedder?,
    private val analyzers: FaceAnalyzerFactory,
    private val teardowns: TeardownGate,
    private val sink: Sink,
) {
    /** Called on the scan thread. After [finished] or [failed] nothing more is reported. */
    interface Sink {
        fun progress(event: AnalysisEvent.Progress)

        fun finished(result: AnalysisResult)

        fun failed(error: AnalysisException)
    }

    private val thread = HandlerThread("OpenBlur:Scan")
    private val stopRequested = AtomicBoolean(false)
    private val teardownAnnounced = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)

    @Volatile
    private var handler: Handler? = null

    @Volatile
    private var session: ScanSession? = null

    // Everything below is touched on the scan thread only.
    private var transformer: Transformer? = null
    private var embedder: FaceEmbedder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var attempt = 0
    private var fraction = 0f
    private val earlierErrors = ArrayList<AnalysisException>()
    private val progressHolder = ProgressHolder()
    private var lastTickMs = 0L
    private var quietSinceMs = 0L
    private var lastActivity = -1L
    private var lastPercent = -1

    /**
     * Ways to get 8-bit SDR pixels out of HDR video, tried in order: Media3's GL tone map, the decoder's own,
     * then reading the HDR signal as if it were SDR, which is washed out but still shows faces.
     */
    private val hdrModes: IntArray =
        if (info.isHdr || info.isDolbyVision) {
            intArrayOf(
                Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
                Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC,
                Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR,
            )
        } else {
            intArrayOf(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        }

    private val ticker = object : Runnable {
        override fun run() {
            if (ended.get()) return
            tick()
            if (!ended.get()) handler?.postDelayed(this, TICK_MS)
        }
    }

    /** Any thread. */
    fun start() {
        thread.start()
        val handler = Handler(thread.looper)
        this.handler = handler
        handler.post(::begin)
    }

    /** Any thread, any number of times. Returns at once; the teardown runs on the scan thread. */
    fun stop() {
        if (!stopRequested.compareAndSet(false, true)) return
        if (ended.get()) return
        announceTeardown()
        session?.abandon()
        handler?.post(::end)
    }

    private fun begin() {
        if (stopRequested.get()) return end()
        teardowns.awaitIdle(TEARDOWN_WAIT_MS) { stopRequested.get() }
        if (stopRequested.get()) return end()
        try {
            acquireWakeLock()
            embedder = if (options.groupPeople) createEmbedder() else null
            sink.progress(AnalysisEvent.Progress(0f, 0, 0))
            startAttempt()
            handler?.postDelayed(ticker, TICK_MS)
        } catch (t: Throwable) {
            fail(ScanErrors.fromThrowable(null, "Could not start the scan", t))
        }
    }

    // Grouping is optional: without the embedding model the scan still finds and tracks every face.
    private fun createEmbedder(): FaceEmbedder? = try {
        embedderProvider()
    } catch (t: Throwable) {
        Log.w(TAG, "Face grouping is off for this scan: the embedding model did not load", t)
        null
    }

    private fun startAttempt() {
        val session = ScanSession(analyzers, detectors, embedder, options, thumbnails)
        this.session = session
        val now = SystemClock.elapsedRealtime()
        lastTickMs = now
        quietSinceMs = now
        lastActivity = -1L
        lastPercent = -1

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (isCurrent(session)) completed(session)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                if (isCurrent(session)) errored(session, exportException)
            }
        }
        val base = Transformer.Builder(context)
            .setLooper(thread.looper)
            .setUsePlatformDiagnostics(false)
            .addListener(listener)
            .build()
        val transformer = ExperimentalAnalyzerModeFactory.buildAnalyzer(context, base)
        this.transformer = transformer

        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(ScanEffect(session))))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.withVideoFrom(listOf(item)))
            .setHdrMode(hdrModes[attempt])
            .build()
        // Analyzer mode never opens this path; Transformer only insists on being given one.
        transformer.start(composition, File(context.cacheDir, UNUSED_OUTPUT_NAME).absolutePath)
    }

    private fun isCurrent(session: ScanSession): Boolean = !ended.get() && this.session === session

    private fun completed(session: ScanSession) {
        if (session.framesSeen == 0) {
            fail(AnalysisException(Kind.DECODER_FAILED, "The video was read to its end without decoding a frame"))
            return
        }
        val result = try {
            session.finish(info.durationUs)
        } catch (t: Throwable) {
            fail(ScanErrors.fromThrowable(FailureStage.MODEL, "Could not finish the scan", t))
            return
        }
        announceTeardown()
        sink.finished(result)
        end()
    }

    private fun errored(session: ScanSession, error: ExportException) {
        val failure = session.failure
        val mapped = ScanErrors.fromExport(error, failure)
        // Only a pass that died before its first frame, and not in the analyzer, can be an HDR mode problem.
        val retry = failure == null &&
            session.framesSeen == 0 &&
            attempt + 1 < hdrModes.size &&
            (mapped.kind == Kind.DECODER_FAILED || mapped.kind == Kind.UNKNOWN) &&
            !stopRequested.get()
        if (!retry) {
            earlierErrors.forEach(mapped::addSuppressed)
            fail(mapped)
            return
        }
        Log.w(TAG, "HDR mode ${hdrModes[attempt]} failed, trying the next one", error)
        earlierErrors.add(mapped)
        session.close()
        this.session = null
        transformer = null
        attempt++
        try {
            startAttempt()
        } catch (t: Throwable) {
            fail(ScanErrors.fromThrowable(null, "Could not restart the scan", t))
        }
    }

    private fun tick() {
        val session = session ?: return
        val transformer = transformer ?: return
        val percent = try {
            val state = transformer.getProgress(progressHolder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) progressHolder.progress else 0
        } catch (e: RuntimeException) {
            0
        }
        val ptsUs = session.progressPtsUs
        // Transformer reports how far the decoder has read, in whole percent. The newest analyzed frame time
        // is finer and never ahead of the analysis, so it is used whenever the duration is known.
        val current = when {
            info.durationUs <= 0L -> percent / 100f
            ptsUs < 0L -> 0f
            else -> (ptsUs.toDouble() / info.durationUs).toFloat()
        }
        fraction = max(fraction, current.coerceIn(0f, 1f))
        sink.progress(AnalysisEvent.Progress(fraction, session.framesSeen, session.facesSoFar))
        if (isStalled(session, percent)) {
            fail(AnalysisException(Kind.DECODER_FAILED, "The decoder stopped delivering frames"))
        }
    }

    /** Analyzer mode switches off Media3's own stall timer, so a decoder that hangs would spin forever. */
    private fun isStalled(session: ScanSession, percent: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        val activity = session.activity
        val moved = session.busy || activity != lastActivity || percent != lastPercent
        // A late tick means this process was frozen or asleep, which says nothing about the decoder.
        val wasPaused = now - lastTickMs > TICK_GAP_MS
        lastTickMs = now
        lastActivity = activity
        lastPercent = percent
        if (moved || wasPaused) {
            quietSinceMs = now
            return false
        }
        val limit = if (session.framesSeen == 0) FIRST_FRAME_STALL_MS else STALL_MS
        return now - quietSinceMs > limit
    }

    private fun fail(error: AnalysisException) {
        if (ended.get()) return
        announceTeardown()
        sink.failed(error)
        end()
    }

    // The outcome is reported before this runs, so a teardown that blocks cannot hold the result back.
    private fun end() {
        if (!ended.compareAndSet(false, true)) return
        announceTeardown()
        try {
            handler?.removeCallbacks(ticker)
            // First, so a later step that hangs cannot keep the phone awake.
            releasing("the wake lock") { releaseWakeLock() }
            session?.abandon()
            // Blocks until the decoder and the GL thread are released. A no-op once the export has ended.
            releasing("the Transformer") { transformer?.cancel() }
            transformer = null
            // After the Transformer, so the analyzer is closed once the GL thread has stopped feeding it.
            releasing("the analyzer") { session?.close() }
            session = null
            releasing("the embedding model") { embedder?.close() }
            embedder = null
        } finally {
            thread.quitSafely()
            teardowns.end()
        }
    }

    // One step failing must not skip the steps after it, or a decoder or model would stay open.
    private inline fun releasing(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not release $what cleanly", t)
        }
    }

    private fun announceTeardown() {
        if (teardownAnnounced.compareAndSet(false, true)) teardowns.begin()
    }

    private fun acquireWakeLock() {
        try {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.setReferenceCounted(false)
            // The timeout only bounds the damage if a teardown hangs; every normal exit releases the lock.
            lock.acquire(wakeLockTimeoutMs())
            wakeLock = lock
        } catch (e: SecurityException) {
            Log.w(TAG, "No WAKE_LOCK permission: the scan pauses when the phone sleeps", e)
        }
    }

    private fun wakeLockTimeoutMs(): Long {
        val durationMs = if (info.durationUs > 0L) info.durationUs / 1000L else UNKNOWN_DURATION_MS
        return (WAKE_LOCK_BASE_MS + durationMs * WAKE_LOCK_SLOWDOWN).coerceAtMost(WAKE_LOCK_MAX_MS)
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        if (lock.isHeld) lock.release()
    }

    private companion object {
        const val TAG = "OpenBlurScan"
        const val WAKE_LOCK_TAG = "OpenBlur:scan"
        const val UNUSED_OUTPUT_NAME = "scan-output-unused.mp4"

        const val TICK_MS = 250L
        const val TICK_GAP_MS = 2_000L
        const val STALL_MS = 60_000L

        // Longer than [STALL_MS]: a document provider may still be fetching the file before the first frame.
        const val FIRST_FRAME_STALL_MS = 120_000L
        const val TEARDOWN_WAIT_MS = 5_000L

        const val WAKE_LOCK_BASE_MS = 10L * 60L * 1000L
        const val WAKE_LOCK_SLOWDOWN = 30L
        const val WAKE_LOCK_MAX_MS = 12L * 60L * 60L * 1000L
        const val UNKNOWN_DURATION_MS = 10L * 60L * 1000L
    }
}
