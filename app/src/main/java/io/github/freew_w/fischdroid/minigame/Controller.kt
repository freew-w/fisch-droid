package io.github.freew_w.fischdroid.minigame

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import io.github.freew_w.fischdroid.overlay.OverlayManager
import io.github.freew_w.fischdroid.services.TapService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "MinigameController"

object Controller {
    suspend fun processFrame(bitmap: Bitmap) {
        withContext(Dispatchers.Default) {
            if (bitmap.height > bitmap.width) return@withContext

            val now = SystemClock.elapsedRealtime()
            val (fish, bar) = Detector.detect(bitmap)

            withContext(Dispatchers.Main) {
                OverlayManager.updateMinigame(fish, bar)
            }

            if (fish == null || bar == null) return@withContext
            val service = TapService.instance ?: return@withContext
            val action = ControlLoop.decide(fish, bar, now) ?: return@withContext

            Log.d(TAG, "action: $action")

            withContext(Dispatchers.Main) {
                if (action.isHold) {
                    service.press(1100f, 600f, action.durationMs)
                }
                delay(action.durationMs.milliseconds)
            }
        }
    }
}
