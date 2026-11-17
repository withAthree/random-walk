package com.randomwalk.app.data.osm

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon

/** Collapses a polyline to a subset of its original points, keeping only vertices at least [minSpacingMeters] apart. */
class PolylineSimplifier(private val minSpacingMeters: Double = 30.0) {

    init {
        require(minSpacingMeters > 0.0) { "minSpacingMeters must be positive" }
    }

    /**
     * Returns a copy of [points] with the first and last points always kept and interior points
     * kept only when they are at least [minSpacingMeters] from the last kept point. Every output
     * point is an element of [points].
     */
    fun simplify(points: List<LatLon>): List<LatLon> {
        require(points.size >= 2) { "polyline must have at least two points" }

        val kept = mutableListOf(points.first())
        for (index in 1 until points.lastIndex) {
            val point = points[index]
            if (GeoMath.haversineMeters(kept.last(), point) >= minSpacingMeters) {
                kept.add(point)
            }
        }
        if (kept.last() != points.last()) {
            kept.add(points.last())
        }
        return kept
    }
}
