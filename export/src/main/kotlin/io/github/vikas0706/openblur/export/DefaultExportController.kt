package io.github.vikas0706.openblur.export

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.vikas0706.openblur.api.ExportController
import io.github.vikas0706.openblur.api.ExportFailure
import io.github.vikas0706.openblur.api.ExportPhase
import io.github.vikas0706.openblur.api.ExportRequest
import io.github.vikas0706.openblur.api.ExportState
import io.github.vikas0706.openblur.api.MaskEffectFactory
import io.github.vikas0706.openblur.api.SourceInspector
import io.github.vikas0706.openblur.core.EncodePlan
import io.github.vikas0706.openblur.core.EncoderPolicy
import io.github.vikas0706.openblur.core.ExportQuality
import io.github.vikas0706.openblur.core.SourceVideoInfo
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The UI's handle on exporting; the work happens in [ExportService]. Create it once, in the Application:
 * creation removes what a crashed export left behind and starts tracking whether the app is on screen.
 */
class DefaultExportController(
    context: Context,
    private val policy: EncoderPolicy,
    private val maskEffects: MaskEffectFactory,
    private val inspector: SourceInspector,
) : ExportController {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notifications = ExportNotifications(appContext)

    init {
        AppVisibility.register(appContext)
        thread(name = "OpenBlurExportCleanup", isDaemon = true) { ExportFiles(appContext).removeLeftovers() }
    }

    override val state: StateFlow<ExportState> get() = ExportSession.state

    override suspend fun plan(info: SourceVideoInfo, quality: ExportQuality): List<EncodePlan> =
        // Listing codecs is a call into the media service, so it stays off the main thread.
        withContext(Dispatchers.Default) { policy.plan(info, EncoderSurvey.candidates(info), quality) }

    override fun start(request: ExportRequest) {
        val ticket = ExportTicket(request, policy, maskEffects, inspector, SystemClock.elapsedRealtime())
        val initial = ExportState.Running(ExportPhase.PREPARING, progress = 0f, plan = null, elapsedMs = 0L)
        if (!ExportSession.begin(ticket, initial)) return
        notifications.clearResult()
        try {
            ContextCompat.startForegroundService(appContext, ExportService.startIntent(appContext))
        } catch (e: RuntimeException) {
            // Android 12 and later refuse foreground service starts from the background.
            val detail = "The export service could not be started: ${e.javaClass.simpleName}: ${e.message}"
            ExportSession.finish(ticket, ExportState.Failed(ExportFailure.UNKNOWN, detail))
            return
        }
        mainHandler.postDelayed({
            val detail = "The export service did not start within ${SERVICE_START_TIMEOUT_MS / 1000} seconds"
            ExportSession.finishIfUnclaimed(ticket, ExportState.Failed(ExportFailure.UNKNOWN, detail))
        }, SERVICE_START_TIMEOUT_MS)
    }

    override fun cancel() {
        ExportSession.cancel()
    }

    override fun reset() {
        ExportSession.reset()
        notifications.clearResult()
    }

    private companion object {
        const val SERVICE_START_TIMEOUT_MS = 10_000L
    }
}
