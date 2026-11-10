package io.github.vikas0706.openblur

import android.app.Application
import io.github.vikas0706.openblur.api.AppServices

/** Builds the real services. This stub is replaced when the modules are joined. */
internal object Wiring {
    fun create(app: Application): AppServices = error("Services are wired at integration")
}
