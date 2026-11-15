package com.randomwalk.app.debug

import android.content.Context
import android.util.Log
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.location.LocationSample
import com.randomwalk.app.location.LocationSampler

/**
 * Debug-only device harness: logs accepted fixes so the 5 s / 5 m request can
 * be checked with adb logcat. It adds no view and is never started from a
 * non-debuggable build.
 */
class LocationDebugProbe(context: Context) {
    private val appContext = context.applicationContext
    private var sampler: LocationSampler? = null
    private var edgeStart: LatLon? = null

    fun start() {
        if (sampler != null) return
        val created = LocationSampler(
            context = appContext,
            onAccepted = ::onSample,
            onRejected = { reason -> Log.d(TAG, "sample_rejected reason=$reason") },
        )
        created.start()
        sampler = created
    }

    fun stop() {
        sampler?.stop()
        sampler = null
        edgeStart = null
    }

    private fun onSample(sample: LocationSample) {
        val point = LatLon(sample.latitude, sample.longitude)
        val start = edgeStart
        if (start == null) {
            edgeStart = point
            Log.d(TAG, "edge_start lat=${point.latitude} lon=${point.longitude}")
            return
        }

        Log.d(
            TAG,
            "sample lat=${point.latitude} lon=${point.longitude} " +
                "acc=${sample.accuracyMeters}",
        )
    }

    private companion object {
        const val TAG = "LocationDebugProbe"
    }
}
