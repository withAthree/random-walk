package com.randomwalk.app.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeProgressTrackerTest {

    private val edge = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.002)) // ~222.4 m east

    @Test
    fun progressIncreasesAlongStraightEdge() {
        val tracker = EdgeProgressTracker(edge)
        val fractions = listOf(0.0, 0.0005, 0.001, 0.0015, 0.002)
            .map { longitude -> tracker.update(LatLon(0.0, longitude)).progressFraction }

        assertEquals(0.0, fractions.first(), 1e-9)
        assertEquals(1.0, fractions.last(), 1e-9)
        for (index in 1 until fractions.size) {
            assertTrue("fractions=$fractions", fractions[index] > fractions[index - 1])
        }
    }

    @Test
    fun reportsDeviationAndProgressAtMidpoint() {
        val projection = EdgeProgressTracker(edge).update(LatLon(0.0005, 0.0005))

        assertEquals(0.25, projection.progressFraction, 0.001)
        assertEquals(55.6, projection.deviationMeters, 0.5)
    }

    @Test
    fun progressSaturatesAtEnd() {
        val projection = EdgeProgressTracker(edge).update(LatLon(0.0, 0.003))

        assertEquals(1.0, projection.progressFraction, 1e-9)
    }
}
