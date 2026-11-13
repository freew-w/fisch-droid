package io.github.freew_w.fischdroid.minigame

import android.graphics.Bitmap
import android.os.SystemClock
import io.github.freew_w.fischdroid.overlay.OverlayManager
import io.github.freew_w.fischdroid.services.TapService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val HOLD_X = 1100f
private const val HOLD_Y = 600f

object Controller {
    suspend fun processFrame(bitmap: Bitmap): Boolean {
        var detected = false

        withContext(Dispatchers.Default) {
            if (bitmap.height > bitmap.width) {
                TapService.instance?.setHolding(false, HOLD_X, HOLD_Y)
                return@withContext
            }

            val now = SystemClock.elapsedRealtime()
            val (fish, bar) = Detector.detect(bitmap)

            withContext(Dispatchers.Main) {
                OverlayManager.updateMinigame(fish, bar)
            }

            val hold = if (fish != null && bar != null) {
                detected = true
                ControlLoop.decide(fish, bar, now)
            } else {
                null
            }

            val service = TapService.instance
            when {
                fish == null || bar == null -> {
                    ControlLoop.reset()
                    service?.setHolding(false, HOLD_X, HOLD_Y)
                }

                hold == null -> service?.setHolding(false, HOLD_X, HOLD_Y)

                else -> service?.setHolding(hold, HOLD_X, HOLD_Y)
            }
        }
        return detected
    }
}
