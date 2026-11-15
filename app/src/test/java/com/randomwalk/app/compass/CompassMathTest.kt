package com.randomwalk.app.compass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class CompassMathTest {

    @Test
    fun pitchDoesNotChangeBearing() {
        for (pitch in listOf(0f, 30f, 45f, 60f, 90f)) {
            assertBearingEquals(
                37f,
                CompassMath.forwardBearingDegrees(rotationMatrix(pitch, 0f, 37f)),
                "pitch=$pitch",
            )
        }
    }

    @Test
    fun rollAboutForwardAxisDoesNotChangeBearing() {
        for (pitch in listOf(0f, 30f, 45f, 60f, 90f)) {
            for (roll in listOf(-60f, -30f, 0f, 30f, 60f)) {
                assertBearingEquals(
                    123f,
                    CompassMath.forwardBearingDegrees(rotationMatrix(pitch, roll, 123f)),
                    "pitch=$pitch roll=$roll",
                )
            }
        }
    }

    @Test
    fun bearingWrapsAcrossNorth() {
        assertBearingEquals(
            350f,
            CompassMath.forwardBearingDegrees(rotationMatrix(45f, 0f, 350f)),
        )
        assertBearingEquals(
            10f,
            CompassMath.forwardBearingDegrees(rotationMatrix(45f, 0f, 10f)),
        )
    }

    @Test
    fun degenerateHoldFallsBackToProjectedY() {
        // Device Y is north and device Z is east: pitch is undefined, but the
        // horizontal forward direction is still north.
        val matrix = floatArrayOf(
            0f, 0f, 1f,
            0f, 1f, 0f,
            -1f, 0f, 0f,
        )
        assertBearingEquals(0f, CompassMath.forwardBearingDegrees(matrix), "edge-on hold")
    }

    @Test
    fun normalizeWrapsIntoZeroTo360() {
        assertEquals(350f, CompassMath.normalizeBearingDegrees(-10f), 1e-4f)
        assertEquals(0f, CompassMath.normalizeBearingDegrees(360f), 1e-4f)
        assertEquals(0f, CompassMath.normalizeBearingDegrees(0f), 1e-4f)
    }

    @Test
    fun shortestDeltaTakesShortestArc() {
        assertEquals(20f, CompassMath.shortestDeltaDegrees(350f, 10f), 1e-4f)
        assertEquals(-20f, CompassMath.shortestDeltaDegrees(10f, 350f), 1e-4f)
        assertEquals(180f, CompassMath.shortestDeltaDegrees(0f, 180f), 1e-4f)
        assertEquals(180f, CompassMath.shortestDeltaDegrees(180f, 0f), 1e-4f)
    }

    private fun assertBearingEquals(expected: Float, actual: Float, message: String = "") {
        val diff = abs(((actual - expected + 540f) % 360f) - 180f)
        assertTrue("$message expected=$expected actual=$actual diff=$diff", diff <= 1f)
    }

    /**
     * Builds a row-major device-to-world matrix. At pitch=0/roll=0/yaw=0:
     * X=east, Y=north, Z=up. Positive pitch raises device Y toward world up.
     * Roll rotates X/Y/Z about the current forward direction.
     */
    private fun rotationMatrix(pitchDeg: Float, rollDeg: Float, yawDeg: Float): FloatArray {
        val p = Math.toRadians(pitchDeg.toDouble())
        val r = Math.toRadians(rollDeg.toDouble())
        val yaw = Math.toRadians(yawDeg.toDouble())
        val forward = doubleArrayOf(sin(yaw), cos(yaw), 0.0)
        val right = doubleArrayOf(cos(yaw), -sin(yaw), 0.0)
        val up = doubleArrayOf(0.0, 0.0, 1.0)

        fun rotateAboutForward(v: DoubleArray): DoubleArray {
            val c = cos(r)
            val s = sin(r)
            val dot = forward[0] * v[0] + forward[1] * v[1] + forward[2] * v[2]
            val cross = doubleArrayOf(
                forward[1] * v[2] - forward[2] * v[1],
                forward[2] * v[0] - forward[0] * v[2],
                forward[0] * v[1] - forward[1] * v[0],
            )
            return doubleArrayOf(
                v[0] * c + cross[0] * s + forward[0] * dot * (1 - c),
                v[1] * c + cross[1] * s + forward[1] * dot * (1 - c),
                v[2] * c + cross[2] * s + forward[2] * dot * (1 - c),
            )
        }

        val y0 = doubleArrayOf(
            cos(p) * forward[0] + sin(p) * up[0],
            cos(p) * forward[1] + sin(p) * up[1],
            cos(p) * forward[2] + sin(p) * up[2],
        )
        val z0 = doubleArrayOf(
            -sin(p) * forward[0] + cos(p) * up[0],
            -sin(p) * forward[1] + cos(p) * up[1],
            -sin(p) * forward[2] + cos(p) * up[2],
        )
        val x = rotateAboutForward(right)
        val yAxis = rotateAboutForward(y0)
        val z = rotateAboutForward(z0)
        return floatArrayOf(
            x[0].toFloat(), yAxis[0].toFloat(), z[0].toFloat(),
            x[1].toFloat(), yAxis[1].toFloat(), z[1].toFloat(),
            x[2].toFloat(), yAxis[2].toFloat(), z[2].toFloat(),
        )
    }
}
