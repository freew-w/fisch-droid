package io.github.freew_w.fischdroid.control

import io.github.freew_w.fischdroid.detection.Bar
import io.github.freew_w.fischdroid.detection.Fish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BarControllerTest {
    private val tuning = ControlTuning(positionGain = 4f, velocityGain = 3.2f, switchBand = 50f)

    @Test
    fun warmUpReturnsNullUntilEnoughSamples() {
        val controller = BarController()

        assertNull(controller.decide(Fish(100), Bar(100, 100), 0L, tuning))
        assertNull(controller.decide(Fish(100), Bar(100, 100), 16L, tuning))
        assertNotNull(controller.decide(Fish(100), Bar(100, 100), 32L, tuning))
    }

    @Test
    fun holdsWhenFishIsToTheRight() {
        val controller = BarController()
        controller.decide(Fish(100), Bar(100, 100), 0L, tuning)
        controller.decide(Fish(300), Bar(100, 100), 16L, tuning)

        assertEquals(true, controller.decide(Fish(300), Bar(100, 100), 32L, tuning))
    }

    @Test
    fun releasesWhenFishIsToTheLeft() {
        val controller = BarController()
        controller.decide(Fish(100), Bar(100, 100), 0L, tuning)
        controller.decide(Fish(100), Bar(100, 100), 16L, tuning)

        assertEquals(false, controller.decide(Fish(100), Bar(100, 100), 32L, tuning))
    }

    @Test
    fun resetStartsTheWarmUpAgain() {
        val controller = BarController()
        controller.decide(Fish(100), Bar(100, 100), 0L, tuning)
        controller.decide(Fish(300), Bar(100, 100), 16L, tuning)
        assertNotNull(controller.decide(Fish(300), Bar(100, 100), 32L, tuning))

        controller.reset()

        assertNull(controller.decide(Fish(300), Bar(100, 100), 48L, tuning))
    }
}
