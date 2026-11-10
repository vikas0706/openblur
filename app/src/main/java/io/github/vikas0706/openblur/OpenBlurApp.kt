package io.github.vikas0706.openblur

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.vikas0706.openblur.api.AppServices
import io.github.vikas0706.openblur.api.AppServicesOwner
import io.github.vikas0706.openblur.api.Project
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.editor.EditorScreen
import io.github.vikas0706.openblur.shell.ShellHost

class OpenBlurApp : Application(), AppServicesOwner, ShellHost {
    override val services: AppServices by lazy { Wiring.create(this) }

    @Composable
    override fun Editor(
        project: Project,
        onBack: () -> Unit,
        onExport: (edit: EditState) -> Unit,
        onRescanThorough: () -> Unit,
        modifier: Modifier,
    ) {
        EditorScreen(
            project = project,
            services = services,
            onBack = onBack,
            onExport = onExport,
            onRescanThorough = onRescanThorough,
            modifier = modifier,
        )
    }
}
