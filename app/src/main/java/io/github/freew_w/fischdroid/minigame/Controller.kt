package io.github.freew_w.fischdroid.minigame

import android.graphics.Bitmap
import android.os.SystemClock
import io.github.freew_w.fischdroid.overlay.DetectionOverlay
import io.github.freew_w.fischdroid.services.TapService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Controller {
    private const val HOLD_X = 1100f
    private const val HOLD_Y = 600f
    private const val RESET_AFTER_MS = 500L
    private var lastDetectedMs = 0L

    suspend fun processFrame(bitmap: Bitmap, spec: Detector.ScanSpec): Boolean {
        var detected = false

        withContext(Dispatchers.Default) {
            val now = SystemClock.elapsedRealtime()

            if (lastDetectedMs != 0L && now - lastDetectedMs > RESET_AFTER_MS) {
                Detector.reset()
                ControlLoop.reset()
            }

            val (fish, bar) = Detector.detect(bitmap, spec)

            withContext(Dispatchers.Main) {
                DetectionOverlay.updateMinigame(fish, bar)
            }

            val hold = if (fish != null && bar != null) {
                detected = true
                lastDetectedMs = now

                ControlLoop.decide(fish, bar, now, spec)
            } else {
                null
            }

            val service = TapService.instance
            if (hold == null) {
                service?.setHolding(false, HOLD_X, HOLD_Y)
            } else {
                service?.setHolding(hold, HOLD_X, HOLD_Y)
            }
        }
        return detected
    }
}
