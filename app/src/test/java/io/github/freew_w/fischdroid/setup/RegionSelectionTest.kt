package io.github.freew_w.fischdroid.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionSelectionTest {
    @Test
    fun between_topLeftThenBottomRight_isInclusive() {
        val region = RegionSelection.between(PixelPoint(10, 20), PixelPoint(19, 24))

        assertEquals(10, region.x)
        assertEquals(20, region.y)
        assertEquals(10, region.width)
        assertEquals(5, region.height)
    }

    @Test
    fun between_reversedCorners_isNormalised() {
        val region = RegionSelection.between(PixelPoint(180, 900), PixelPoint(100, 800))

        assertEquals(100, region.x)
        assertEquals(800, region.y)
        assertEquals(81, region.width)
        assertEquals(101, region.height)
    }

    @Test
    fun between_adjacentDiagonalPixels_isTwoByTwo() {
        val region = RegionSelection.between(PixelPoint(5, 5), PixelPoint(6, 6))

        assertEquals(2, region.width)
        assertEquals(2, region.height)
    }

    @Test
    fun between_samePixel_isOneByOne() {
        val region = RegionSelection.between(PixelPoint(42, 7), PixelPoint(42, 7))

        assertEquals(42, region.x)
        assertEquals(7, region.y)
        assertEquals(1, region.width)
        assertEquals(1, region.height)
    }

    @Test
    fun toIntArray_matchesFieldOrder() {
        val values = RegionSelection(1, 2, 3, 4).toIntArray()

        assertEquals(listOf(1, 2, 3, 4), values.toList())
    }
}
