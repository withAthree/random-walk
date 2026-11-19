package com.randomwalk.app.walk

import com.randomwalk.app.geo.LatLon

data class WalkLeg(
    val target: LatLon,
    val bearingDegrees: Float,
    val distanceMeters: Int,
)
