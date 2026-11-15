package io.github.freew_w.fischdroid

import android.app.Application
import io.github.freew_w.fischdroid.config.Config

/*
Config init here
No init needed for Controller, but ControlLoop and Detector has to be reset in Controller when staled
Overlays init in TapService
 */
class FischDroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Config.init(this)
    }
}
