package com.randomwalk.app.geo

import kotlin.math.cos
import kotlin.math.floor

data class TileBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    val centerLatitude: Double get() = (south + north) / 2.0
    val centerLongitude: Double get() = (west + east) / 2.0

    /** Half-open: the north and east edges belong to the next tile. */
    fun contains(point: LatLon): Boolean =
        point.latitude >= south && point.latitude < north &&
            point.longitude >= west && point.longitude < east
}

data class Tile(val id: Long, val bounds: TileBounds) {
    val center: LatLon get() = LatLon(bounds.centerLatitude, bounds.centerLongitude)
}

object TileGrid {
    const val TILE_SIZE_METERS = 2_000.0
    const val LAT_STEP_DEGREES = TILE_SIZE_METERS / GeoMath.METERS_PER_DEGREE_LAT

    private const val MAX_GRID_LATITUDE = 85.0

    fun tileFor(point: LatLon): Tile {
        val id = tileIdAt(point.latitude, point.longitude)
        return Tile(id, tileBounds(id))
    }

    fun tileIdAt(latitude: Double, longitude: Double): Long {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "latitude out of range: $latitude"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "longitude out of range: $longitude"
        }

        val latitudeIndex = floor(latitude / LAT_STEP_DEGREES).toInt()
        val longitudeIndex = floor(
            GeoMath.normalizeLongitudeDegrees(longitude) /
                longitudeStepDegrees(latitudeIndex),
        ).toInt()
        return encode(latitudeIndex, longitudeIndex)
    }

    fun tileBounds(tileId: Long): TileBounds {
        val latitudeIndex = (tileId shr 32).toInt()
        val longitudeIndex = tileId.toInt()
        val step = longitudeStepDegrees(latitudeIndex)

        return TileBounds(
            south = (latitudeIndex * LAT_STEP_DEGREES).coerceIn(-90.0, 90.0),
            west = (longitudeIndex * step).coerceIn(-180.0, 180.0),
            north = ((latitudeIndex + 1) * LAT_STEP_DEGREES).coerceIn(-90.0, 90.0),
            east = ((longitudeIndex + 1) * step).coerceIn(-180.0, 180.0),
        )
    }

    private fun longitudeStepDegrees(latitudeIndex: Int): Double {
        val centerLatitude = ((latitudeIndex + 0.5) * LAT_STEP_DEGREES)
            .coerceIn(-MAX_GRID_LATITUDE, MAX_GRID_LATITUDE)
        return LAT_STEP_DEGREES / cos(Math.toRadians(centerLatitude))
    }

    private fun encode(latitudeIndex: Int, longitudeIndex: Int): Long =
        (latitudeIndex.toLong() shl 32) or
            (longitudeIndex.toLong() and 0xFFFF_FFFFL)
}
