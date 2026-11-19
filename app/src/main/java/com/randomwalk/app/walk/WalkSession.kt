package com.randomwalk.app.walk

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.location.LocationSample
import kotlin.math.roundToInt

data class WalkState(
    val leg: WalkLeg? = null,
    val remainingMeters: Int? = null,
    val targetBearingDegrees: Float? = null,
) {
    val waitingForLocation: Boolean get() = leg == null
}

class WalkSession(
    private val generator: LegGenerator,
    private val arrivalTracker: ArrivalTracker = ArrivalTracker(),
    private val onUpdate: (WalkState) -> Unit = {},
) {
    private var currentLeg: WalkLeg? = null
    private var currentPosition: LatLon? = null

    var state: WalkState = WalkState()
        private set

    fun onLocation(sample: LocationSample) {
        val position = LatLon(sample.latitude, sample.longitude)
        currentPosition = position
        val leg = currentLeg
        if (leg == null) {
            startLeg(position)
            return
        }
        if (arrivalTracker.update(leg.target, sample)) {
            startLeg(position)
            return
        }
        publish(position, leg)
    }

    private fun startLeg(position: LatLon) {
        val leg = generator.next(position, excludedBearings = emptyList())
        currentLeg = leg
        arrivalTracker.reset()
        publish(position, leg)
    }

    private fun publish(position: LatLon, leg: WalkLeg) {
        state = WalkState(
            leg = leg,
            remainingMeters = GeoMath.haversineMeters(position, leg.target).roundToInt(),
            targetBearingDegrees = GeoMath.initialBearingDegrees(position, leg.target).toFloat(),
        )
        onUpdate(state)
    }
}
