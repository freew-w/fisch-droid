package io.github.freew_w.fischdroid.setup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import androidx.core.graphics.get
import androidx.core.graphics.withTranslation
import io.github.freew_w.fischdroid.R
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a bitmap that can be pinched, zoomed and panned. A tap only moves the crosshair cursor;
 * nothing is committed until the caller reads [cursor] or [colorAtCursor].
 */
class PixelPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    fun interface OnCursorChangedListener {
        fun onCursorChanged(x: Int, y: Int, color: Int)
    }

    var onCursorChanged: OnCursorChangedListener? = null

    private var bitmap: Bitmap? = null

    private var scale = 1f
    private var minScale = 1f
    private var maxScale = 8f
    private var offsetX = 0f
    private var offsetY = 0f
    private var hasFit = false

    private var cursorX = 0
    private var cursorY = 0
    private var pendingCursor: PixelPoint? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false
    private var scaling = false

    private val backgroundPaint = Paint().apply {
        color = resolveThemeColor(
            com.google.android.material.R.attr.colorSurfaceContainerHighest,
            Color.DKGRAY,
        )
    }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crosshairShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(140, 0, 0, 0)
        strokeWidth = 4f * density
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.5f * density
    }
    private val pixelHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(160, 0, 0, 0)
        strokeWidth = 4f * density
    }
    private val pixelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = resolveThemeColor(androidx.appcompat.R.attr.colorPrimary, Color.CYAN)
        strokeWidth = 1.5f * density
    }

    private val scaleDetector = ScaleGestureDetector(
        context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                return bitmap != null
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (bitmap == null) return false
                val next = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
                if (next == scale) return true

                // Keep the pinch focal point pinned to the same image pixel.
                offsetX = detector.focusX - (detector.focusX - offsetX) * (next / scale)
                offsetY = detector.focusY - (detector.focusY - offsetY) * (next / scale)
                scale = next
                clampTranslation()
                invalidate()
                return true
            }
        }).apply { isQuickScaleEnabled = false }

    private companion object {
        const val ZOOM_STEP = 1.5f
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.setup_zoom_hint)
    }

    fun setImage(bitmap: Bitmap, initialCursor: PixelPoint? = null) {
        this.bitmap = bitmap
        pendingCursor = initialCursor
        hasFit = false
        if (width > 0 && height > 0) fitToView()
        invalidate()
    }

    val hasImage: Boolean
        get() = bitmap != null

    val cursor: PixelPoint
        get() = PixelPoint(cursorX, cursorY)

    fun setCursor(x: Int, y: Int) {
        val bmp = bitmap
        if (bmp == null) {
            pendingCursor = PixelPoint(x, y)
            return
        }
        cursorX = x.coerceIn(0, bmp.width - 1)
        cursorY = y.coerceIn(0, bmp.height - 1)
        ensureCursorVisible()
        notifyCursor()
        invalidate()
    }

    /** Moves the cursor by exactly one image pixel in each direction, clamped to the image. */
    fun nudge(dx: Int, dy: Int): Boolean {
        val bmp = bitmap ?: return false
        val nextX = (cursorX + dx).coerceIn(0, bmp.width - 1)
        val nextY = (cursorY + dy).coerceIn(0, bmp.height - 1)
        if (nextX == cursorX && nextY == cursorY) return false
        cursorX = nextX
        cursorY = nextY
        ensureCursorVisible()
        notifyCursor()
        invalidate()
        return true
    }

    fun colorAtCursor(): Int = pixelColor(cursorX, cursorY)

    fun textAtCursor(): String = bitmap?.let {
        if (cursorX in 0 until it.width && cursorY in 0 until it.height) {
            String.format("#%06X", pixelColor(cursorX, cursorY) and 0xFFFFFF)
        } else {
            ""
        }
    } ?: ""

    fun fitToScreen() {
        if (bitmap == null) return
        pendingCursor = PixelPoint(cursorX, cursorY)
        fitToView()
    }

    /** Steps the zoom in around the cursor; returns false when already at the maximum. */
    fun zoomIn(): Boolean = zoomBy(ZOOM_STEP)

    /** Steps the zoom out around the cursor; returns false when already fitted. */
    fun zoomOut(): Boolean = zoomBy(1f / ZOOM_STEP)

    private fun zoomBy(factor: Float): Boolean {
        if (bitmap == null || width == 0 || height == 0) return false
        val next = (scale * factor).coerceIn(minScale, maxScale)
        if (next == scale) return false

        // Keep the selected pixel under the same view point while stepping.
        val anchorX = offsetX + (cursorX + 0.5f) * scale
        val anchorY = offsetY + (cursorY + 0.5f) * scale
        offsetX = anchorX - (anchorX - offsetX) * (next / scale)
        offsetY = anchorY - (anchorY - offsetY) * (next / scale)
        scale = next
        clampTranslation()
        invalidate()
        return true
    }

    private fun pixelColor(x: Int, y: Int): Int {
        val bmp = bitmap ?: return Color.TRANSPARENT
        if (x !in 0 until bmp.width || y !in 0 until bmp.height) return Color.TRANSPARENT
        return runCatching { bmp[x, y] }.getOrDefault(Color.TRANSPARENT)
    }

    private fun fitToView() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return

        val fit = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        minScale = fit
        maxScale = max(fit * 24f, 8f)
        scale = fit
        offsetX = (width - bmp.width * fit) / 2f
        offsetY = (height - bmp.height * fit) / 2f

        val pending = pendingCursor
        if (pending != null) {
            cursorX = pending.x.coerceIn(0, bmp.width - 1)
            cursorY = pending.y.coerceIn(0, bmp.height - 1)
            pendingCursor = null
        } else {
            cursorX = bmp.width / 2
            cursorY = bmp.height / 2
        }

        hasFit = true
        notifyCursor()
        invalidate()
    }

    private fun clampTranslation() {
        val bmp = bitmap ?: return
        val scaledWidth = bmp.width * scale
        val scaledHeight = bmp.height * scale
        offsetX = if (scaledWidth <= width) {
            (width - scaledWidth) / 2f
        } else {
            offsetX.coerceIn(width - scaledWidth, 0f)
        }
        offsetY = if (scaledHeight <= height) {
            (height - scaledHeight) / 2f
        } else {
            offsetY.coerceIn(height - scaledHeight, 0f)
        }
    }

    private fun ensureCursorVisible() {
        if (width == 0 || height == 0) return
        val margin = 24f * density
        val cx = offsetX + (cursorX + 0.5f) * scale
        val cy = offsetY + (cursorY + 0.5f) * scale

        if (width > margin * 2) {
            if (cx < margin) offsetX += margin - cx
            else if (cx > width - margin) offsetX -= cx - (width - margin)
        }
        if (height > margin * 2) {
            if (cy < margin) offsetY += margin - cy
            else if (cy > height - margin) offsetY -= cy - (height - margin)
        }
        clampTranslation()
    }

    private fun notifyCursor() {
        onCursorChanged?.onCursorChanged(cursorX, cursorY, pixelColor(cursorX, cursorY))
    }

    private fun moveCursorTo(viewX: Float, viewY: Float) {
        val bmp = bitmap ?: return
        if (scale <= 0f) return
        val imageX = ((viewX - offsetX) / scale).toInt().coerceIn(0, bmp.width - 1)
        val imageY = ((viewY - offsetY) / scale).toInt().coerceIn(0, bmp.height - 1)
        if (imageX == cursorX && imageY == cursorY) {
            notifyCursor()
            return
        }
        cursorX = imageX
        cursorY = imageY
        notifyCursor()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmap == null) return
        if (hasFit) pendingCursor = PixelPoint(cursorX, cursorY)
        fitToView()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        val bmp = bitmap ?: return

        val imageScale = scale
        canvas.withTranslation(offsetX, offsetY) {
            scale(imageScale, imageScale)
            // Nearest-neighbor while zoomed in so individual pixels stay crisp.
            imagePaint.isFilterBitmap = imageScale < 2.5f
            drawBitmap(bmp, 0f, 0f, imagePaint)
        }

        val cx = offsetX + (cursorX + 0.5f) * scale
        val cy = offsetY + (cursorY + 0.5f) * scale

        canvas.drawLine(0f, cy, width.toFloat(), cy, crosshairShadowPaint)
        canvas.drawLine(cx, 0f, cx, height.toFloat(), crosshairShadowPaint)
        canvas.drawLine(0f, cy, width.toFloat(), cy, crosshairPaint)
        canvas.drawLine(cx, 0f, cx, height.toFloat(), crosshairPaint)

        // Outline the selected pixel; keep it at least 3dp so it stays visible when zoomed out.
        val size = max(scale, 3f * density)
        val left = cx - size / 2f
        val top = cy - size / 2f
        canvas.drawRect(left, top, left + size, top + size, pixelHaloPaint)
        canvas.drawRect(left, top, left + size, top + size, pixelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                tracking = true
                dragging = false
                scaling = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                tracking = false
                scaling = true
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_POINTER_UP -> {
                lastX = event.getX(0)
                lastY = event.getY(0)
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    scaling = true
                } else if (tracking) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (!dragging && hypot(event.x - downX, event.y - downY) > touchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        offsetX += dx
                        offsetY += dy
                        clampTranslation()
                        invalidate()
                    }
                }
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP -> {
                // A tap positions the cursor; it never commits a value.
                if (tracking && !dragging && !scaling) {
                    performClick()
                    moveCursorTo(event.x, event.y)
                }
                tracking = false
                dragging = false
                scaling = false
            }

            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                dragging = false
                scaling = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun resolveThemeColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return fallback
        return if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }
}
