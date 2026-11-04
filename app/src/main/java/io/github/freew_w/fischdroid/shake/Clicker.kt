package io.github.freew_w.fischdroid.shake

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import io.github.freew_w.fischdroid.overlay.OverlayManager
import io.github.freew_w.fischdroid.services.TapService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ShakeClicker"

object Clicker {
    private const val INTERVAL_MS = 100L
    private const val TAP_DURATION_MS = 40L

    private var lastRunAt = 0L

    suspend fun processFrame(bitmap: Bitmap, width: Int, height: Int) {
        withContext(Dispatchers.Default) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRunAt < INTERVAL_MS) return@withContext
            lastRunAt = now

            val button = Detector.find(bitmap, width, height)
            withContext(Dispatchers.Main) {
                OverlayManager.updateShake(button)
            }
            if (button == null) return@withContext

            val service = TapService.instance ?: return@withContext

            Log.d(TAG, "tapping shake button at (${button.centerX}, ${button.centerY})")
            withContext(Dispatchers.Main) {
                service.press(
                    button.centerX.toFloat(),
                    button.centerY.toFloat(),
                    TAP_DURATION_MS
                )
            }
        }
    }
}
