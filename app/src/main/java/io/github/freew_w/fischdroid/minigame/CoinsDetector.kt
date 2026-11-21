package io.github.freew_w.fischdroid.minigame

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the cream "C$" money indicator in the bottom-right HUD.
 *
 * Calibration-free: every threshold is a ratio of the frame size or of the detected glyph
 * height, so the same detector works on the 1892x1010 and 2400x1080 fixtures. The only
 * color constant is the indicator's solid fill.
 *
 * Known limitation (accepted, same as the old pixel probe): while a full-screen overlay such as
 * settings or the backpack is open, "C$" is hidden and this returns null, i.e. "in minigame".
 */
object CoinsDetector {
    data class Indicator(val left: Int, val top: Int, val right: Int, val bottom: Int)

    // Solid fill measured as #FFFDE4 on both fixture families; device color profiles can shift it.
    private const val FILL_R = 255
    private const val FILL_G = 253
    private const val FILL_B = 228
    private const val COLOR_TOLERANCE = 12

    // Measured C$ bounds: x 0.973-0.992w, y 0.902-0.963h. 0.85 also excludes "Level" and icons.
    private const val ROI_FRACTION = 0.85f

    // Measured fill-only glyph heights: "C" 2.22-2.28% and "$" 2.96-2.97% of frame height.
    private const val MIN_GLYPH_HEIGHT_FRACTION = 0.018f
    private const val MAX_GLYPH_HEIGHT_FRACTION = 0.055f

    // Measured fill-only glyph aspects: "C" 0.71-0.74, "$" 0.50; the outline is excluded.
    private const val MIN_GLYPH_ASPECT = 0.30f
    private const val MAX_GLYPH_ASPECT = 1.30f

    // Measured pair: aspect 1.09-1.10, "$"/"C" height ratio 1.30-1.33, gap 2-3 px.
    private const val MIN_PAIR_ASPECT = 0.85f
    private const val MAX_PAIR_ASPECT = 1.60f
    private const val MAX_PAIR_HEIGHT_RATIO = 1.45f
    private const val MAX_PAIR_GAP_FRACTION = 0.5f

    // Measured right edge 9-13 px (0.23-0.29 glyph heights) from the frame edge; amount digits
    // end more than two glyph heights short, so this anchor is what rejects the digits.
    private const val MAX_RIGHT_GAP_FRACTION = 0.8f

    // Measured centres: x 0.983-0.985w, y 0.921-0.963h.
    private const val MIN_CENTRE_X_FRACTION = 0.93f
    private const val MIN_CENTRE_Y_FRACTION = 0.88f
    private const val TARGET_CENTRE_X_FRACTION = 0.985f
    private const val TARGET_CENTRE_Y_FRACTION = 0.94f
    private const val TARGET_PAIR_ASPECT = 1.10f

    // Ignores anti-aliased specks that survive the color tolerance.
    private const val MIN_COMPONENT_AREA = 6

    // The committed C$ crops are 51x42 and 51x46; anything this small is scanned whole.
    private const val MIN_FRAME_WIDTH = 320
    private const val MIN_FRAME_HEIGHT = 240

    private class Component {
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0
        val width get() = right - left + 1
        val height get() = bottom - top + 1
    }

    // Reused across frames; access is serialized by @Synchronized.
    private val components = ArrayList<Component>()
    private var componentCount = 0
    private var roiPixels = IntArray(0)
    private var mask = BooleanArray(0)
    private var queue = IntArray(0)
    private var order = IntArray(0)
    private var groupLast = IntArray(0)
    private var groupSecondLast = IntArray(0)
    private var groupSize = IntArray(0)

    @Synchronized
    fun isVisible(bitmap: Bitmap, width: Int = bitmap.width, height: Int = bitmap.height): Boolean {
        if (height >= width) return false
        val frame = isFullFrame(width, height)
        val roiX = roiStart(width, frame)
        val roiY = roiStart(height, frame)
        val roiW = width - roiX
        val roiH = height - roiY
        if (roiW <= 0 || roiH <= 0) return false

        val size = roiW * roiH
        if (roiPixels.size < size) roiPixels = IntArray(size)
        bitmap.getPixels(roiPixels, 0, roiW, roiX, roiY, roiW, roiH)
        return detect(roiPixels, roiW, 0, 0, roiX, roiY, roiW, roiH, width, height, frame) != null
    }

    /** Pure-JVM entry point used by host tests. [pixels] is a row-major ARGB frame. */
    @Synchronized
    fun find(pixels: IntArray, width: Int, height: Int): Indicator? {
        if (height >= width) return null
        val frame = isFullFrame(width, height)
        val roiX = roiStart(width, frame)
        val roiY = roiStart(height, frame)
        val roiW = width - roiX
        val roiH = height - roiY
        if (roiW <= 0 || roiH <= 0) return null
        return detect(pixels, width, roiX, roiY, roiX, roiY, roiW, roiH, width, height, frame)
    }

    private fun isFullFrame(width: Int, height: Int) =
        width >= MIN_FRAME_WIDTH && height >= MIN_FRAME_HEIGHT

    private fun roiStart(size: Int, frame: Boolean) =
        if (frame) (size * ROI_FRACTION).toInt() else 0

    private fun isFill(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return abs(r - FILL_R) <= COLOR_TOLERANCE &&
            abs(g - FILL_G) <= COLOR_TOLERANCE &&
            abs(b - FILL_B) <= COLOR_TOLERANCE
    }

    private fun aspect(width: Int, height: Int) = width.toFloat() / height

    private fun detect(
        pixels: IntArray,
        stride: Int,
        sourceX: Int,
        sourceY: Int,
        offsetX: Int,
        offsetY: Int,
        roiW: Int,
        roiH: Int,
        frameW: Int,
        frameH: Int,
        frame: Boolean,
    ): Indicator? {
        val size = roiW * roiH
        if (mask.size < size) {
            mask = BooleanArray(size)
            queue = IntArray(size)
        }
        for (y in 0 until roiH) {
            val src = (sourceY + y) * stride + sourceX
            val dst = y * roiW
            for (x in 0 until roiW) mask[dst + x] = isFill(pixels[src + x])
        }

        val minHeight = if (frame) max(1, (frameH * MIN_GLYPH_HEIGHT_FRACTION).toInt()) else 1
        val maxHeight = if (frame) (frameH * MAX_GLYPH_HEIGHT_FRACTION).toInt() else frameH
        componentCount = 0
        for (sy in 0 until roiH) {
            for (sx in 0 until roiW) {
                val start = sy * roiW + sx
                if (!mask[start]) continue

                mask[start] = false
                queue[0] = start
                var head = 0
                var tail = 1
                var left = sx
                var right = sx
                var top = sy
                var bottom = sy
                var area = 0
                while (head < tail) {
                    val current = queue[head++]
                    val cy = current / roiW
                    val cx = current - cy * roiW
                    area++
                    if (cx < left) left = cx
                    if (cx > right) right = cx
                    if (cy < top) top = cy
                    if (cy > bottom) bottom = cy
                    for (dy in -1..1) {
                        val ny = cy + dy
                        if (ny < 0 || ny >= roiH) continue
                        val row = ny * roiW
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            if (nx < 0 || nx >= roiW) continue
                            val next = row + nx
                            if (!mask[next]) continue
                            mask[next] = false
                            queue[tail++] = next
                        }
                    }
                }

                val glyphW = right - left + 1
                val glyphH = bottom - top + 1
                if (area < MIN_COMPONENT_AREA || glyphH !in minHeight..maxHeight) continue
                if (aspect(glyphW, glyphH) !in MIN_GLYPH_ASPECT..MAX_GLYPH_ASPECT) continue

                val component = if (componentCount < components.size) {
                    components[componentCount]
                } else {
                    Component().also { components.add(it) }
                }
                component.left = left
                component.top = top
                component.right = right
                component.bottom = bottom
                componentCount++
            }
        }
        if (componentCount == 0) return null

        if (order.size < componentCount) {
            order = IntArray(componentCount)
            groupLast = IntArray(componentCount)
            groupSecondLast = IntArray(componentCount)
            groupSize = IntArray(componentCount)
        }
        for (i in 0 until componentCount) order[i] = i
        for (i in 1 until componentCount) {
            val value = order[i]
            val key = components[value].left
            var j = i - 1
            while (j >= 0 && components[order[j]].left > key) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = value
        }

        var groups = 0
        for (i in 0 until componentCount) {
            val component = components[order[i]]
            var placed = false
            for (g in 0 until groups) {
                val last = components[groupLast[g]]
                val overlap = min(last.bottom, component.bottom) - max(last.top, component.top) + 1
                if (overlap < 0.75f * min(last.height, component.height)) continue
                if (max(last.height, component.height) > MAX_PAIR_HEIGHT_RATIO * min(last.height, component.height)) continue
                val gap = component.left - last.right - 1
                if (gap < -1 || gap > MAX_PAIR_GAP_FRACTION * min(last.height, component.height)) continue
                groupSecondLast[g] = groupLast[g]
                groupLast[g] = order[i]
                groupSize[g]++
                placed = true
                break
            }
            if (!placed) {
                groupLast[groups] = order[i]
                groupSecondLast[groups] = -1
                groupSize[groups] = 1
                groups++
            }
        }

        var best: Indicator? = null
        var bestScore = Double.MAX_VALUE
        for (g in 0 until groups) {
            val candidate: Indicator
            if (groupSize[g] == 1) {
                // Downscaling can fuse C and $ into one component.
                val only = components[groupLast[g]]
                if (aspect(only.width, only.height) !in MIN_PAIR_ASPECT..MAX_PAIR_ASPECT) continue
                candidate = Indicator(
                    only.left + offsetX,
                    only.top + offsetY,
                    only.right + offsetX,
                    only.bottom + offsetY,
                )
            } else {
                val first = components[groupSecondLast[g]]
                val second = components[groupLast[g]]
                if (max(first.height, second.height) > MAX_PAIR_HEIGHT_RATIO * min(first.height, second.height)) continue
                val gap = second.left - first.right - 1
                if (gap < -1 || gap > MAX_PAIR_GAP_FRACTION * min(first.height, second.height)) continue
                val left = min(first.left, second.left) + offsetX
                val top = min(first.top, second.top) + offsetY
                val right = max(first.right, second.right) + offsetX
                val bottom = max(first.bottom, second.bottom) + offsetY
                if (aspect(right - left + 1, bottom - top + 1) !in MIN_PAIR_ASPECT..MAX_PAIR_ASPECT) continue
                candidate = Indicator(left, top, right, bottom)
            }

            val glyphHeight = candidate.bottom - candidate.top + 1
            if (frameW - 1 - candidate.right > MAX_RIGHT_GAP_FRACTION * glyphHeight) continue

            val centreX = (candidate.left + candidate.right) / 2.0
            val centreY = (candidate.top + candidate.bottom) / 2.0
            if (frame) {
                if (centreX < frameW * MIN_CENTRE_X_FRACTION) continue
                if (centreY < frameH * MIN_CENTRE_Y_FRACTION) continue
            }

            val pairAspect = aspect(candidate.right - candidate.left + 1, glyphHeight)
            val score = abs(pairAspect - TARGET_PAIR_ASPECT) * 1000 +
                hypot(
                    centreX - frameW * TARGET_CENTRE_X_FRACTION,
                    centreY - frameH * TARGET_CENTRE_Y_FRACTION,
                )
            if (score < bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }
}
