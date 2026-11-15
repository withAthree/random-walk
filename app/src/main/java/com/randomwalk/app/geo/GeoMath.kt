package com.randomwalk.app.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    const val EARTH_RADIUS_METERS = 6_371_008.8
    const val METERS_PER_DEGREE_LAT = 111_194.93

    fun haversineMeters(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(normalizeLongitudeDegrees(b.longitude - a.longitude))
        val h = sin(dLat / 2.0).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLon / 2.0).let { it * it }
        return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }

    fun initialBearingDegrees(from: LatLon, to: LatLon): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(
            normalizeLongitudeDegrees(to.longitude - from.longitude),
        )
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return if (x == 0.0 && y == 0.0) 0.0 else {
            normalizeBearingDegrees(Math.toDegrees(atan2(y, x)))
        }
    }

    fun normalizeBearingDegrees(degrees: Double): Double {
        val normalized = degrees % 360.0
        return if (normalized < 0.0) normalized + 360.0 else normalized
    }

    fun normalizeLongitudeDegrees(longitude: Double): Double {
        var normalized = longitude % 360.0
        if (normalized >= 180.0) normalized -= 360.0
        if (normalized < -180.0) normalized += 360.0
        return normalized
    }

    fun offsetMeters(origin: LatLon, eastMeters: Double, northMeters: Double): LatLon {
        val latitude = (origin.latitude + northMeters / METERS_PER_DEGREE_LAT)
            .coerceIn(-90.0, 90.0)
        val longitude = normalizeLongitudeDegrees(
            origin.longitude + eastMeters / metersPerDegreeLongitude(origin.latitude),
        )
        return LatLon(latitude, longitude)
    }

    fun projectOntoPolyline(point: LatLon, polyline: List<LatLon>): PolylineProjection {
        require(polyline.isNotEmpty()) { "polyline must not be empty" }

        if (polyline.size == 1) {
            return PolylineProjection(
                point = polyline[0],
                segmentIndex = 0,
                segmentFraction = 0.0,
                progressMeters = 0.0,
                progressFraction = 0.0,
                deviationMeters = haversineMeters(point, polyline[0]),
            )
        }

        val lengths = DoubleArray(polyline.lastIndex) { index ->
            haversineMeters(polyline[index], polyline[index + 1])
        }
        val prefix = DoubleArray(polyline.size)
        for (index in lengths.indices) {
            prefix[index + 1] = prefix[index] + lengths[index]
        }
        val totalLength = prefix.last()

        var bestIndex = 0
        var bestFraction = 0.0
        var bestEast = 0.0
        var bestNorth = 0.0
        var bestDeviation = Double.POSITIVE_INFINITY

        for (index in lengths.indices) {
            val a = toLocal(point, polyline[index])
            val b = toLocal(point, polyline[index + 1])
            val dx = b.east - a.east
            val dy = b.north - a.north
            val lengthSquared = dx * dx + dy * dy
            val fraction = if (lengthSquared <= 1e-12) {
                0.0
            } else {
                (((0.0 - a.east) * dx + (0.0 - a.north) * dy) / lengthSquared)
                    .coerceIn(0.0, 1.0)
            }
            val east = a.east + fraction * dx
            val north = a.north + fraction * dy
            val deviation = hypot(east, north)
            if (deviation < bestDeviation) {
                bestDeviation = deviation
                bestIndex = index
                bestFraction = fraction
                bestEast = east
                bestNorth = north
            }
        }

        val projected = offsetMeters(point, bestEast, bestNorth)
        val progress = if (totalLength == 0.0) {
            0.0
        } else {
            prefix[bestIndex] + bestFraction * lengths[bestIndex]
        }
        return PolylineProjection(
            point = projected,
            segmentIndex = bestIndex,
            segmentFraction = bestFraction,
            progressMeters = progress,
            progressFraction = if (totalLength == 0.0) 0.0 else progress / totalLength,
            deviationMeters = haversineMeters(point, projected),
        )
    }

    private fun toLocal(origin: LatLon, point: LatLon): LocalPoint {
        val east = normalizeLongitudeDegrees(point.longitude - origin.longitude) *
            metersPerDegreeLongitude(origin.latitude)
        val north = (point.latitude - origin.latitude) * METERS_PER_DEGREE_LAT
        return LocalPoint(east, north)
    }

    private fun metersPerDegreeLongitude(latitude: Double): Double =
        METERS_PER_DEGREE_LAT * max(cos(Math.toRadians(latitude)), 1e-6)

    private data class LocalPoint(val east: Double, val north: Double)
}

data class PolylineProjection(
    val point: LatLon,
    val segmentIndex: Int,
    val segmentFraction: Double,
    val progressMeters: Double,
    val progressFraction: Double,
    val deviationMeters: Double,
)
