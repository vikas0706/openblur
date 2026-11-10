package io.github.vikas0706.openblur.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.vikas0706.openblur.api.AppServices
import io.github.vikas0706.openblur.api.Project
import io.github.vikas0706.openblur.core.EditState

/** Implemented by the Application. Gives the shell its services and the editor screen without a module dependency. */
interface ShellHost {
    val services: AppServices

    @Composable
    fun Editor(
        project: Project,
        onBack: () -> Unit,
        onExport: (edit: EditState) -> Unit,
        onRescanThorough: () -> Unit,
        modifier: Modifier,
    )
}
