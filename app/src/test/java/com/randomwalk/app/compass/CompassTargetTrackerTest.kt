package com.randomwalk.app.compass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompassTargetTrackerTest {

    @Test
    fun changingTheTargetMovesTheArrowWithoutANewHeading() {
        val tracker = CompassTargetTracker(0f)
        assertEquals(-30f, tracker.onHeading(30f), 0.01f)
        assertEquals(60f, tracker.updateTargetBearing(90f)!!, 0.01f)
        assertEquals(90f, tracker.targetBearingDegrees, 0.01f)
    }

    @Test
    fun targetUpdateBeforeAnyHeadingEmitsNothing() {
        val tracker = CompassTargetTracker(0f)
        assertNull(tracker.updateTargetBearing(90f))
    }

    @Test
    fun targetBearingIsNormalized() {
        val tracker = CompassTargetTracker(-10f)
        assertEquals(350f, tracker.targetBearingDegrees, 0.01f)

        tracker.onHeading(350f)
        assertEquals(10f, tracker.updateTargetBearing(360f)!!, 0.01f)
        assertEquals(0f, tracker.targetBearingDegrees, 0.01f)
    }

    @Test
    fun worldFixedBearingWrapsAcrossNorth() {
        val tracker = CompassTargetTracker(350f)
        assertEquals(-20f, tracker.onHeading(10f), 0.01f)
    }

    @Test
    fun resetHeadingStopsImmediateUpdatesUntilTheNextHeading() {
        val tracker = CompassTargetTracker(0f)
        tracker.onHeading(30f)
        tracker.resetHeading()

        assertNull(tracker.updateTargetBearing(90f))
        assertEquals(0f, tracker.onHeading(90f), 0.01f)
    }
}
