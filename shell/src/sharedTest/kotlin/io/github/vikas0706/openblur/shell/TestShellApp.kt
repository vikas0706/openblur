package io.github.vikas0706.openblur.shell

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vikas0706.openblur.api.AppServices
import io.github.vikas0706.openblur.api.Project
import io.github.vikas0706.openblur.core.EditState

/**
 * The Application the shell's tests run in. It stands in for the app module: fake services, and a
 * placeholder editor with one control for each callback the real editor has.
 */
class TestShellApp : Application(), ShellHost {
    var fake = FakeAppServices()
        private set

    /** The edit the placeholder editor hands to onExport. */
    var editToExport = EditState()

    override val services: AppServices get() = fake

    fun reset() {
        fake = FakeAppServices()
        editToExport = EditState()
    }

    @Composable
    override fun Editor(
        project: Project,
        onBack: () -> Unit,
        onExport: (edit: EditState) -> Unit,
        onRescanThorough: () -> Unit,
        modifier: Modifier,
    ) {
        Column(modifier.padding(top = 48.dp)) {
            Text(EDITOR_TITLE_PREFIX + project.displayName)
            Text(EDITOR_EXPORT, Modifier.clickable { onExport(editToExport) }.padding(16.dp))
            Text(EDITOR_RESCAN, Modifier.clickable(onClick = onRescanThorough).padding(16.dp))
            Text(EDITOR_BACK, Modifier.clickable(onClick = onBack).padding(16.dp))
        }
    }

    companion object {
        const val EDITOR_TITLE_PREFIX = "Placeholder editor: "
        const val EDITOR_EXPORT = "Placeholder export"
        const val EDITOR_RESCAN = "Placeholder rescan"
        const val EDITOR_BACK = "Placeholder back"
    }
}
