package com.randomwalk.app.walk

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.location.LocationSample
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSessionTest {
    private val origin = LatLon(48.8566, 2.3522)

    private fun sample(point: LatLon, atMillis: Long) =
        LocationSample(point.latitude, point.longitude, 5.0, atMillis)

    private fun legAt(
        bearingDegrees: Float,
        from: LatLon = origin,
        distanceMeters: Int = 100,
    ): WalkLeg {
        val radians = Math.toRadians(bearingDegrees.toDouble())
        return WalkLeg(
            target = GeoMath.offsetMeters(
                from,
                eastMeters = sin(radians) * distanceMeters,
                northMeters = cos(radians) * distanceMeters,
            ),
            bearingDegrees = bearingDegrees,
            distanceMeters = distanceMeters,
        )
    }

    private class RecordingGenerator(vararg legs: WalkLeg) : LegGenerator {
        data class Call(val from: LatLon, val excludedBearings: List<Float>)

        val calls = mutableListOf<Call>()
        private val legs = legs.toList()
        private var index = 0

        override fun next(from: LatLon, excludedBearings: List<Float>): WalkLeg {
            calls += Call(from, excludedBearings)
            return legs[minOf(index++, legs.lastIndex)]
        }
    }

    @Test
    fun waitsUntilAUsableLocationArrives() {
        val updates = mutableListOf<WalkState>()
        val session = WalkSession(RecordingGenerator(legAt(0f)), onUpdate = updates::add)
        assertTrue(session.state.waitingForLocation)
        assertNull(session.state.leg)
        assertNull(session.state.remainingMeters)
        assertTrue(updates.isEmpty())
    }

    @Test
    fun firstAcceptedLocationStartsALeg() {
        val generator = RecordingGenerator(legAt(0f))
        val session = WalkSession(generator)
        session.onLocation(sample(origin, 0L))

        assertEquals(origin, generator.calls.single().from)
        assertEquals(emptyList<Float>(), generator.calls.single().excludedBearings)
        assertEquals(legAt(0f), session.state.leg)
        assertTrue(!session.state.waitingForLocation)
        assertEquals(100, session.state.remainingMeters)
        assertEquals(0f, session.state.targetBearingDegrees!!, 0.5f)
    }

    @Test
    fun remainingDistanceIncreasesWhenWalkingAway() {
        val session = WalkSession(RecordingGenerator(legAt(0f)))
        session.onLocation(sample(origin, 0L))
        session.onLocation(sample(GeoMath.offsetMeters(origin, 0.0, -20.0), 5_000L))
        assertEquals(120, session.state.remainingMeters)
    }

    @Test
    fun targetBearingFollowsTheWalker() {
        val session = WalkSession(RecordingGenerator(legAt(0f)))
        session.onLocation(sample(origin, 0L))
        session.onLocation(sample(GeoMath.offsetMeters(origin, 10.0, 0.0), 5_000L))
        assertEquals(354.29, session.state.targetBearingDegrees!!.toDouble(), 0.2)
    }

    @Test
    fun arrivalStartsTheNextLegFromTheCurrentPosition() {
        val northLeg = legAt(0f)
        val eastLeg = legAt(90f, from = northLeg.target)
        val generator = RecordingGenerator(northLeg, eastLeg)
        val session = WalkSession(generator)

        session.onLocation(sample(origin, 0L))
        session.onLocation(sample(northLeg.target, 500L))
        session.onLocation(sample(northLeg.target, 2_000L))

        assertEquals(2, generator.calls.size)
        assertEquals(northLeg.target, generator.calls.last().from)
        assertEquals(eastLeg, session.state.leg)
    }

    @Test
    fun shortVisitDoesNotStartTheNextLeg() {
        val northLeg = legAt(0f)
        val generator = RecordingGenerator(northLeg, legAt(90f))
        val session = WalkSession(generator)

        session.onLocation(sample(origin, 0L))
        session.onLocation(sample(northLeg.target, 500L))
        session.onLocation(sample(GeoMath.offsetMeters(northLeg.target, 30.0, 0.0), 1_200L))

        assertEquals(1, generator.calls.size)
        assertEquals(northLeg, session.state.leg)
    }

    @Test
    fun atTheTargetTheRemainingDistanceIsZeroAndTheBearingIsFinite() {
        val northLeg = legAt(0f)
        val session = WalkSession(RecordingGenerator(northLeg, legAt(90f)))

        session.onLocation(sample(origin, 0L))
        session.onLocation(sample(northLeg.target, 500L))

        assertEquals(0, session.state.remainingMeters)
        assertTrue(session.state.targetBearingDegrees!!.isFinite())
        assertEquals(northLeg, session.state.leg)
    }
}
