package com.randomwalk.app.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGridTest {

    @Test
    fun originTileContainsOriginAndRoundTrips() {
        val tile = TileGrid.tileFor(LatLon(0.0, 0.0))

        assertTrue(tile.bounds.contains(LatLon(0.0, 0.0)))
        assertEquals(tile.id, TileGrid.tileIdAt(tile.center.latitude, tile.center.longitude))
        assertEquals(
            2000.0,
            GeoMath.haversineMeters(
                LatLon(tile.bounds.south, tile.center.longitude),
                LatLon(tile.bounds.north, tile.center.longitude),
            ),
            1.0,
        )
        assertEquals(
            2000.0,
            GeoMath.haversineMeters(
                LatLon(tile.center.latitude, tile.bounds.west),
                LatLon(tile.center.latitude, tile.bounds.east),
            ),
            1.0,
        )
    }

    @Test
    fun negativeCoordinatesRoundTrip() {
        val point = LatLon(-33.8688, 151.2093)
        val tile = TileGrid.tileFor(point)

        assertTrue(tile.bounds.contains(point))
        assertTrue(tile.bounds.south < 0.0)
        assertTrue(tile.bounds.west > 0.0)
        assertEquals(tile.id, TileGrid.tileIdAt(tile.center.latitude, tile.center.longitude))
    }

    @Test
    fun westNegativeCoordinatesRoundTrip() {
        val point = LatLon(48.8566, -2.3522)
        val tile = TileGrid.tileFor(point)

        assertTrue(tile.bounds.contains(point))
        assertTrue(tile.bounds.west < 0.0)
        assertEquals(tile.id, TileGrid.tileIdAt(tile.center.latitude, tile.center.longitude))
    }

    @Test
    fun highLatitudeTileKeepsMetricSize() {
        val point = LatLon(78.2232, 15.6469)
        val tile = TileGrid.tileFor(point)

        assertTrue(tile.bounds.contains(point))
        assertEquals(
            2000.0,
            GeoMath.haversineMeters(
                LatLon(tile.center.latitude, tile.bounds.west),
                LatLon(tile.center.latitude, tile.bounds.east),
            ),
            1.0,
        )
        assertEquals(
            2000.0,
            GeoMath.haversineMeters(
                LatLon(tile.bounds.south, tile.center.longitude),
                LatLon(tile.bounds.north, tile.center.longitude),
            ),
            1.0,
        )
        assertEquals(tile.id, TileGrid.tileIdAt(tile.center.latitude, tile.center.longitude))
    }

    @Test
    fun adjacentRowsHaveDifferentIds() {
        val current = TileGrid.tileIdAt(0.0, 0.0)
        val next = TileGrid.tileIdAt(TileGrid.LAT_STEP_DEGREES, 0.0)

        assertTrue(current != next)
    }
}
