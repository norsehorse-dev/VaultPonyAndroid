package dev.norsehorse.vaultpony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CameraZoomTest {

    @Test
    fun singleLensBackCamera() {
        assertEquals(listOf(1f, 2f, 5f), CameraZoom.steps(1f, 8f))
    }

    @Test
    fun ultrawideThroughTheSameCamera() {
        assertEquals(listOf(0.5f, 1f, 2f, 5f, 10f), CameraZoom.steps(0.5f, 30f))
        assertEquals(listOf(0.6f, 1f, 2f), CameraZoom.steps(0.6f, 4f))
    }

    @Test
    fun stepsStayInsideTheRange() {
        for ((min, max) in listOf(1f to 1f, 1f to 1.9f, 1f to 2f, 0.5f to 4.99f, 1f to 10f, 1.2f to 6f)) {
            val steps = CameraZoom.steps(min, max)
            assertTrue(steps.all { it >= min && it <= max + 0.001f })
            assertEquals(steps.sorted().distinct(), steps)
        }
    }

    @Test
    fun noChoiceWithoutZoom() {
        assertEquals(listOf(1f), CameraZoom.steps(1f, 1f))
        assertEquals(listOf(1f), CameraZoom.steps(1f, 1.9f))
        assertEquals(emptyList<Float>(), CameraZoom.steps(0f, 0f))
        assertEquals(emptyList<Float>(), CameraZoom.steps(Float.NaN, 4f))
    }

    @Test
    fun numbersFollowTheLocale() {
        assertEquals("1", CameraZoom.number(1f, Locale.US))
        assertEquals("0.5", CameraZoom.number(0.5f, Locale.US))
        assertEquals("2.3", CameraZoom.number(2.34f, Locale.US))
        assertEquals("0,6", CameraZoom.number(0.6f, Locale.GERMANY))
        assertEquals("10", CameraZoom.number(9.96f, Locale.US))
    }
}
