package io.github.vikas0706.openblur.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vikas0706.openblur.api.AppServices
import io.github.vikas0706.openblur.api.Project
import io.github.vikas0706.openblur.api.ProjectStore
import io.github.vikas0706.openblur.core.BlurChoice
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.MaskTimeline
import io.github.vikas0706.openblur.designsystem.BottomPanel
import io.github.vikas0706.openblur.designsystem.ConfirmDialog
import io.github.vikas0706.openblur.designsystem.IconAction
import io.github.vikas0706.openblur.designsystem.Notice
import io.github.vikas0706.openblur.designsystem.NoticeTone
import io.github.vikas0706.openblur.designsystem.OpenBlurDimens
import io.github.vikas0706.openblur.designsystem.OpenBlurIcons
import io.github.vikas0706.openblur.designsystem.OpenBlurShapes
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme
import io.github.vikas0706.openblur.designsystem.PrimaryButton
import io.github.vikas0706.openblur.designsystem.TopBar
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The editor for one scanned video. [onExport] receives the user's current choices when they ask to save;
 * [onRescanThorough] asks the host to scan the same video again with the thorough settings.
 */
@Composable
fun EditorScreen(
    project: Project,
    services: AppServices,
    onBack: () -> Unit,
    onExport: (edit: EditState) -> Unit,
    onRescanThorough: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = rememberEditorViewModel(project)
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val colors = OpenBlurTheme.colors

    val videoWidth = project.info.displayWidth.takeIf { it > 0 } ?: project.result.frameWidth
    val videoHeight = project.info.displayHeight.takeIf { it > 0 } ?: project.result.frameHeight
    val preview = remember(vm, project.sourceUri) {
        PreviewPlayer(
            context = context,
            uri = project.sourceUri,
            displayWidth = videoWidth,
            displayHeight = videoHeight,
            mask = services.maskEffects.create(),
            onFrameShown = vm::onFrameShown,
            onPlayingChanged = vm::onPlayingChanged,
        )
    }
    val saver = remember(project.id, services.projects) { EditSaver(services.projects, project.id, project.edit) }
    val thumbnails = remember(project.id, services.projects) { ThumbnailCache(services.projects.thumbnails(project.id)) }
    var timeline by remember(vm) { mutableStateOf<MaskTimeline?>(null) }
    var previewStale by remember(vm) { mutableStateOf(false) }
    var confirmRescan by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(vm, preview) {
        vm.playback = preview
        // After a rotation the new player starts at zero; queue the seek, which waits for the first STATE_READY.
        vm.positionUs.value.let { if (it > 0L) preview.seekTo(it) }
        onDispose {
            vm.playback = null
            vm.onPlayingChanged(false)
        }
    }

    LaunchedEffect(preview, state.muted) { preview.setMuted(state.muted) }

    LaunchedEffect(vm, preview) {
        vm.state.map { it.edit }.distinctUntilChanged().conflate().collect { edit ->
            val built = try {
                withContext(Dispatchers.Default) { services.timelines.build(vm.result, edit) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            previewStale = built == null
            if (built != null) {
                preview.setTimeline(built)
                timeline = built
                preview.redrawIfPaused(vm.positionUs.value)
            }
            // While a drag is under way, leave the player time to show one frame before the next rebuild.
            if (vm.isGestureActive) delay(DRAG_REBUILD_MS)
        }
    }

    LaunchedEffect(vm, saver) {
        vm.state.map { it.edit }.distinctUntilChanged().collectLatest { edit ->
            delay(SAVE_DEBOUNCE_MS)
            saver.save(edit)
        }
    }
    DisposableEffect(vm, saver) {
        onDispose { saver.saveDetached(vm.state.value.edit) }
    }

    LaunchedEffect(vm, preview, preview.isPlaying) {
        if (preview.isPlaying) {
            while (true) {
                withFrameNanos { }
                vm.onPlayerPosition(preview.positionUs)
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, preview, vm, saver) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    preview.pause()
                    saver.saveDetached(vm.state.value.edit)
                }
                // The video surface is recreated on return and starts out empty.
                Lifecycle.Event.ON_START -> preview.redrawIfPaused(vm.positionUs.value)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = backClosesSomething(state)) { vm.handleBack() }

    val leave = {
        saver.saveDetached(vm.state.value.edit)
        onBack()
    }
    val export = {
        preview.pause()
        val edit = vm.state.value.edit
        saver.saveDetached(edit)
        onExport(edit)
    }
    val retry = { preview.retry(vm.positionUs.value) }
    val askRescan = { confirmRescan = true }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Z) {
                    if (event.isShiftPressed) vm.redo() else vm.undo()
                    true
                } else {
                    false
                }
            },
    ) {
        val wide = maxWidth >= 560.dp && (maxWidth > maxHeight || maxWidth >= 840.dp)
        // Too short for a preview above a panel: the whole screen scrolls instead of squeezing either.
        val cramped = !wide && maxHeight < 600.dp
        val fullHeight = maxHeight
        val fullWidth = maxWidth

        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(title = project.displayName, onBack = leave) {
                IconAction(
                    icon = OpenBlurIcons.Undo,
                    contentDescription = stringResource(R.string.editor_undo),
                    onClick = vm::undo,
                    enabled = state.canUndo,
                )
                IconAction(
                    icon = OpenBlurIcons.Redo,
                    contentDescription = stringResource(R.string.editor_redo),
                    onClick = vm::redo,
                    enabled = state.canRedo,
                )
                OverflowMenu(onRescan = askRescan)
                if (wide) {
                    PrimaryButton(
                        text = stringResource(R.string.editor_save_video),
                        onClick = export,
                        modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                    )
                }
            }

            val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
            when {
                wide -> Row(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Bottom)),
                    ) {
                        PreviewPane(
                            vm, state, preview, timeline, previewStale, videoWidth, videoHeight, retry,
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                        Transport(vm, state, preview)
                    }
                    SidePanel(width = (fullWidth * 0.42f).coerceIn(280.dp, 420.dp)) {
                        SaveFailedNotice(saver)
                        EditorTabs(state, vm::setTab)
                        ScrollingTab(
                            vm, state, thumbnails, askRescan,
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    }
                }

                cramped -> Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    PreviewPane(
                        vm, state, preview, timeline, previewStale, videoWidth, videoHeight, retry,
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(sideInsets)
                            .height(CRAMPED_PREVIEW_HEIGHT),
                    )
                    Transport(vm, state, preview, Modifier.windowInsetsPadding(sideInsets))
                    BottomPanel {
                        SaveFailedNotice(saver)
                        EditorTabs(state, vm::setTab)
                        TabContent(vm, state, thumbnails, askRescan)
                        PrimaryButton(
                            text = stringResource(R.string.editor_save_video),
                            onClick = export,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                else -> {
                    PreviewPane(
                        vm, state, preview, timeline, previewStale, videoWidth, videoHeight, retry,
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .windowInsetsPadding(sideInsets),
                    )
                    Transport(vm, state, preview, Modifier.windowInsetsPadding(sideInsets))
                    BottomPanel {
                        SaveFailedNotice(saver)
                        EditorTabs(state, vm::setTab)
                        ScrollingTab(
                            vm, state, thumbnails, askRescan,
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = tabHeightLimit(fullHeight)),
                        )
                        PrimaryButton(
                            text = stringResource(R.string.editor_save_video),
                            onClick = export,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        Announcer(state.announcement)
    }

    if (confirmRescan) {
        ConfirmDialog(
            title = stringResource(R.string.editor_rescan_title),
            text = stringResource(R.string.editor_rescan_text),
            confirmLabel = stringResource(R.string.editor_rescan_confirm),
            dismissLabel = stringResource(R.string.editor_rescan_dismiss),
            onConfirm = {
                confirmRescan = false
                preview.pause()
                onRescanThorough()
            },
            onDismiss = { confirmRescan = false },
            destructive = true,
        )
    }
}

private const val SAVE_DEBOUNCE_MS = 600L
private const val DRAG_REBUILD_MS = 32L
private val CRAMPED_PREVIEW_HEIGHT = 220.dp

/**
 * How tall the open tab may grow under a preview on a phone held upright. The preview keeps at least
 * 28% of the screen after the bars, transport, tab row and Save button (about 356 dp together) are placed.
 */
private fun tabHeightLimit(screenHeight: Dp): Dp = (screenHeight - 356.dp - screenHeight * 0.28f).coerceAtLeast(120.dp)

@Composable
private fun ScrollingTab(
    vm: EditorViewModel,
    state: EditorState,
    thumbnails: ThumbnailCache,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Each tab, and each person's appearance list, starts at its own top.
    key(state.tab, state.openPersonId) {
        Box(modifier = modifier.verticalScroll(rememberScrollState())) {
            TabContent(vm, state, thumbnails, onRescan)
        }
    }
}

/** The panel beside the preview in landscape and on tablets. */
@Composable
private fun SidePanel(width: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(OpenBlurTheme.colors.surfaceRaised, RoundedCornerShape(topStart = 12.dp))
            // Taps on the panel's empty areas must not fall through to anything behind it.
            .pointerInput(Unit) { }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
            .padding(OpenBlurDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap),
        content = content,
    )
}

@Composable
private fun OverflowMenu(onRescan: () -> Unit) {
    val colors = OpenBlurTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(
            icon = OpenBlurIcons.More,
            contentDescription = stringResource(R.string.editor_more),
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = OpenBlurShapes.Control,
            containerColor = colors.surfaceRaised,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, colors.outlineStrong),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_rescan), style = MaterialTheme.typography.bodyLarge) },
                onClick = {
                    open = false
                    onRescan()
                },
                leadingIcon = { Icon(OpenBlurIcons.Rescan, contentDescription = null, modifier = Modifier.size(24.dp)) },
                colors = MenuDefaults.itemColors(textColor = colors.text, leadingIconColor = colors.text),
            )
        }
    }
}

@Composable
private fun SaveFailedNotice(saver: EditSaver) {
    if (saver.failed) {
        Notice(text = stringResource(R.string.editor_save_failed), tone = NoticeTone.Warning)
    }
}

/** Says each edit aloud for screen readers, since most of them show only as a change in the picture. */
@Composable
private fun Announcer(announcement: Announcement?) {
    val event = announcement?.event ?: return
    val text = when (event) {
        is EditorEvent.PersonBlur -> stringResource(
            when (event.choice) {
                BlurChoice.ALL -> R.string.editor_said_person_blurred
                BlurChoice.NONE -> R.string.editor_said_person_visible
                BlurChoice.MIXED -> R.string.editor_said_person_mixed
            },
            stringResource(R.string.editor_person, event.personNumber),
        )
        is EditorEvent.AppearanceBlur ->
            stringResource(if (event.blurred) R.string.editor_said_appearance_blurred else R.string.editor_said_appearance_visible)
        is EditorEvent.EveryoneBlur ->
            stringResource(if (event.blurred) R.string.editor_said_everyone_blurred else R.string.editor_said_everyone_visible)
        is EditorEvent.GroupBlur ->
            stringResource(if (event.blurred) R.string.editor_said_brief_blurred else R.string.editor_said_brief_visible)
        is EditorEvent.PersonSelected ->
            stringResource(R.string.editor_said_selected, stringResource(R.string.editor_person, event.personNumber))
        is EditorEvent.AreaSelected ->
            stringResource(R.string.editor_said_selected, stringResource(R.string.editor_area, event.regionId))
        is EditorEvent.AreaAdded ->
            stringResource(R.string.editor_said_area_added, stringResource(R.string.editor_area, event.regionId))
        is EditorEvent.AreaDeleted ->
            stringResource(R.string.editor_said_area_deleted, stringResource(R.string.editor_area, event.regionId))
        EditorEvent.SelectionCleared -> stringResource(R.string.editor_said_selection_cleared)
        EditorEvent.Undone -> stringResource(R.string.editor_said_undone)
        EditorEvent.Redone -> stringResource(R.string.editor_said_redone)
    }
    Box(
        modifier = Modifier
            .size(1.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = text
            },
    )
}

/** Keeps the editing session across rotation, which would otherwise drop the undo history and the playhead. */
internal class EditorSessionHolder : ViewModel() {
    private var projectId: String? = null
    private var session: EditorViewModel? = null

    fun sessionFor(project: Project): EditorViewModel {
        val existing = session
        // A new scan of the same project makes a new result object, and the old session's choices no longer fit it.
        if (existing != null && projectId == project.id && existing.result === project.result) return existing
        return newSession(project).also {
            session = it
            projectId = project.id
        }
    }
}

private fun newSession(project: Project) = EditorViewModel(project.result, project.edit, project.info.frameRate)

@Composable
private fun rememberEditorViewModel(project: Project): EditorViewModel {
    val owner = LocalViewModelStoreOwner.current
    return if (owner != null) {
        val holder: EditorSessionHolder = viewModel(viewModelStoreOwner = owner, key = "openblur.editor") { EditorSessionHolder() }
        remember(holder, project.id, project.result) { holder.sessionFor(project) }
    } else {
        remember(project.id, project.result) { newSession(project) }
    }
}

/** Writes the edit to the project store. One write at a time, and never the same edit twice. */
internal class EditSaver(private val projects: ProjectStore, private val projectId: String, saved: EditState) {
    var failed by mutableStateOf(false)
        private set

    private val mutex = Mutex()
    private var lastSaved = saved

    suspend fun save(edit: EditState) {
        // A write that has started runs to its end, so a newer edit never cancels it halfway through the file.
        withContext(NonCancellable) {
            mutex.withLock {
                if (edit == lastSaved && !failed) return@withLock
                try {
                    projects.saveEdit(projectId, edit)
                    lastSaved = edit
                    failed = false
                } catch (_: Exception) {
                    failed = true
                }
            }
        }
    }

    /** For leaving the screen: the write outlives the composition that asked for it. */
    fun saveDetached(edit: EditState) {
        if (edit == lastSaved && !failed) return
        Detached.launch { save(edit) }
    }

    private companion object {
        val Detached = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
