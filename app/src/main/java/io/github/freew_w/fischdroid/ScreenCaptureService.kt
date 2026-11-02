package io.github.freew_w.fischdroid

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
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "ScreenCaptureService"

class ScreenCaptureService : Service() {
    /**
     * Everything that only exists while a capture is running. Keeping it in one object means
     * there is a single source of truth: if it is `null`, nothing is capturing, and if it is
     * not, all of its parts (projection, reader, display) are valid.
     */
    private class CaptureSession(
        val projection: MediaProjection,
        var reader: ImageReader,
        var display: VirtualDisplay,
    ) {
        var bitmap: Bitmap? = null
    }

    private var session: CaptureSession? = null
    private var isProcessingFrame = false
    private var debugFrameSaved = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private companion object {
        /**
         * Set to `true` to dump the first captured frame of each stream setup as a PNG.
         * Pull it from the device with:
         * `adb pull /sdcard/Android/data/io.github.freew_w.fischdroid/files/Pictures/`
         */
        private const val DEBUG_SAVE_FRAMES = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()

        val resultCode =
            intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION") val data =
            intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, data) ?: return START_NOT_STICKY
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                // The projection is already stopping, so only release the resources around it.
                releaseSession(stopProjection = false)
                stopSelf()
            }
        }, null)

        startCapture(projection)
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        reconfigureCapture()
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

    private fun startCapture(projection: MediaProjection) {
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
            return
        }
        session = CaptureSession(projection, reader, display)
        attachFrameListener(reader)
    }

    private fun reconfigureCapture() {
        val current = session ?: return
        val (width, height, density) = screenMetrics()

        val reader = newImageReader(width, height)
        current.reader.setOnImageAvailableListener(null, null)
        current.reader.close()
        current.reader = reader
        current.bitmap = null

        current.display.resize(width, height, density)
        current.display.surface = reader.surface

        debugFrameSaved = false
        attachFrameListener(reader)
    }

    private fun releaseSession(stopProjection: Boolean = true) {
        val current = session ?: return
        // Clear the field first so re-entrant calls (e.g. from onStop) become no-ops.
        session = null
        current.reader.setOnImageAvailableListener(null, null)
        current.display.release()
        current.reader.close()
        if (stopProjection) current.projection.stop()
    }

    private fun screenMetrics(): Triple<Int, Int, Int> {
        val metrics = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(this)
        val bounds = metrics.bounds
        return Triple(bounds.width(), bounds.height(), metrics.density.toInt())
    }

    private fun newImageReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

    private fun attachFrameListener(reader: ImageReader) {
        reader.setOnImageAvailableListener({ onImageAvailable(it) }, mainHandler)
    }

    private fun onImageAvailable(reader: ImageReader) {
        val current = session ?: return
        val image = reader.acquireLatestImage() ?: return

        try {
            if (isProcessingFrame) return
            isProcessingFrame = true

            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val bufferWidth = maxOf(image.width, (plane.rowStride + pixelStride - 1) / pixelStride)
            val bufferHeight = image.height

            // Keep a local reference so a configuration change cannot null it mid-frame.
            val frameBitmap =
                current.bitmap?.takeIf { it.width == bufferWidth && it.height == bufferHeight }
                    ?: createBitmap(bufferWidth, bufferHeight).also { current.bitmap = it }

            plane.buffer.rewind()
            frameBitmap.copyPixelsFromBuffer(plane.buffer)

            val frameWidth = image.width
            val frameHeight = image.height

            serviceScope.launch {
                if (DEBUG_SAVE_FRAMES && !debugFrameSaved) {
                    debugFrameSaved = true
                    saveBitmapForDebug(frameBitmap, frameWidth, frameHeight)
                }
                Detector.processFrame(frameBitmap)
                isProcessingFrame = false
            }
        } catch (t: Throwable) {
            isProcessingFrame = false
            throw t
        } finally {
            // Always release the image, including on the early return above and on exceptions.
            image.close()
        }
    }

    /**
     * Saves [bitmap] as a PNG in the app-specific external Pictures directory so it can be
     * inspected from outside the app (no storage permission required).
     *
     * [width] and [height] are the real frame dimensions; the bitmap itself may be wider
     * because of row-stride padding, so it is cropped before saving.
     *
     * @return the written file, or `null` if saving failed.
     */
    private fun saveBitmapForDebug(bitmap: Bitmap, width: Int, height: Int): File? {
        val cropped = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } else {
            bitmap
        }
        return try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
            val file = File(dir, "capture_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { output ->
                if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    Log.w(TAG, "saveBitmapForDebug: failed to compress bitmap")
                    return null
                }
            }
            Log.d(
                TAG,
                "saveBitmapForDebug: saved ${cropped.width}x${cropped.height} bitmap to ${file.absolutePath}"
            )
            file
        } catch (e: IOException) {
            Log.e(TAG, "saveBitmapForDebug: failed to write bitmap", e)
            null
        } finally {
            if (cropped !== bitmap) cropped.recycle()
        }
    }
}
