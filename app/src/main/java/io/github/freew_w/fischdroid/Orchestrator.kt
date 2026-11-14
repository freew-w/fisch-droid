package io.github.freew_w.fischdroid

import android.graphics.Bitmap
import android.os.SystemClock
import io.github.freew_w.fischdroid.minigame.Controller
import io.github.freew_w.fischdroid.minigame.Detector
import io.github.freew_w.fischdroid.services.TapService
import io.github.freew_w.fischdroid.shake.Clicker

object Orchestrator {
    private const val CAST_AFTER_MS = 1000L

    private var lastDetectionMs = 0L

    var started = false

    private fun castRod() {
        TapService.instance?.press(1100f, 600f, 500L)
    }

    suspend fun processFrame(bitmap: Bitmap, frameWidth: Int, frameHeight: Int) {
        if (bitmap.height > bitmap.width || !started) {
            TapService.instance?.setHolding(false, 1100f, 600f)
            return
        }

        val now = SystemClock.elapsedRealtime()

        if (Detector.notInMinigame(bitmap)) {
            if (now - lastDetectionMs > CAST_AFTER_MS) {
                lastDetectionMs = now
                castRod()
            }

            if (Clicker.processFrame(bitmap, frameWidth, frameHeight)) {
                lastDetectionMs = now
            }
        } else {
            if (Controller.processFrame(bitmap)) {
                lastDetectionMs = now
            }
        }
    }
}