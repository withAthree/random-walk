package com.randomwalk.app.walk

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.location.LocationSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalTrackerTest {
    private val target = LatLon(48.8566, 2.3522)

    private fun sampleAt(eastMeters: Double, northMeters: Double, timeMillis: Long): LocationSample {
        val point = GeoMath.offsetMeters(target, eastMeters, northMeters)
        return LocationSample(point.latitude, point.longitude, 5.0, timeMillis)
    }

    @Test
    fun arrivesAfterContinuousTenMeterDwellForOneAndAHalfSeconds() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(target, sampleAt(9.0, 0.0, 0L)))
        assertFalse(tracker.update(target, sampleAt(9.0, 0.0, 1_000L)))
        assertTrue(tracker.update(target, sampleAt(9.0, 0.0, 1_500L)))
    }

    @Test
    fun leavingTheRadiusResetsTheDwell() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(target, sampleAt(0.0, 0.0, 0L)))
        assertFalse(tracker.update(target, sampleAt(20.0, 0.0, 1_200L)))
        assertFalse(tracker.update(target, sampleAt(5.0, 0.0, 1_500L)))
        assertFalse(tracker.update(target, sampleAt(5.0, 0.0, 2_500L)))
        assertTrue(tracker.update(target, sampleAt(5.0, 0.0, 3_000L)))
    }

    @Test
    fun shortPassThroughDoesNotArrive() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(target, sampleAt(0.0, 0.0, 0L)))
        assertFalse(tracker.update(target, sampleAt(30.0, 0.0, 1_000L)))
        assertFalse(tracker.update(target, sampleAt(40.0, 0.0, 2_000L)))
    }

    @Test
    fun movingInsideTheRadiusKeepsTheDwell() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(target, sampleAt(9.0, 0.0, 0L)))
        assertFalse(tracker.update(target, sampleAt(-9.0, 0.0, 800L)))
        assertTrue(tracker.update(target, sampleAt(0.0, 9.0, 1_500L)))
    }

    @Test
    fun outOfOrderTimestampsDoNotArriveEarly() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(target, sampleAt(0.0, 0.0, 1_000L)))
        assertFalse(tracker.update(target, sampleAt(0.0, 0.0, 500L)))
        assertFalse(tracker.update(target, sampleAt(0.0, 0.0, 2_499L)))
        assertTrue(tracker.update(target, sampleAt(0.0, 0.0, 2_500L)))
    }

    @Test
    fun resetDropsTheCurrentDwell() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(target, sampleAt(0.0, 0.0, 0L)))
        tracker.reset()
        assertFalse(tracker.update(target, sampleAt(0.0, 0.0, 1_500L)))
        assertTrue(tracker.update(target, sampleAt(0.0, 0.0, 3_000L)))
    }
}
