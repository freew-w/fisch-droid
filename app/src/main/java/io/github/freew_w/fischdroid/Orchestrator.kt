package io.github.freew_w.fischdroid

import android.graphics.Bitmap
import android.os.SystemClock
import io.github.freew_w.fischdroid.config.Config
import io.github.freew_w.fischdroid.minigame.Controller
import io.github.freew_w.fischdroid.minigame.Detector
import io.github.freew_w.fischdroid.services.TapService
import io.github.freew_w.fischdroid.shake.Clicker

object Orchestrator {
    private const val CAST_AFTER_MS = 1000L

    private var lastDetectionMs = 0L

    var started = false

    private fun castRod(spec: Detector.ScanSpec) {
        TapService.instance?.press(spec.castHoldX, spec.castHoldY, 500L)
    }

    private fun scanSpec() = Detector.ScanSpec(
        trackX = Config.trackX,
        trackY = Config.trackY,
        trackW = Config.trackW,
        trackH = Config.trackH,
        control = Config.rodControl,
        positionGain = Config.positionGain,
        velocityGain = Config.velocityGain,
        switchBand = Config.switchBand,
        castHoldX = Config.castHoldX,
        castHoldY = Config.castHoldY,
        fishColor = Config.fishColor,
    )

    suspend fun processFrame(bitmap: Bitmap, frameWidth: Int, frameHeight: Int) {
        if (bitmap.height > bitmap.width || !started) {
            TapService.instance?.setHolding(false, 1100f, 600f)
            return
        }

        val now = SystemClock.elapsedRealtime()
        val spec = scanSpec()

        if (Detector.notInMinigame(bitmap)) {
            if (now - lastDetectionMs > CAST_AFTER_MS) {
                lastDetectionMs = now
                castRod(spec)
            }

            if (Clicker.processFrame(bitmap, frameWidth, frameHeight)) {
                lastDetectionMs = now
            }
        } else {
            if (Controller.processFrame(bitmap, spec)) {
                lastDetectionMs = now
            }
        }
    }
}