package io.github.freew_w.fischdroid

import io.github.freew_w.fischdroid.shake.Detector
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Host-side tests for [io.github.freew_w.fischdroid.shake.Detector] using real captures from three devices.
 *
 * The sample files under `src/test/resources/shake` are 1:1 crops of the button, so each one is
 * pasted into a landscape frame of a realistic size before detection. That keeps the detector's
 * size bounds honest: the crops themselves are zoomed in compared to the real screen.
 */
class DetectorTest {
    private val canvasWidth = 2400
    private val canvasHeight = 1065
    private val pasteX = 700
    private val pasteY = 300

    private class Sample(
        val file: String,
        val expectedCenterX: Int,
        val expectedCenterY: Int,
        val expectedRadius: Int,
    )

    private val samples = listOf(
        // Device pixels from the originals; centers/radii were measured independently.
        Sample("physical_shake1_obsured.png", 238, 217, 176),
        Sample("physical_shake2.png", 212, 200, 176),
        Sample("physical_shake3.png", 166, 172, 162),
        Sample("waydroid_shake1.png", 73, 76, 70),
        Sample("waydroid_shake2.png", 79, 77, 77),
        Sample("waydroid_shake3.png", 144, 151, 123),
    )

    @Test
    fun findsShakeButtonInEveryDeviceCapture() {
        for (sample in samples) {
            val button =
                Detector.find(pasteIntoCanvas(load(sample.file)), canvasWidth, canvasHeight)
            assertNotNull("${sample.file} was not detected", button)

            assertTrue(
                "${sample.file} center x was ${button!!.centerX}, expected ${sample.expectedCenterX + pasteX}",
                abs(button.centerX - (sample.expectedCenterX + pasteX)) <= 12,
            )
            assertTrue(
                "${sample.file} center y was ${button.centerY}, expected ${sample.expectedCenterY + pasteY}",
                abs(button.centerY - (sample.expectedCenterY + pasteY)) <= 12,
            )
            assertTrue(
                "${sample.file} radius was ${button.radius}, expected ${sample.expectedRadius}",
                abs(button.radius - sample.expectedRadius) <= maxOf(10, sample.expectedRadius / 5),
            )
        }
    }

    /**
     * The in-game jump button is also a dark disk with a bright ring. It must not be tapped.
     */
    @Test
    fun rejectsRingedJumpButton() {
        val button = Detector.find(
            pasteIntoCanvas(load("jump_button_negative.png")), canvasWidth, canvasHeight
        )
        assertNull("jump button must not be mistaken for the shake button", button)
    }

    @Test
    fun rejectsFrameWithoutButton() {
        val blank = IntArray(canvasWidth * canvasHeight) { 0xFF14213D.toInt() }
        assertNull(Detector.find(blank, canvasWidth, canvasHeight))
    }

    @Test
    fun ignoresPortraitFrames() {
        val portraitWidth = 1080
        val portraitHeight = 2400
        val blank = IntArray(portraitWidth * portraitHeight) { 0xFF14213D.toInt() }
        assertNull(Detector.find(blank, portraitWidth, portraitHeight))
    }

    private fun load(name: String): BufferedImage {
        val stream = requireNotNull(javaClass.getResourceAsStream("/shake/$name")) {
            "missing test resource /shake/$name"
        }
        return stream.use { ImageIO.read(it) }
    }

    /** Draws the crop onto a plain landscape frame and returns packed ARGB pixels. */
    private fun pasteIntoCanvas(crop: BufferedImage): IntArray {
        val canvas = IntArray(canvasWidth * canvasHeight) { 0xFF14213D.toInt() }
        val width = crop.width
        val height = crop.height
        val pixels = IntArray(width * height)
        crop.getRGB(0, 0, width, height, pixels, 0, width)
        for (y in 0 until height) {
            System.arraycopy(pixels, y * width, canvas, (pasteY + y) * canvasWidth + pasteX, width)
        }
        return canvas
    }
}
