package io.github.vikas0706.openblur

import android.app.Application
import io.github.vikas0706.openblur.api.AppServices
import io.github.vikas0706.openblur.api.AppServicesOwner

class OpenBlurApp : Application(), AppServicesOwner {
    override val services: AppServices by lazy { Wiring.create(this) }
}
