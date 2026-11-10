package io.github.vikas0706.openblur.shell

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.vikas0706.openblur.designsystem.InfoRow
import io.github.vikas0706.openblur.designsystem.Notice
import io.github.vikas0706.openblur.designsystem.NoticeTone
import io.github.vikas0706.openblur.designsystem.OpenBlurIcons
import io.github.vikas0706.openblur.designsystem.OpenBlurShapes
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme
import io.github.vikas0706.openblur.designsystem.SecondaryButton
import io.github.vikas0706.openblur.designsystem.SectionHeading
import io.github.vikas0706.openblur.designsystem.tabularFigures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember(context) { versionName(context) }
    val sourceUrl = stringResource(R.string.about_source_url)
    var appInfoFailed by remember { mutableStateOf(false) }
    var browserFailed by remember { mutableStateOf(false) }
    var showApache by rememberSaveable { mutableStateOf(false) }
    var showBsd by rememberSaveable { mutableStateOf(false) }
    var showMit by rememberSaveable { mutableStateOf(false) }
    var showOnnxNotices by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    ScreenFrame(title = stringResource(R.string.about_title), onBack = onBack, modifier = modifier) {
        if (version != null) {
            Text(
                text = stringResource(R.string.about_version, version),
                color = OpenBlurTheme.colors.textMuted,
                style = MaterialTheme.typography.bodyMedium.tabularFigures(),
            )
        }

        Column {
            SectionHeading(stringResource(R.string.about_privacy_heading))
            BodyText(stringResource(R.string.about_privacy_text))
            BodyText(stringResource(R.string.about_storage_text), Modifier.padding(top = 8.dp))
        }
        SecondaryButton(
            text = stringResource(R.string.about_app_info),
            onClick = { appInfoFailed = !openAppInfo(context) },
            icon = OpenBlurIcons.OpenExternal,
        )
        if (appInfoFailed) Notice(text = stringResource(R.string.about_app_info_failed), tone = NoticeTone.Error)

        Column {
            SectionHeading(stringResource(R.string.about_licence_heading))
            BodyText(stringResource(R.string.about_licence_text))
        }

        Column {
            SectionHeading(stringResource(R.string.about_source_heading))
            // Selectable, so the address can be copied on a phone without a browser.
            SelectionContainer { BodyText(sourceUrl) }
        }
        SecondaryButton(
            text = stringResource(R.string.about_source_open),
            onClick = { browserFailed = !openInBrowser(context, sourceUrl) },
            icon = OpenBlurIcons.OpenExternal,
        )
        if (browserFailed) Notice(text = stringResource(R.string.about_source_failed), tone = NoticeTone.Error)

        Column {
            SectionHeading(stringResource(R.string.about_notices_heading))
            BodyText(stringResource(R.string.about_notices_text), Modifier.padding(bottom = 8.dp), muted = true)
            val apache = stringResource(R.string.licence_apache)
            InfoRow(stringResource(R.string.about_component_androidx), apache, Modifier.padding(vertical = 4.dp))
            InfoRow(stringResource(R.string.about_component_media3), apache, Modifier.padding(vertical = 4.dp))
            InfoRow(stringResource(R.string.about_component_kotlin), apache, Modifier.padding(vertical = 4.dp))
            InfoRow(stringResource(R.string.about_component_guava), apache, Modifier.padding(vertical = 4.dp))
            InfoRow(
                stringResource(R.string.about_component_onnx),
                stringResource(R.string.licence_mit),
                Modifier.padding(vertical = 4.dp),
            )
            InfoRow(
                stringResource(R.string.about_component_yunet),
                stringResource(R.string.licence_bsd3),
                Modifier.padding(vertical = 4.dp),
            )
            InfoRow(stringResource(R.string.about_component_sface), apache, Modifier.padding(vertical = 4.dp))
        }
        SecondaryButton(
            text = stringResource(if (showApache) R.string.about_hide_apache else R.string.about_show_apache),
            onClick = { showApache = !showApache },
        )
        if (showApache) LicenceText(R.raw.licence_apache2)
        SecondaryButton(
            text = stringResource(if (showBsd) R.string.about_hide_bsd else R.string.about_show_bsd),
            onClick = { showBsd = !showBsd },
        )
        if (showBsd) LicenceText(R.raw.licence_bsd3_yunet)
        SecondaryButton(
            text = stringResource(if (showMit) R.string.about_hide_onnx else R.string.about_show_onnx),
            onClick = { showMit = !showMit },
        )
        if (showMit) LicenceText(R.raw.licence_mit_onnxruntime)
        SecondaryButton(
            text = stringResource(if (showOnnxNotices) R.string.about_hide_onnx_notices else R.string.about_show_onnx_notices),
            onClick = { showOnnxNotices = !showOnnxNotices },
        )
        if (showOnnxNotices) LongLicenceText(R.raw.notices_onnxruntime)
    }
}

/** For texts too long for one Text: about 320 kB laid out at once stalls the main thread. */
@Composable
private fun LongLicenceText(rawId: Int) {
    val context = LocalContext.current
    val paragraphs by produceState(initialValue = emptyList<String>(), rawId) {
        value = withContext(Dispatchers.IO) {
            try {
                context.resources.openRawResource(rawId).bufferedReader().use { it.readText() }
                    .split(Regex("\n\\s*\n"))
                    .filter { it.isNotBlank() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    if (paragraphs.isNotEmpty()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(480.dp)
                .background(OpenBlurTheme.colors.surfaceHigh, OpenBlurShapes.Control)
                .testTag(TAG_ONNX_NOTICES),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(paragraphs) { paragraph ->
                Text(
                    text = paragraph,
                    color = OpenBlurTheme.colors.text,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal const val TAG_ONNX_NOTICES = "onnx-notices"

@Composable
private fun LicenceText(rawId: Int) {
    val context = LocalContext.current
    val text by produceState(initialValue = "", rawId) {
        value = withContext(Dispatchers.IO) {
            try {
                context.resources.openRawResource(rawId).bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                ""
            }
        }
    }
    if (text.isNotEmpty()) {
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OpenBlurTheme.colors.surfaceHigh, OpenBlurShapes.Control)
                    .padding(12.dp),
                color = OpenBlurTheme.colors.text,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Read from the installed package, because this module is a library and has no version of its own. */
private fun versionName(context: Context): String? = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
} catch (_: Exception) {
    null
}

private fun openAppInfo(context: Context): Boolean = try {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

private fun openInBrowser(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
