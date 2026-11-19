package com.randomwalk.app.walk

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomLegGeneratorTest {
    private val origin = LatLon(48.8566, 2.3522)

    private fun angularDistance(a: Float, b: Float): Float {
        val delta = abs((a - b) % 360f)
        return if (delta > 180f) 360f - delta else delta
    }

    @Test
    fun sameSeedProducesSameLeg() {
        assertEquals(
            RandomLegGenerator(Random(7)).next(origin),
            RandomLegGenerator(Random(7)).next(origin),
        )
    }

    @Test
    fun bearingAndDistanceStayInConfiguredRange() {
        val generator = RandomLegGenerator(Random(11))
        repeat(200) {
            val leg = generator.next(origin)
            assertTrue(leg.bearingDegrees >= 0f && leg.bearingDegrees < 360f)
            assertTrue(leg.distanceMeters in 50..100)
        }
    }

    @Test
    fun targetLiesAtRequestedBearingAndDistance() {
        val leg = RandomLegGenerator(Random(11)).next(origin)
        val radians = Math.toRadians(leg.bearingDegrees.toDouble())
        val expected = GeoMath.offsetMeters(
            origin,
            eastMeters = sin(radians) * leg.distanceMeters,
            northMeters = cos(radians) * leg.distanceMeters,
        )
        assertEquals(expected.latitude, leg.target.latitude, 1e-6)
        assertEquals(expected.longitude, leg.target.longitude, 1e-6)
        assertEquals(
            leg.distanceMeters.toDouble(),
            GeoMath.haversineMeters(origin, leg.target),
            0.5,
        )
        assertEquals(
            leg.bearingDegrees.toDouble(),
            GeoMath.initialBearingDegrees(origin, leg.target),
            0.01,
        )
    }

    @Test
    fun excludedBearingsAreAvoided() {
        val generator = RandomLegGenerator(Random(23))
        repeat(200) {
            val leg = generator.next(origin, listOf(0f, 90f))
            assertTrue(angularDistance(leg.bearingDegrees, 0f) > 45f)
            assertTrue(angularDistance(leg.bearingDegrees, 90f) > 45f)
        }
    }

    @Test
    fun exclusionBandWrapsAcrossNorth() {
        val generator = RandomLegGenerator(Random(29))
        repeat(200) {
            val leg = generator.next(origin, listOf(350f))
            assertTrue(angularDistance(leg.bearingDegrees, 350f) > 45f)
        }
    }

    @Test
    fun allDirectionsExcludedDropsOldestAndStillDraws() {
        val leg = RandomLegGenerator(Random(31))
            .next(origin, listOf(0f, 90f, 180f, 270f))
        assertTrue(angularDistance(leg.bearingDegrees, 0f) <= 45f)
        assertTrue(leg.distanceMeters in 50..100)
    }
}
