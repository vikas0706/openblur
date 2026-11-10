package io.github.vikas0706.openblur.shell

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Runs the shell's device tests inside [TestShellApp], which MainActivity needs as its ShellHost. */
class ShellTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestShellApp::class.java.name, context)
}
