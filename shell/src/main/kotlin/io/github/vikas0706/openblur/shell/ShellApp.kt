package io.github.vikas0706.openblur.shell

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vikas0706.openblur.designsystem.MosaicProgress
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme

/** The whole app below the theme: one screen at a time, chosen by [ShellViewModel.screen]. */
@Composable
internal fun ShellApp(
    host: ShellHost,
    viewModel: ShellViewModel,
    onVideoPicked: (Uri) -> Unit,
    onLeaveApp: () -> Unit,
) {
    val context = LocalContext.current
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val home by viewModel.home.collectAsStateWithLifecycle()
    val busyNotice by viewModel.busyNotice.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onVideoPicked(uri)
    }
    val launchPicker = {
        try {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        } catch (_: ActivityNotFoundException) {
            viewModel.showHomeNotice(HomeNotice.NO_PICKER)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.pickRequests.collect { launchPicker() }
    }

    val exportName = (screen as? Screen.Editor)?.project?.displayName
        ?.let { stringResource(R.string.export_file_name, Formats.baseName(it)) }
    val startExport = { if (exportName != null) viewModel.startExport(exportName) }
    // The export goes ahead whatever the answer: without the permission only the progress notification is missing.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startExport()
    }
    val askNotificationsThenStart = {
        if (ExportPermissions.shouldAskForNotifications(context)) {
            ExportPermissions.markNotificationsAsked(context)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startExport()
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askNotificationsThenStart() else viewModel.onStoragePermissionDenied()
    }
    val saveExport = {
        if (ExportPermissions.needsStoragePermission(context)) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            askNotificationsThenStart()
        }
    }

    Box(Modifier.fillMaxSize().background(OpenBlurTheme.colors.surface)) {
        when (val current = screen) {
            Screen.Home -> HomeScreen(
                home = home,
                onChooseVideo = launchPicker,
                onAbout = viewModel::showAbout,
                onOpenProject = viewModel::openProject,
                onDeleteProject = viewModel::deleteProject,
                onRetryList = viewModel::refreshProjects,
                onDismissNotice = viewModel::dismissHomeNotice,
            )
            Screen.About -> AboutScreen(onBack = viewModel::showHome)
            is Screen.Scanning -> ScanningScreen(
                scan = current.scan,
                busyNotice = busyNotice,
                onDismissBusyNotice = viewModel::dismissBusyNotice,
                onCancel = viewModel::cancelScan,
                onRetry = viewModel::retryScan,
            )
            Screen.OpeningProject -> OpeningScreen(onBack = viewModel::showHome)
            is Screen.Editor -> {
                // Registered before the editor's own handlers, so those win while the editor has something to close.
                BackHandler(enabled = current.sheet == null, onBack = viewModel::showHome)
                key(current.project) {
                    host.Editor(
                        project = current.project,
                        onBack = viewModel::showHome,
                        onExport = viewModel::openExportSheet,
                        onRescanThorough = viewModel::rescanThorough,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                current.sheet?.let { sheet ->
                    ExportSheet(
                        project = current.project,
                        sheet = sheet,
                        onQuality = viewModel::setExportQuality,
                        onRemoveLocation = viewModel::setRemoveLocation,
                        onRetryPlan = viewModel::retryPlan,
                        onSave = saveExport,
                        onDismiss = viewModel::closeExportSheet,
                    )
                }
            }
            is Screen.Exporting -> ExportingScreen(
                running = current.running,
                remainingMs = current.remainingMs,
                busyNotice = busyNotice,
                onDismissBusyNotice = viewModel::dismissBusyNotice,
                onCancel = viewModel::cancelExport,
                onLeaveApp = onLeaveApp,
            )
            is Screen.Result -> ResultScreen(
                outcome = current.outcome,
                canReturnToEditor = current.canReturnToEditor,
                onBackToEditor = viewModel::leaveResult,
                onBlurAnother = viewModel::blurAnotherVideo,
            )
        }
    }
}

@Composable
private fun OpeningScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    ScreenFrame(title = stringResource(R.string.opening_title), onBack = onBack) {
        MosaicProgress(progress = null, contentDescription = stringResource(R.string.opening_progress))
        BodyText(stringResource(R.string.opening_progress), muted = true)
    }
}

internal object ExportPermissions {
    private const val PREFS = "openblur_shell"
    private const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"

    /** Saving to the shared Movies folder needs a permission only before scoped storage (Android 9 and older). */
    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            !isGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    /** Asked once, at the first export, so the progress notification can show. A refusal is not asked about again. */
    fun shouldAskForNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isGranted(context, Manifest.permission.POST_NOTIFICATIONS) &&
            !prefs(context).getBoolean(KEY_NOTIFICATIONS_ASKED, false)

    fun markNotificationsAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS_ASKED, true).apply()
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
