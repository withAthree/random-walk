package com.randomwalk.app.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {

    @Test
    fun haversineMatchesOneDegreeAtEquator() {
        assertEquals(
            111_194.93,
            GeoMath.haversineMeters(LatLon(0.0, 0.0), LatLon(0.0, 1.0)),
            0.5,
        )
        assertEquals(
            111_194.93,
            GeoMath.haversineMeters(LatLon(0.0, 0.0), LatLon(1.0, 0.0)),
            0.5,
        )
    }

    @Test
    fun bearingCardinalDirections() {
        val origin = LatLon(0.0, 0.0)
        assertEquals(0.0, GeoMath.initialBearingDegrees(origin, LatLon(1.0, 0.0)), 0.01)
        assertEquals(90.0, GeoMath.initialBearingDegrees(origin, LatLon(0.0, 1.0)), 0.01)
        assertEquals(180.0, GeoMath.initialBearingDegrees(origin, LatLon(-1.0, 0.0)), 0.01)
        assertEquals(270.0, GeoMath.initialBearingDegrees(origin, LatLon(0.0, -1.0)), 0.01)
    }

    @Test
    fun longitudeNormalizationWrapsAt180() {
        assertEquals(0.0, GeoMath.normalizeLongitudeDegrees(360.0), 1e-9)
        assertEquals(-180.0, GeoMath.normalizeLongitudeDegrees(180.0), 1e-9)
        assertEquals(170.0, GeoMath.normalizeLongitudeDegrees(-190.0), 1e-9)
    }

    @Test
    fun offsetMetersIsMetricAtEquatorAndHighLatitude() {
        val equator = LatLon(0.0, 0.0)
        assertEquals(
            100.0,
            GeoMath.haversineMeters(equator, GeoMath.offsetMeters(equator, 100.0, 0.0)),
            0.2,
        )
        assertEquals(
            100.0,
            GeoMath.haversineMeters(equator, GeoMath.offsetMeters(equator, 0.0, 100.0)),
            0.2,
        )

        val highLatitude = LatLon(60.0, 10.0)
        assertEquals(
            100.0,
            GeoMath.haversineMeters(
                highLatitude,
                GeoMath.offsetMeters(highLatitude, 100.0, 0.0),
            ),
            0.2,
        )
    }

    @Test
    fun projectionReturnsPointProgressAndDeviation() {
        val edge = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.002)) // ~222.4 m east
        val projection = GeoMath.projectOntoPolyline(LatLon(0.0005, 0.0005), edge)

        assertEquals(0, projection.segmentIndex)
        assertEquals(0.25, projection.segmentFraction, 0.001)
        assertEquals(0.25, projection.progressFraction, 0.001)
        assertEquals(55.6, projection.deviationMeters, 0.5)
        assertEquals(0.0, projection.point.latitude, 1e-6)
        assertEquals(0.0005, projection.point.longitude, 1e-6)
    }

    @Test
    fun pointBeyondEndClampsToEnd() {
        val edge = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001))
        val projection = GeoMath.projectOntoPolyline(LatLon(0.0, 0.002), edge)

        assertEquals(1.0, projection.progressFraction, 1e-9)
        assertEquals(edge.last().latitude, projection.point.latitude, 1e-9)
        assertEquals(edge.last().longitude, projection.point.longitude, 1e-9)
    }

    @Test
    fun pointBeforeStartClampsToStart() {
        val edge = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001))
        val projection = GeoMath.projectOntoPolyline(LatLon(0.0, -0.001), edge)

        assertEquals(0.0, projection.progressFraction, 1e-9)
        assertEquals(edge.first().latitude, projection.point.latitude, 1e-9)
        assertEquals(edge.first().longitude, projection.point.longitude, 1e-9)
    }

    @Test
    fun overlappingVerticesAreSkipped() {
        val edge = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.0), LatLon(0.0, 0.001))
        val projection = GeoMath.projectOntoPolyline(LatLon(0.0005, 0.0005), edge)

        assertEquals(1, projection.segmentIndex)
        assertEquals(0.5, projection.progressFraction, 0.001)
        assertEquals(55.6, projection.deviationMeters, 0.5)
    }

    @Test
    fun zeroLengthPolylineProjectsToItsOnlyPoint() {
        val edge = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.0))
        val projection = GeoMath.projectOntoPolyline(LatLon(0.001, 0.001), edge)

        assertEquals(0.0, projection.progressMeters, 1e-9)
        assertEquals(0.0, projection.progressFraction, 1e-9)
        assertEquals(edge.first().latitude, projection.point.latitude, 1e-9)
        assertEquals(edge.first().longitude, projection.point.longitude, 1e-9)
        assertEquals(157.2, projection.deviationMeters, 1.0)
    }

    @Test
    fun reversedPolylineMeasuresProgressFromNewFirstVertex() {
        val forward = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001))
        val reversed = forward.reversed()
        val point = LatLon(0.0, 0.00025)

        assertEquals(0.25, GeoMath.projectOntoPolyline(point, forward).progressFraction, 1e-9)
        assertEquals(0.75, GeoMath.projectOntoPolyline(point, reversed).progressFraction, 1e-9)
        assertEquals(270.0, GeoMath.initialBearingDegrees(reversed.first(), reversed.last()), 0.01)
    }

    @Test
    fun singlePointPolylineIsSafe() {
        val projection = GeoMath.projectOntoPolyline(
            LatLon(0.001, 0.0),
            listOf(LatLon(0.0, 0.0)),
        )

        assertEquals(0.0, projection.progressFraction, 1e-9)
        assertEquals(111.2, projection.deviationMeters, 0.5)
    }
}
