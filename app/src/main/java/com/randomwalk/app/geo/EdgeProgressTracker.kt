package com.randomwalk.app.geo

class EdgeProgressTracker(private val edge: List<LatLon>) {
    init {
        require(edge.isNotEmpty()) { "edge must not be empty" }
    }

    fun update(position: LatLon): PolylineProjection =
        GeoMath.projectOntoPolyline(position, edge)
}
