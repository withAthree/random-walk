package com.randomwalk.app.compass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HeadingSmootherTest {

    @Test
    fun firstUpdateReturnsRaw() {
        val smoother = HeadingSmoother()
        assertEquals(42f, smoother.update(42f, 0f, lowAccuracy = false), 0.01f)
    }

    @Test
    fun firstUpdateNormalizesRaw() {
        val smoother = HeadingSmoother()
        assertEquals(10f, smoother.update(370f, 0f, lowAccuracy = false), 0.01f)
    }

    @Test
    fun rejectsNonPositiveTimeConstants() {
        assertThrows(IllegalArgumentException::class.java) {
            HeadingSmoother(normalTimeConstantSeconds = 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeadingSmoother(lowAccuracyTimeConstantSeconds = -1f)
        }
    }

    @Test
    fun crossesNorthByShortestArc() {
        val smoother = HeadingSmoother()
        smoother.update(350f, 0.2f, lowAccuracy = false)
        val result = smoother.update(10f, 0.2f, lowAccuracy = false)
        assertBearingEquals(2.64f, result)
    }

    @Test
    fun crossesNorthBackwardsByShortestArc() {
        val smoother = HeadingSmoother()
        smoother.update(10f, 0.2f, lowAccuracy = false)
        val result = smoother.update(350f, 0.2f, lowAccuracy = false)
        assertBearingEquals(357.36f, result)
    }

    @Test
    fun lowAccuracyMovesSlower() {
        val normal = HeadingSmoother()
        val low = HeadingSmoother()
        normal.update(0f, 0.2f, lowAccuracy = false)
        low.update(0f, 0.2f, lowAccuracy = false)

        val normalResult = normal.update(90f, 0.2f, lowAccuracy = false)
        val lowResult = low.update(90f, 0.2f, lowAccuracy = true)

        assertEquals(56.9f, normalResult, 0.2f)
        assertEquals(11.2f, lowResult, 0.2f)
        assertTrue("low accuracy must lag behind normal", lowResult < normalResult)
    }

    @Test
    fun resetStartsFromRaw() {
        val smoother = HeadingSmoother()
        smoother.update(0f, 0.2f, lowAccuracy = false)
        smoother.update(90f, 0.2f, lowAccuracy = false)
        smoother.reset()
        assertEquals(350f, smoother.update(350f, 0.2f, lowAccuracy = false), 0.01f)
    }

    @Test
    fun nonPositiveDeltaKeepsHeading() {
        val smoother = HeadingSmoother()
        smoother.update(10f, 0.2f, lowAccuracy = false)
        assertEquals(10f, smoother.update(200f, 0f, lowAccuracy = false), 0.01f)
        assertEquals(10f, smoother.update(200f, -0.5f, lowAccuracy = false), 0.01f)
    }

    private fun assertBearingEquals(expected: Float, actual: Float) {
        val diff = abs(((actual - expected + 540f) % 360f) - 180f)
        assertTrue("expected=$expected actual=$actual diff=$diff", diff <= 0.1f)
    }
}
