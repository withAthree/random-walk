package com.randomwalk.app.location

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import kotlin.math.max

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val timestampMillis: Long,
)

enum class LocationRejection {
    INVALID_COORDINATES,
    POOR_ACCURACY,
    STALE,
    TELEPORT,
}

sealed interface LocationDecision {
    data class Accepted(val sample: LocationSample) : LocationDecision
    data class Rejected(val reason: LocationRejection) : LocationDecision
}

class LocationFilter(
    private val maxAccuracyMeters: Double = 50.0,
    private val maxAgeMillis: Long = 10_000,
    private val maxSpeedMetersPerSecond: Double = 3.0,
    private val teleportGraceMeters: Double = 30.0,
) {
    private var lastAccepted: LocationSample? = null
    private var lastTeleportCandidate: LocationSample? = null

    fun evaluate(sample: LocationSample, nowMillis: Long): LocationDecision {
        if (!sample.latitude.isFinite() || sample.latitude !in -90.0..90.0 ||
            !sample.longitude.isFinite() || sample.longitude !in -180.0..180.0
        ) {
            return LocationDecision.Rejected(LocationRejection.INVALID_COORDINATES)
        }
        if (!sample.accuracyMeters.isFinite() || sample.accuracyMeters < 0.0 ||
            sample.accuracyMeters > maxAccuracyMeters
        ) {
            return LocationDecision.Rejected(LocationRejection.POOR_ACCURACY)
        }
        if (nowMillis - sample.timestampMillis > maxAgeMillis) {
            return LocationDecision.Rejected(LocationRejection.STALE)
        }

        val references = listOfNotNull(lastAccepted, lastTeleportCandidate)
        if (references.isNotEmpty()) {
            val plausible = references.any { reference ->
                val seconds = max(
                    0.0,
                    (sample.timestampMillis - reference.timestampMillis) / 1000.0,
                )
                val allowed = maxSpeedMetersPerSecond * seconds + teleportGraceMeters
                val distance = GeoMath.haversineMeters(
                    LatLon(reference.latitude, reference.longitude),
                    LatLon(sample.latitude, sample.longitude),
                )
                distance <= allowed
            }
            if (!plausible) {
                lastTeleportCandidate = sample
                return LocationDecision.Rejected(LocationRejection.TELEPORT)
            }
        }

        lastAccepted = sample
        lastTeleportCandidate = null
        return LocationDecision.Accepted(sample)
    }

    fun reset() {
        lastAccepted = null
        lastTeleportCandidate = null
    }
}
