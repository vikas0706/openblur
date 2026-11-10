package io.github.vikas0706.openblur.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.vikas0706.openblur.api.AppServices
import io.github.vikas0706.openblur.api.Project
import io.github.vikas0706.openblur.core.EditState

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
}
