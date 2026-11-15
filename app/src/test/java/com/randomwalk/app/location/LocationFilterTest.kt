package com.randomwalk.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFilterTest {

    @Test
    fun acceptsValidFirstFix() {
        val decision = LocationFilter().evaluate(
            LocationSample(31.2304, 121.4737, 8.0, 1_000),
            nowMillis = 2_000,
        )
        assertTrue(decision is LocationDecision.Accepted)
    }

    @Test
    fun acceptsAccuracyAtLimit() {
        val decision = LocationFilter().evaluate(
            LocationSample(0.0, 0.0, 50.0, 1_000),
            nowMillis = 2_000,
        )
        assertTrue(decision is LocationDecision.Accepted)
    }

    @Test
    fun rejectsAccuracyWorseThanLimit() {
        val decision = LocationFilter().evaluate(
            LocationSample(0.0, 0.0, 50.1, 1_000),
            nowMillis = 2_000,
        )
        assertEquals(
            LocationRejection.POOR_ACCURACY,
            (decision as LocationDecision.Rejected).reason,
        )
    }

    @Test
    fun acceptsFixAtAgeLimit() {
        val decision = LocationFilter().evaluate(
            LocationSample(0.0, 0.0, 5.0, 0),
            nowMillis = 10_000,
        )
        assertTrue(decision is LocationDecision.Accepted)
    }

    @Test
    fun rejectsStaleFix() {
        val decision = LocationFilter().evaluate(
            LocationSample(0.0, 0.0, 5.0, 0),
            nowMillis = 10_001,
        )
        assertEquals(
            LocationRejection.STALE,
            (decision as LocationDecision.Rejected).reason,
        )
    }

    @Test
    fun rejectsInvalidCoordinates() {
        val filter = LocationFilter()

        for (latitude in listOf(91.0, -91.0, Double.NaN)) {
            val decision = filter.evaluate(
                LocationSample(latitude, 0.0, 5.0, 1_000),
                nowMillis = 1_000,
            )
            assertEquals(
                LocationRejection.INVALID_COORDINATES,
                (decision as LocationDecision.Rejected).reason,
            )
        }
        for (longitude in listOf(181.0, -181.0, Double.NaN)) {
            val decision = filter.evaluate(
                LocationSample(0.0, longitude, 5.0, 1_000),
                nowMillis = 1_000,
            )
            assertEquals(
                LocationRejection.INVALID_COORDINATES,
                (decision as LocationDecision.Rejected).reason,
            )
        }
    }

    @Test
    fun acceptsNormalWalkingStep() {
        val filter = LocationFilter()
        assertTrue(
            filter.evaluate(LocationSample(0.0, 0.0, 5.0, 0), 0) is LocationDecision.Accepted,
        )

        val next = LocationSample(0.00005, 0.0, 5.0, 5_000) // ~5.6 m in 5 s
        assertTrue(filter.evaluate(next, 5_000) is LocationDecision.Accepted)
    }

    @Test
    fun rejectsTeleport() {
        val filter = LocationFilter()
        assertTrue(
            filter.evaluate(LocationSample(0.0, 0.0, 5.0, 0), 0) is LocationDecision.Accepted,
        )

        val jump = LocationSample(0.001, 0.0, 5.0, 5_000) // ~111 m in 5 s
        val decision = filter.evaluate(jump, 5_000)
        assertEquals(
            LocationRejection.TELEPORT,
            (decision as LocationDecision.Rejected).reason,
        )
    }

    @Test
    fun recoversAfterTeleportWhenNextFixIsConsistent() {
        val filter = LocationFilter()
        filter.evaluate(LocationSample(0.0, 0.0, 5.0, 0), 0)
        assertTrue(
            filter.evaluate(LocationSample(0.001, 0.0, 5.0, 5_000), 5_000) is LocationDecision.Rejected,
        )

        val next = LocationSample(0.0011, 0.0, 5.0, 10_000) // ~11 m from rejected fix
        assertTrue(filter.evaluate(next, 10_000) is LocationDecision.Accepted)
    }

    @Test
    fun resetForgetsHistory() {
        val filter = LocationFilter()
        filter.evaluate(LocationSample(0.0, 0.0, 5.0, 0), 0)
        filter.reset()

        val far = LocationSample(0.001, 0.0, 5.0, 5_000)
        assertTrue(filter.evaluate(far, 5_000) is LocationDecision.Accepted)
    }
}
