package com.randomwalk.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.randomwalk.app.permission.LocationPermissionGate

class LocationSampler(
    context: Context,
    private val filter: LocationFilter = LocationFilter(),
    private val onAccepted: (LocationSample) -> Unit,
    private val onRejected: (LocationRejection) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)
    private var running = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::handle)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (!LocationPermissionGate.hasPreciseLocation(appContext)) {
            Log.w(TAG, "precise location not granted; sampler not started")
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            INTERVAL_MILLIS,
        )
            .setMinUpdateIntervalMillis(INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
            .setWaitForAccurateLocation(false)
            .build()

        filter.reset()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            running = true
        } catch (error: SecurityException) {
            Log.w(TAG, "location permission revoked before request", error)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            client.removeLocationUpdates(callback)
        } catch (error: SecurityException) {
            Log.w(TAG, "location permission already revoked", error)
        }
    }

    private fun handle(location: Location) {
        val sample = LocationSample(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.toDouble(),
            timestampMillis = location.time,
        )
        when (val decision = filter.evaluate(sample, clock())) {
            is LocationDecision.Accepted -> {
                Log.d(TAG, "accepted $sample")
                onAccepted(decision.sample)
            }

            is LocationDecision.Rejected -> {
                Log.d(TAG, "rejected reason=${decision.reason} sample=$sample")
                onRejected(decision.reason)
            }
        }
    }

    private companion object {
        const val TAG = "LocationSampler"
        const val INTERVAL_MILLIS = 5_000L
        const val MIN_DISTANCE_METERS = 5f
    }
}
