package com.randomwalk.app.data

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.geo.TileBounds
import com.randomwalk.app.geo.TileGrid
import kotlin.math.ceil
import kotlin.math.max

/** Selects and bounds [TileGrid] tiles for walks and edge geometry. */
object TilePlanner {
    private const val SAMPLE_STEP_METERS = TileGrid.TILE_SIZE_METERS / 4.0

    private val OFFSETS_METERS = doubleArrayOf(-TileGrid.TILE_SIZE_METERS, 0.0, TileGrid.TILE_SIZE_METERS)

    /** The center tile plus its 8 neighbors (3x3 block). */
    fun requiredTiles(center: LatLon): Set<Long> {
        val tiles = HashSet<Long>()
        for (east in OFFSETS_METERS) {
            for (north in OFFSETS_METERS) {
                tiles.add(TileGrid.tileFor(GeoMath.offsetMeters(center, east, north)).id)
            }
        }
        return tiles
    }

    /** Union of the bounds of the given tiles; the set must not be empty. */
    fun boundsOf(tileIds: Set<Long>): TileBounds {
        require(tileIds.isNotEmpty()) { "tileIds must not be empty" }
        var south = Double.POSITIVE_INFINITY
        var west = Double.POSITIVE_INFINITY
        var north = Double.NEGATIVE_INFINITY
        var east = Double.NEGATIVE_INFINITY
        for (tileId in tileIds) {
            val bounds = TileGrid.tileBounds(tileId)
            south = minOf(south, bounds.south)
            west = minOf(west, bounds.west)
            north = maxOf(north, bounds.north)
            east = maxOf(east, bounds.east)
        }
        return TileBounds(south, west, north, east)
    }

    /** Every tile touched by [points], sampling long segments every 500 m. */
    fun tilesCovering(points: List<LatLon>): Set<Long> {
        val tiles = HashSet<Long>()
        if (points.isEmpty()) return tiles

        for (point in points) {
            tiles.add(TileGrid.tileFor(point).id)
        }
        for (index in 0 until points.lastIndex) {
            val from = points[index]
            val to = points[index + 1]
            val meters = GeoMath.haversineMeters(from, to)
            val steps = max(1, ceil(meters / SAMPLE_STEP_METERS).toInt())
            for (step in 1 until steps) {
                val fraction = step.toDouble() / steps
                tiles.add(TileGrid.tileFor(interpolate(from, to, fraction)).id)
            }
        }
        return tiles
    }

    private fun interpolate(from: LatLon, to: LatLon, fraction: Double): LatLon {
        val latitude = from.latitude + (to.latitude - from.latitude) * fraction
        val longitudeDelta = GeoMath.normalizeLongitudeDegrees(to.longitude - from.longitude)
        val longitude = GeoMath.normalizeLongitudeDegrees(from.longitude + longitudeDelta * fraction)
        return LatLon(latitude, longitude)
    }
}
