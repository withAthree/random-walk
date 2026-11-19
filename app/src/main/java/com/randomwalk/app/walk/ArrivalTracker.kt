package com.randomwalk.app.walk

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.location.LocationSample

class ArrivalTracker(
    private val radiusMeters: Double = 10.0,
    private val dwellMillis: Long = 1_500L,
) {
    private var insideSinceMillis: Long? = null

    fun update(target: LatLon, sample: LocationSample): Boolean {
        val position = LatLon(sample.latitude, sample.longitude)
        val distance = GeoMath.haversineMeters(position, target)
        if (distance > radiusMeters) {
            insideSinceMillis = null
            return false
        }
        val since = insideSinceMillis
        if (since == null) {
            insideSinceMillis = sample.timestampMillis
            return false
        }
        return sample.timestampMillis - since >= dwellMillis
    }

    fun reset() {
        insideSinceMillis = null
    }
}
