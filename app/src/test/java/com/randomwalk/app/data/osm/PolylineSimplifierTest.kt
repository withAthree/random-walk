package com.randomwalk.app.data.osm

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineSimplifierTest {

    @Test
    fun keepsEndpointsAndDropsVerticesCloserThan30m() {
        val points = (0..6).map {
            GeoMath.offsetMeters(LatLon(0.0, 0.0), eastMeters = it * 10.0, northMeters = 0.0)
        }
        val simplified = PolylineSimplifier(30.0).simplify(points)
        assertEquals(listOf(points[0], points[3], points[6]), simplified)
    }

    @Test
    fun keepsLastPointEvenWhenCloserThan30m() {
        val points = (0..7).map {
            GeoMath.offsetMeters(LatLon(0.0, 0.0), eastMeters = it * 10.0, northMeters = 0.0)
        }
        val simplified = PolylineSimplifier(30.0).simplify(points)
        assertEquals(listOf(points[0], points[3], points[6], points[7]), simplified)
    }

    @Test
    fun everyOutputPointIsInTheInputAndEndpointsAreUnchanged() {
        val points = (0..9).map {
            GeoMath.offsetMeters(LatLon(1.0, 2.0), eastMeters = it * 13.0, northMeters = it * 5.0)
        }
        val simplified = PolylineSimplifier().simplify(points)

        assertTrue(simplified.all { it in points })
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }

    @Test
    fun rejectsOnePointLine() {
        val single = listOf(LatLon(0.0, 0.0))
        assertThrows(IllegalArgumentException::class.java) {
            PolylineSimplifier().simplify(single)
        }
    }

    @Test
    fun rejectsNonPositiveMinSpacing() {
        assertThrows(IllegalArgumentException::class.java) { PolylineSimplifier(0.0) }
        assertThrows(IllegalArgumentException::class.java) { PolylineSimplifier(-1.0) }
    }
}
