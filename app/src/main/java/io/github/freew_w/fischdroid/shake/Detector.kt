package io.github.freew_w.fischdroid.shake

import android.graphics.Bitmap
import io.github.freew_w.fischdroid.shake.Detector.RING_STEPS
import java.util.Arrays
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The detector is deliberately template- and calibration-free: everything it looks for is a
 * *relative* shape, so it works on any resolution, UI scale and background without the user
 * configuring colors, paths or offsets.
 *
 * What the button always is:
 *  - five separate, equal-height white glyphs spelling "SHAKE" in a horizontal row,
 *  - with the word roughly four times wider than it is tall,
 *  - sitting in the middle of a dark disk,
 *  - surrounded by a thin bright circular ring.
 *
 * Detection is three cheap stages:
 *  1. threshold near-white pixels and label connected runs (no full-frame label buffer);
 *  2. keep letter-shaped components and group them into five-letter words by height/geometry;
 *  3. verify that a bright ring of the expected radius surrounds the word and that the disk
 *     interior is darker than the ring.
 *
 * Coordinates are full-frame pixels. Callers should only pass landscape frames, which is the
 * orientation the game is played in.
 */
object Detector {
    data class Button(
        val centerX: Int,
        val centerY: Int,
        val radius: Int,
        val score: Double,
    )

    /** A channel at or above this value counts as "white". Anti-aliased glyph edges go to ~200. */
    private const val WHITE_LEVEL = 140

    private const val MIN_LETTER_HEIGHT = 7

    /** Letters can never be taller than this fraction of the frame; filters background blobs. */
    private const val MAX_LETTER_HEIGHT_FRACTION = 0.14f

    private const val LETTER_MIN_ASPECT = 0.28f
    private const val LETTER_MAX_ASPECT = 1.20f
    private const val LETTER_MIN_FILL = 0.10f
    private const val LETTER_MAX_FILL = 0.55f

    /** "SHAKE" has exactly five glyphs. */
    private const val WORD_LETTER_COUNT = 5

    /**
     * Measured word aspect (width / height) across devices is 3.85-4.1. The range is kept
     * generous so UI scaling has room, but tight enough to reject unrelated five-letter words.
     */
    private const val WORD_MIN_ASPECT = 3.2f
    private const val WORD_MAX_ASPECT = 4.7f
    private const val WORD_MAX_HEIGHT_SPREAD = 1.25f
    private const val WORD_MIN_VERTICAL_OVERLAP = 0.75f
    private const val WORD_MAX_HEIGHT_RATIO = 1.25f
    private const val WORD_MAX_GAP_FRACTION = 0.5f

    /** The ring radius is ~0.67 x the word width on every measured device. */
    private const val RING_MIN_RADIUS_FRACTION = 0.55f
    private const val RING_MAX_RADIUS_FRACTION = 0.85f

    private const val RING_STEPS = 128
    private const val RING_BRIGHTNESS_DELTA = 35
    private const val MIN_RING_COVERAGE = 0.5f
    private const val MIN_RING_CONTRAST = 50.0
    private const val MIN_RING_SCORE = 40.0

    private val COS = FloatArray(RING_STEPS) { cos(2.0 * Math.PI * it / RING_STEPS).toFloat() }
    private val SIN = FloatArray(RING_STEPS) { sin(2.0 * Math.PI * it / RING_STEPS).toFloat() }

    /** Reused so the capture loop does not allocate a full-frame buffer on every frame. */
    private var pixelBuffer = IntArray(0)

    /**
     * Convenience entry point for the capture pipeline. [width] and [height] describe the real
     * frame; [bitmap] may be wider because of row-stride padding, in which case only the top-left
     * `width x height` region is analyzed.
     */
    @Synchronized
    fun find(bitmap: Bitmap, width: Int = bitmap.width, height: Int = bitmap.height): Button? {
        if (width <= 0 || height <= 0) return null
        val needed = width * height
        if (pixelBuffer.size < needed) pixelBuffer = IntArray(needed)
        bitmap.getPixels(pixelBuffer, 0, width, 0, 0, width, height)
        return find(pixelBuffer, width, height)
    }

    /**
     * Pure-JVM entry point, used by tests. [pixels] is packed ARGB with `width` pixels per row.
     */
    fun find(pixels: IntArray, width: Int, height: Int): Button? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        // The game is played in landscape; portrait frames are not this screen.
        if (height >= width) return null

        val components = findWhiteComponents(pixels, width, height)
        val maxLetterHeight =
            max(MIN_LETTER_HEIGHT + 1, (min(width, height) * MAX_LETTER_HEIGHT_FRACTION).toInt())

        val letters = components.filter { component ->
            val w = component.width
            val h = component.height
            if (w <= 1 || h < MIN_LETTER_HEIGHT || h > maxLetterHeight) {
                false
            } else {
                val fill = component.area.toFloat() / (w * h)
                val aspect = w.toFloat() / h
                aspect in LETTER_MIN_ASPECT..LETTER_MAX_ASPECT && fill in LETTER_MIN_FILL..LETTER_MAX_FILL
            }
        }

        var best: Button? = null
        for (word in groupWords(letters)) {
            if (word.size != WORD_LETTER_COUNT) continue

            val left = word.minOf { it.x0 }
            val right = word.maxOf { it.x1 }
            val top = word.minOf { it.y0 }
            val bottom = word.maxOf { it.y1 }
            val wordWidth = right - left + 1
            val wordHeight = bottom - top + 1
            if (wordHeight <= 0) continue

            val aspect = wordWidth.toFloat() / wordHeight
            if (aspect !in WORD_MIN_ASPECT..WORD_MAX_ASPECT) continue

            val heights = word.map { it.height }
            if (heights.max().toFloat() / heights.min() > WORD_MAX_HEIGHT_SPREAD) continue

            val centerX = (left + right) / 2
            val centerY = (top + bottom) / 2
            val ring = findRing(pixels, width, height, centerX, centerY, wordWidth) ?: continue

            if (ring.score < MIN_RING_SCORE ||
                ring.coverage < MIN_RING_COVERAGE ||
                ring.contrast < MIN_RING_CONTRAST
            ) {
                continue
            }

            val bestSoFar = best
            if (bestSoFar == null || ring.score > bestSoFar.score) {
                best = Button(centerX, centerY, ring.radius, ring.score)
            }
        }
        return best
    }

    private class Component(
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val area: Int,
    ) {
        val width: Int get() = x1 - x0 + 1
        val height: Int get() = y1 - y0 + 1
    }

    /**
     * Labels connected white regions with a run-length union-find.
     *
     * Every row is reduced to horizontal runs of white pixels, and each run is unioned with the
     * runs it overlaps in the row above. Only one union-find node per run is needed, so memory
     * stays tiny even on a 2400x1080 frame.
     */
    private fun findWhiteComponents(pixels: IntArray, width: Int, height: Int): List<Component> {
        val runs = RunUnionFind()

        var prevStart = IntArray(width + 1)
        var prevEnd = IntArray(width + 1)
        var prevId = IntArray(width + 1)
        var prevCount = 0

        var curStart = IntArray(width + 1)
        var curEnd = IntArray(width + 1)
        var curId = IntArray(width + 1)
        var curCount: Int

        for (y in 0 until height) {
            curCount = 0
            var x = 0
            val row = y * width
            while (x < width) {
                while (x < width && !isWhite(pixels[row + x])) x++
                if (x >= width) break
                val start = x
                while (x < width && isWhite(pixels[row + x])) x++
                val end = x - 1
                val id = runs.add(start, end, y)
                curStart[curCount] = start
                curEnd[curCount] = end
                curId[curCount] = id
                curCount++
            }

            var i = 0
            var j = 0
            while (i < curCount && j < prevCount) {
                if (curEnd[i] < prevStart[j]) {
                    i++
                } else if (prevEnd[j] < curStart[i]) {
                    j++
                } else {
                    runs.union(prevId[j], curId[i])
                    if (curEnd[i] < prevEnd[j]) i++ else j++
                }
            }

            val swapStart = prevStart
            prevStart = curStart
            curStart = swapStart
            val swapEnd = prevEnd
            prevEnd = curEnd
            curEnd = swapEnd
            val swapId = prevId
            prevId = curId
            curId = swapId
            prevCount = curCount
        }

        return runs.components()
    }

    private fun isWhite(pixel: Int): Boolean {
        return ((pixel shr 16) and 0xFF) >= WHITE_LEVEL &&
                ((pixel shr 8) and 0xFF) >= WHITE_LEVEL &&
                (pixel and 0xFF) >= WHITE_LEVEL
    }

    /**
     * Groups letter-shaped components that sit on the same baseline into words, left to right.
     */
    private fun groupWords(letters: List<Component>): List<List<Component>> {
        val sorted = letters.sortedBy { it.x0 }
        val words = mutableListOf<MutableList<Component>>()
        for (letter in sorted) {
            var placed = false
            for (word in words) {
                val last = word.last()
                val overlap = min(last.y1, letter.y1) - max(last.y0, letter.y0) + 1
                if (overlap < WORD_MIN_VERTICAL_OVERLAP * min(last.height, letter.height)) continue
                if (max(last.height, letter.height).toFloat() / min(last.height, letter.height) >
                    WORD_MAX_HEIGHT_RATIO
                ) {
                    continue
                }
                val gap = letter.x0 - last.x1 - 1
                if (gap < -1 || gap > WORD_MAX_GAP_FRACTION * last.height) continue
                word.add(letter)
                placed = true
                break
            }
            if (!placed) words.add(mutableListOf(letter))
        }
        return words
    }

    private class RingResult(
        val radius: Int,
        val contrast: Double,
        val coverage: Double,
        val score: Double,
    )

    /**
     * Searches for the brightest circular ring around [centerX]/[centerY] and scores it.
     *
     * For each candidate radius the circumference is sampled at [RING_STEPS] angles. Each sample
     * takes the brightest of three adjacent radii so a 1-2 px ring is not missed at higher
     * resolutions. The disk interior (half the radius) must be clearly darker than the ring.
     */
    private fun findRing(
        pixels: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        wordWidth: Int,
    ): RingResult? {
        val minRadius = (wordWidth * RING_MIN_RADIUS_FRACTION).toInt()
        val maxRadius = (wordWidth * RING_MAX_RADIUS_FRACTION).toInt()
        if (maxRadius <= minRadius) return null

        val ringValues = IntArray(RING_STEPS)
        val innerValues = IntArray(RING_STEPS)
        var best: RingResult? = null

        for (radius in minRadius..maxRadius) {
            var valid = 0
            for (angle in 0 until RING_STEPS) {
                var peak = -1
                var found = false
                for (dr in -1..1) {
                    val r = radius + dr
                    val x = (centerX + r * COS[angle]).roundToInt()
                    val y = (centerY + r * SIN[angle]).roundToInt()
                    if (x in 0 until width && y in 0 until height) {
                        val luminance = luminance(pixels[y * width + x])
                        if (!found || luminance > peak) {
                            peak = luminance
                            found = true
                        }
                    }
                }
                if (found) ringValues[valid++] = peak
            }
            if (valid < RING_STEPS * 3 / 5) break

            var innerCount = 0
            for (angle in 0 until RING_STEPS) {
                val x = (centerX + radius * 0.5f * COS[angle]).roundToInt()
                val y = (centerY + radius * 0.5f * SIN[angle]).roundToInt()
                if (x in 0 until width && y in 0 until height) {
                    innerValues[innerCount++] = luminance(pixels[y * width + x])
                }
            }
            if (innerCount == 0) continue

            Arrays.sort(innerValues, 0, innerCount)
            val innerMedian = innerValues[innerCount / 2]

            var bright = 0
            for (i in 0 until valid) {
                if (ringValues[i] > innerMedian + RING_BRIGHTNESS_DELTA) bright++
            }
            val coverage = bright.toDouble() / valid

            Arrays.sort(ringValues, 0, valid)
            val ringMedian = ringValues[valid / 2]

            val contrast = (ringMedian - innerMedian).toDouble()
            val score = contrast * coverage
            val bestSoFar = best
            if (bestSoFar == null || score > bestSoFar.score) {
                best = RingResult(radius, contrast, coverage, score)
            }
        }
        return best
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /**
     * Union-find over horizontal white runs. Each run records its own bounds/area; components are
     * aggregated once at the end by following each run to its root.
     */
    private class RunUnionFind {
        private var parent = IntArray(1024)
        private var area = IntArray(1024)
        private var minX = IntArray(1024)
        private var maxX = IntArray(1024)
        private var minY = IntArray(1024)
        private var maxY = IntArray(1024)
        private var size = 0

        fun add(x0: Int, x1: Int, y: Int): Int {
            if (size == parent.size) grow()
            val id = size++
            parent[id] = id
            area[id] = x1 - x0 + 1
            minX[id] = x0
            maxX[id] = x1
            minY[id] = y
            maxY[id] = y
            return id
        }

        fun union(a: Int, b: Int) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) parent[rootB] = rootA
        }

        private fun find(node: Int): Int {
            var root = node
            while (parent[root] != root) {
                parent[root] = parent[parent[root]]
                root = parent[root]
            }
            return root
        }

        fun components(): List<Component> {
            if (size == 0) return emptyList()
            val aggregatedArea = IntArray(size)
            val aggregatedMinX = IntArray(size) { Int.MAX_VALUE }
            val aggregatedMinY = IntArray(size) { Int.MAX_VALUE }
            val aggregatedMaxX = IntArray(size) { -1 }
            val aggregatedMaxY = IntArray(size) { -1 }

            for (id in 0 until size) {
                val root = find(id)
                aggregatedArea[root] += area[id]
                if (minX[id] < aggregatedMinX[root]) aggregatedMinX[root] = minX[id]
                if (minY[id] < aggregatedMinY[root]) aggregatedMinY[root] = minY[id]
                if (maxX[id] > aggregatedMaxX[root]) aggregatedMaxX[root] = maxX[id]
                if (maxY[id] > aggregatedMaxY[root]) aggregatedMaxY[root] = maxY[id]
            }

            val result = ArrayList<Component>()
            for (id in 0 until size) {
                if (aggregatedArea[id] == 0) continue
                result.add(
                    Component(
                        aggregatedMinX[id],
                        aggregatedMinY[id],
                        aggregatedMaxX[id],
                        aggregatedMaxY[id],
                        aggregatedArea[id],
                    )
                )
            }
            return result
        }

        private fun grow() {
            val capacity = parent.size * 2
            parent = parent.copyOf(capacity)
            area = area.copyOf(capacity)
            minX = minX.copyOf(capacity)
            maxX = maxX.copyOf(capacity)
            minY = minY.copyOf(capacity)
            maxY = maxY.copyOf(capacity)
        }
    }
}
