package com.randomwalk.app.data

import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.geo.TileGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePlannerTest {

    @Test
    fun requiredTilesReturnsNineDistinctTilesNearTileCenter() {
        val center = TileGrid.tileFor(LatLon(0.001, 0.001)).center

        val tiles = TilePlanner.requiredTiles(center)

        assertEquals(9, tiles.size)
    }

    @Test
    fun requiredTilesContainsCenterTile() {
        val center = LatLon(-33.8688, 151.2093)

        val tiles = TilePlanner.requiredTiles(center)

        assertTrue(TileGrid.tileFor(center).id in tiles)
    }

    @Test
    fun boundsOfContainsEveryTileCenter() {
        val tileIds = setOf(
            TileGrid.tileFor(LatLon(0.001, 0.001)).id,
            TileGrid.tileFor(LatLon(-0.001, -0.001)).id,
        )

        val bounds = TilePlanner.boundsOf(tileIds)

        tileIds.forEach { id ->
            val tileBounds = TileGrid.tileBounds(id)
            assertTrue(bounds.contains(LatLon(tileBounds.centerLatitude, tileBounds.centerLongitude)))
        }
    }

    @Test
    fun tilesCoveringEdgeAcrossBoundaryReturnsBothTiles() {
        val westPoint = LatLon(0.001, -0.0001)
        val eastPoint = LatLon(0.001, 0.0001)
        val westTile = TileGrid.tileFor(westPoint).id
        val eastTile = TileGrid.tileFor(eastPoint).id
        assertTrue(westTile != eastTile)

        val tiles = TilePlanner.tilesCovering(listOf(westPoint, eastPoint))

        assertTrue(westTile in tiles)
        assertTrue(eastTile in tiles)
    }

    @Test
    fun tilesCoveringLongSegmentSamplesIntermediateTiles() {
        val tiles = TilePlanner.tilesCovering(listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.06)))

        val expected = setOf(
            TileGrid.tileFor(LatLon(0.0, 0.01)).id,
            TileGrid.tileFor(LatLon(0.0, 0.025)).id,
            TileGrid.tileFor(LatLon(0.0, 0.04)).id,
            TileGrid.tileFor(LatLon(0.0, 0.055)).id,
        )
        assertTrue(expected.all { it in tiles })
    }
}
