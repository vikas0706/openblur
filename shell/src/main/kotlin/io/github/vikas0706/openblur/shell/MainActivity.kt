package io.github.vikas0706.openblur.shell

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme

/** The app's only activity. Every screen is a state of [ShellViewModel]; the Application supplies the services. */
class MainActivity : ComponentActivity() {

    private val host: ShellHost
        get() = application as? ShellHost
            ?: error("The Application class must implement ShellHost")

    private val viewModel: ShellViewModel by viewModels {
        ShellViewModel.factory(host.services, getString(R.string.video_fallback_name))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark only, so both bars always get light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // After a restore the view model already holds what the launch intent started.
        if (savedInstanceState == null) handleIntent(intent)
        val shellHost = host
        setContent {
            OpenBlurTheme {
                ShellApp(
                    host = shellHost,
                    viewModel = viewModel,
                    onVideoPicked = ::openVideo,
                    onLeaveApp = { moveTaskToBack(true) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Reopening the app from Recents replays the old intent; the video in it was already handled.
        if (intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uri = when (intent?.action) {
            // Some apps put the video only in the ClipData, which also carries the read grant.
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        openVideo(uri)
    }

    /** Starts scanning [uri], or tells the user why not when a scan or export is already running. */
    internal fun openVideo(uri: Uri) {
        if (!viewModel.onVideoChosen(uri)) return
        try {
            // Lets a saved scan reopen the video after a restart. Not every provider offers it.
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The grant for this session still lets the scan and an export run now.
        } catch (_: IllegalArgumentException) {
            // Thrown for Uris that are not content Uris.
        }
    }
}
