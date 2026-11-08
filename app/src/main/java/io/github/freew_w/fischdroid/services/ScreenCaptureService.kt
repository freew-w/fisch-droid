package io.github.freew_w.fischdroid.services

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import androidx.window.layout.WindowMetricsCalculator
import io.github.freew_w.fischdroid.minigame.Controller
import io.github.freew_w.fischdroid.shake.Clicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {
    private class CaptureSession(
        val projection: MediaProjection,
        var reader: ImageReader,
        var display: VirtualDisplay,
    ) {
        var bitmap: Bitmap? = null
    }

    private var session: CaptureSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isProcessingFrame = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()

        if (intent?.getStringExtra("ACTION") == "STOP") {
            stop()
            return START_NOT_STICKY
        }

        val resultCode =
            intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION") val data =
            intent?.getParcelableExtra<Intent>("data") ?: run {
                stop()
                return START_NOT_STICKY
            }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, data) ?: run {
            stop()
            return START_NOT_STICKY
        }
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
                super.onStop()
            }
        }, null)

        val (width, height, density) = screenMetrics()
        val reader = newImageReader(width, height)
        val display = projection.createVirtualDisplay(
            "FischDroidCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        ) ?: run {
            reader.close()
            projection.stop()
            stop()
            return START_NOT_STICKY
        }
        session = CaptureSession(projection, reader, display)
        attachFrameListener(reader)

        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val current = session ?: return
        current.reader.close()
        current.bitmap?.recycle()
        current.bitmap = null

        val (width, height, density) = screenMetrics()
        val reader = newImageReader(width, height)
        current.reader = reader
        current.display.resize(width, height, density)
        current.display.surface = reader.surface
        attachFrameListener(reader)
    }

    override fun onDestroy() {
        releaseSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val channelId = "screen_capture_channel"
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                channelId, "Screen Capture", NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification =
            NotificationCompat.Builder(this, channelId).setContentTitle("Fisch Droid")
                .setContentText("Capturing screen for automation")
                .setSmallIcon(android.R.drawable.ic_menu_camera).build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseSession() {
        val current = session ?: return
        session = null
        current.projection.stop()
        current.reader.close()
        current.display.release()
        current.bitmap?.recycle()
    }

    private fun screenMetrics(): Triple<Int, Int, Int> {
        val metrics = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(this)
        val bounds = metrics.bounds
        return Triple(bounds.width(), bounds.height(), (metrics.density * 160).toInt())
    }

    private fun newImageReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

    private fun attachFrameListener(reader: ImageReader) {
        reader.setOnImageAvailableListener({ onImageAvailable(it) }, mainHandler)
    }

    private fun onImageAvailable(reader: ImageReader) {
        val current = session ?: return
        val image = reader.acquireLatestImage() ?: return

        if (isProcessingFrame) {
            image.close()
            return
        }
        isProcessingFrame = true

        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val bufferWidth = maxOf(image.width, (plane.rowStride + pixelStride - 1) / pixelStride)
        val bufferHeight = image.height
        val frameWidth = image.width
        val frameHeight = image.height

        serviceScope.launch {
            try {
                // Keep a local reference so a configuration change cannot null it mid-frame.
                val frameBitmap =
                    current.bitmap?.takeIf { it.width == bufferWidth && it.height == bufferHeight }
                        ?: createBitmap(bufferWidth, bufferHeight).also { current.bitmap = it }

                plane.buffer.rewind()
                frameBitmap.copyPixelsFromBuffer(plane.buffer)
                image.close()

                Clicker.processFrame(frameBitmap, frameWidth, frameHeight)
                Controller.processFrame(frameBitmap)
            } finally {
                runCatching { image.close() }
                isProcessingFrame = false
            }
        }
    }
}
