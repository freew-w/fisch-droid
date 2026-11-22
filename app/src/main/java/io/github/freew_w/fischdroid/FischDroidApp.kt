package io.github.freew_w.fischdroid

import android.app.Application
import io.github.freew_w.fischdroid.config.Config

/*
Config is initialized here. The automation pipeline owns its detector/controller state and resets
it on start/stop and on minigame transitions; overlays are created by TapService.
 */
class FischDroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Config.init(this)
    }
}
