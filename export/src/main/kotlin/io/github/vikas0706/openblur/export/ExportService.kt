package io.github.vikas0706.openblur.export

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.vikas0706.openblur.api.ExportFailure
import io.github.vikas0706.openblur.api.ExportPhase
import io.github.vikas0706.openblur.api.ExportState

/**
 * Foreground service that runs the export so it carries on after the activity is gone. It takes its
 * work from [ExportSession]; started with nothing waiting there, it stops at once.
 */
class ExportService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var exportThread: HandlerThread
    private lateinit var exportHandler: Handler
    private lateinit var notifications: ExportNotifications
    private var wakeLock: PowerManager.WakeLock? = null

    private var activeJob: ExportJob? = null
    private var lastStartId = 0

    private var lastNotifiedAtMs = 0L
    private var lastNotifiedPercent = -1
    private var lastNotifiedPhase: ExportPhase? = null
    private var lastWakeRenewalMs = 0L

    private val host = object : ExportJob.Host {
        override fun onProgress(job: ExportJob, phase: ExportPhase, overall: Float) {
            mainHandler.post { showProgress(job, phase, overall) }
        }

        override fun onFinished(job: ExportJob, terminal: ExportState) {
            mainHandler.post { finish(job, terminal) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = ExportNotifications(this)
        notifications.ensureChannel()
        // Transformer must be driven from one looper; a dedicated thread keeps it off the main thread.
        exportThread = HandlerThread("OpenBlurExport").also { it.start() }
        exportHandler = Handler(exportThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            ExportSession.cancel()
            if (activeJob == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val ticket = ExportSession.claim()
        if (ticket == null) {
            if (activeJob == null) {
                // startForegroundService obliges the service to enter the foreground even when it has nothing to do.
                promote(notifications.progress("", ExportPhase.PREPARING, 0f))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }

        val job = ExportJob(applicationContext, ticket, exportHandler, host)
        val refusal = promote(notifications.progress(job.fileName, ExportPhase.PREPARING, 0f))
        if (refusal != null) {
            ExportSession.finish(ticket, ExportState.Failed(failureFor(refusal), "The export service could not enter the foreground: $refusal"))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        lastNotifiedAtMs = SystemClock.elapsedRealtime()
        lastNotifiedPercent = 0
        lastNotifiedPhase = ExportPhase.PREPARING
        holdWakeLock()
        activeJob = job
        job.start()
        return START_NOT_STICKY
    }

    /** Called by Android 15 and later when the daily allowance of this kind of foreground service is used up. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val job = activeJob
        if (job == null) {
            stopSelf()
            return
        }
        job.ticket.requestStop(StopReason.TIME_LIMIT)
        // The system crashes the app unless the service leaves the foreground within a few seconds.
        stopForeground(STOP_FOREGROUND_REMOVE)
        mainHandler.postDelayed({
            if (activeJob === job) {
                // The export thread did not answer in time; report the limit now and let its cleanup finish behind.
                finish(job, ExportState.Failed(ExportFailure.TIME_LIMIT, "Android's time limit for background media processing was reached"))
            }
        }, TIMEOUT_GRACE_MS)
    }

    override fun onDestroy() {
        val job = activeJob
        if (job != null) {
            // This class only stops itself after a job ends, so someone else stopped the service: end the job and say so.
            activeJob = null
            job.ticket.requestStop(StopReason.USER)
            ExportSession.finish(job.ticket, ExportState.Failed(ExportFailure.UNKNOWN, "The export service was shut down by the system"))
        }
        mainHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        // quitSafely lets a cancellation that is already queued delete its temp file first.
        exportThread.quitSafely()
        super.onDestroy()
    }

    private fun showProgress(job: ExportJob, phase: ExportPhase, overall: Float) {
        // After onTimeout the service is no longer in the foreground, so a posted progress notification would outlive it.
        if (job !== activeJob || job.ticket.stopReason != null) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeRenewalMs >= WAKE_LOCK_RENEWAL_MS) holdWakeLock()
        val percent = ExportProgress.percent(overall)
        if (percent == lastNotifiedPercent && phase == lastNotifiedPhase) return
        if (now - lastNotifiedAtMs < NOTIFICATION_INTERVAL_MS) return
        lastNotifiedAtMs = now
        lastNotifiedPercent = percent
        lastNotifiedPhase = phase
        notifications.showProgress(job.fileName, phase, overall)
    }

    private fun finish(job: ExportJob, terminal: ExportState) {
        if (job !== activeJob) return
        activeJob = null
        ExportSession.finish(job.ticket, terminal)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // stopForeground removes nothing once onTimeout has already left the foreground.
        notifications.cancelProgress()
        if (!AppVisibility.isVisible()) {
            when (terminal) {
                is ExportState.Succeeded -> notifications.showSaved(terminal.report.displayName)
                is ExportState.Failed -> notifications.showFailed(job.fileName)
                else -> Unit
            }
        }
        // Tied to the start id so a start that arrived in the meantime keeps the service alive.
        stopSelfResult(lastStartId)
    }

    /** Returns null on success, else why the system refused. */
    private fun promote(notification: Notification): RuntimeException? = try {
        // ServiceCompat.startForeground masks out the mediaProcessing type, so the platform call is used.
        when {
            Build.VERSION.SDK_INT >= 35 ->
                startForeground(ExportNotifications.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(ExportNotifications.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(ExportNotifications.PROGRESS_ID, notification)
        }
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "startForeground was refused", e)
        e
    }

    private fun failureFor(refusal: RuntimeException): ExportFailure {
        // Once the daily allowance is spent, Android refuses the start with a message naming the time limit.
        val timeLimit = Build.VERSION.SDK_INT >= 31 &&
            Api31.isStartNotAllowed(refusal) &&
            refusal.message?.contains("time limit", ignoreCase = true) == true
        return if (timeLimit) ExportFailure.TIME_LIMIT else ExportFailure.UNKNOWN
    }

    private fun holdWakeLock() {
        lastWakeRenewalMs = SystemClock.elapsedRealtime()
        try {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
            // Renewed while progress keeps arriving, so a hung export cannot keep the CPU awake for long.
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No wake lock; the export may pause while the screen is off", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Releasing the wake lock failed", e)
        }
    }

    /** Holds the reference to a class older devices lack, so this service's own code verifies on them. */
    @RequiresApi(31)
    private object Api31 {
        fun isStartNotAllowed(error: RuntimeException): Boolean = error is ForegroundServiceStartNotAllowedException
    }

    internal companion object {
        const val ACTION_START = "io.github.vikas0706.openblur.export.action.START"
        const val ACTION_CANCEL = "io.github.vikas0706.openblur.export.action.CANCEL"

        private const val TAG = "OpenBlurExport"
        private const val WAKE_LOCK_TAG = "OpenBlur:export"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_RENEWAL_MS = 60 * 1000L
        private const val NOTIFICATION_INTERVAL_MS = 1000L
        private const val TIMEOUT_GRACE_MS = 3000L

        fun startIntent(context: Context): Intent = Intent(context, ExportService::class.java).setAction(ACTION_START)
    }
}
