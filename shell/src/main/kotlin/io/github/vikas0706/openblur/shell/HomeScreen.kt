package io.github.vikas0706.openblur.shell

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vikas0706.openblur.api.ProjectSummary
import io.github.vikas0706.openblur.designsystem.ConfirmDialog
import io.github.vikas0706.openblur.designsystem.IconAction
import io.github.vikas0706.openblur.designsystem.MosaicProgress
import io.github.vikas0706.openblur.designsystem.Notice
import io.github.vikas0706.openblur.designsystem.NoticeTone
import io.github.vikas0706.openblur.designsystem.OpenBlurDimens
import io.github.vikas0706.openblur.designsystem.OpenBlurIcons
import io.github.vikas0706.openblur.designsystem.OpenBlurShapes
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme
import io.github.vikas0706.openblur.designsystem.PrimaryButton
import io.github.vikas0706.openblur.designsystem.SectionHeading
import io.github.vikas0706.openblur.designsystem.TextAction
import io.github.vikas0706.openblur.designsystem.TopBar
import io.github.vikas0706.openblur.designsystem.formatTimeUs
import io.github.vikas0706.openblur.designsystem.tabularFigures
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HomeScreen(
    home: HomeUi,
    onChooseVideo: () -> Unit,
    onAbout: () -> Unit,
    onOpenProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onRetryList: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissInterrupted: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<ProjectSummary?>(null) }

    Column(modifier.fillMaxSize().background(OpenBlurTheme.colors.surface)) {
        TopBar(
            title = stringResource(R.string.app_name),
            actions = { TextAction(text = stringResource(R.string.home_about), onClick = onAbout) },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                contentPadding = PaddingValues(OpenBlurDimens.ScreenPadding),
            ) {
                item(key = "intro") {
                    Intro(home, onChooseVideo, onDismissNotice, onDismissInterrupted)
                }
                item(key = "heading") {
                    Spacer(Modifier.height(OpenBlurDimens.SectionGap))
                    SectionHeading(stringResource(R.string.home_projects_heading))
                }
                when (val list = home.projects) {
                    ProjectListUi.Loading -> item(key = "loading") { ProjectsLoading() }
                    ProjectListUi.Failed -> item(key = "failed") {
                        Notice(
                            text = stringResource(R.string.home_projects_error),
                            tone = NoticeTone.Error,
                            actionLabel = stringResource(R.string.action_try_again),
                            onAction = onRetryList,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    is ProjectListUi.Loaded -> if (list.projects.isEmpty()) {
                        item(key = "empty") { ProjectsEmpty() }
                    } else {
                        items(list.projects, key = { "project-" + it.id }) { project ->
                            ProjectRow(
                                project = project,
                                onOpen = { onOpenProject(project.id) },
                                onDelete = { pendingDelete = project },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { project ->
        ConfirmDialog(
            title = stringResource(R.string.delete_title),
            text = stringResource(R.string.delete_text, project.displayName),
            confirmLabel = stringResource(R.string.delete_confirm),
            dismissLabel = stringResource(R.string.delete_dismiss),
            onConfirm = {
                pendingDelete = null
                onDeleteProject(project.id)
            },
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }
}

@Composable
private fun Intro(
    home: HomeUi,
    onChooseVideo: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissInterrupted: () -> Unit,
) {
    val notice = home.notice
    Column(verticalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap)) {
        if (home.exportInterrupted) {
            Notice(
                text = stringResource(R.string.home_notice_export_interrupted),
                tone = NoticeTone.Warning,
                actionLabel = stringResource(R.string.action_dismiss),
                onAction = onDismissInterrupted,
            )
        }
        if (notice != null) {
            Notice(
                text = stringResource(
                    when (notice) {
                        HomeNotice.PROJECT_UNAVAILABLE -> R.string.home_notice_project_missing
                        HomeNotice.DELETE_FAILED -> R.string.home_notice_delete_failed
                        HomeNotice.NO_PICKER -> R.string.home_notice_no_picker
                    },
                ),
                tone = NoticeTone.Error,
                actionLabel = stringResource(R.string.action_dismiss),
                onAction = onDismissNotice,
            )
        }
        Text(
            text = stringResource(R.string.home_headline),
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            color = OpenBlurTheme.colors.text,
            style = MaterialTheme.typography.headlineMedium,
        )
        BodyText(stringResource(R.string.home_body))
        PrimaryButton(
            text = stringResource(R.string.home_choose_video),
            onClick = onChooseVideo,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            icon = OpenBlurIcons.Video,
        )
        BodyText(stringResource(R.string.home_no_network), muted = true)
    }
}

@Composable
private fun ProjectsLoading() {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap),
    ) {
        MosaicProgress(progress = null, contentDescription = stringResource(R.string.home_projects_loading))
        BodyText(stringResource(R.string.home_projects_loading), muted = true)
    }
}

@Composable
private fun ProjectsEmpty() {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap),
    ) {
        MosaicMark(Modifier.padding(top = 2.dp))
        BodyText(stringResource(R.string.home_projects_empty), muted = true)
    }
}

@Composable
private fun ProjectRow(project: ProjectSummary, onOpen: () -> Unit, onDelete: () -> Unit) {
    val resources = LocalResources.current
    val meta = stringResource(
        R.string.project_meta,
        formatTimeUs(project.durationUs),
        Labels.people(resources, project.peopleCount),
    )
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    onClickLabel = stringResource(R.string.project_open, project.displayName),
                    role = Role.Button,
                    onClick = onOpen,
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(project.cover)
            Spacer(Modifier.width(OpenBlurDimens.ItemGap))
            Column(Modifier.weight(1f)) {
                Text(
                    text = project.displayName,
                    color = OpenBlurTheme.colors.text,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    color = OpenBlurTheme.colors.textMuted,
                    style = MaterialTheme.typography.bodyMedium.tabularFigures(),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        IconAction(
            icon = OpenBlurIcons.Delete,
            contentDescription = stringResource(R.string.project_delete, project.displayName),
            onClick = onDelete,
        )
    }
}

/** The first person's face picture, or a face outline when the scan found nobody. */
@Composable
private fun Cover(file: File?) {
    val image by produceState<ImageBitmap?>(initialValue = null, file) {
        value = if (file == null) null else withContext(Dispatchers.IO) { decodeCover(file) }
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(OpenBlurShapes.Control)
            .background(OpenBlurTheme.colors.surfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = OpenBlurIcons.Face,
                contentDescription = null,
                tint = OpenBlurTheme.colors.textMuted,
            )
        }
    }
}

private const val COVER_DECODE_PX = 256

private fun decodeCover(file: File): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= COVER_DECODE_PX && bounds.outHeight / (sample * 2) >= COVER_DECODE_PX) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}
