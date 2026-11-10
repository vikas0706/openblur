package io.github.vikas0706.openblur.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.vikas0706.openblur.api.ExportPhase

/** Builds and posts the export notifications: the ongoing one with progress, and the result. */
internal class ExportNotifications(context: Context) {
    private val appContext = context.applicationContext
    private val manager: NotificationManager? = appContext.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.openblur_export_channel_name),
            // Low importance: visible in the shade, no sound, no heads-up.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = appContext.getString(R.string.openblur_export_channel_description)
            setShowBadge(false)
        }
        manager?.createNotificationChannel(channel)
    }

    fun progress(fileName: String, phase: ExportPhase, overall: Float): Notification {
        val percent = ExportProgress.percent(overall)
        val builder = base(appContext.getString(R.string.openblur_export_running_title), fileName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Without this Android 12 and later hold the notification back for ten seconds.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, percent, /* indeterminate = */ false)
            .addAction(0, appContext.getString(R.string.openblur_export_action_cancel), cancelIntent())
        if (phase != ExportPhase.PREPARING) {
            builder.setSubText(appContext.getString(R.string.openblur_export_running_percent, percent))
        }
        return builder.build()
    }

    fun showProgress(fileName: String, phase: ExportPhase, overall: Float) {
        notify(PROGRESS_ID, progress(fileName, phase, overall))
    }

    fun showSaved(fileName: String) {
        val notification = base(
            appContext.getString(R.string.openblur_export_saved_title),
            appContext.getString(R.string.openblur_export_saved_text, fileName),
        ).setAutoCancel(true).build()
        notify(RESULT_ID, notification)
    }

    fun showFailed(fileName: String) {
        val notification = base(
            appContext.getString(R.string.openblur_export_failed_title),
            appContext.getString(R.string.openblur_export_failed_text, fileName),
        ).setAutoCancel(true).build()
        notify(RESULT_ID, notification)
    }

    /** Removes a result notification once the app shows the same result itself. */
    fun clearResult() {
        manager?.cancel(RESULT_ID)
    }

    private fun base(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.openblur_export_ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setContentIntent(openAppIntent())

    private fun notify(id: Int, notification: Notification) {
        try {
            // Silently dropped when the user has not allowed notifications.
            manager?.notify(id, notification)
        } catch (e: RuntimeException) {
            // A notification that cannot be shown must not fail the export.
        }
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName) ?: return null
        return PendingIntent.getActivity(
            appContext,
            REQUEST_OPEN,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        appContext,
        REQUEST_CANCEL,
        Intent(appContext, ExportService::class.java).setAction(ExportService.ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "openblur_export"
        const val PROGRESS_ID = 0x0B10
        const val RESULT_ID = 0x0B11
        private const val REQUEST_OPEN = 1
        private const val REQUEST_CANCEL = 2
    }
}
