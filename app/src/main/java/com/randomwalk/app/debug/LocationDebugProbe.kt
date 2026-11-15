package com.randomwalk.app.debug

import android.content.Context
import android.util.Log
import com.randomwalk.app.geo.EdgeProgressTracker
import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.geo.PolylineProjection
import com.randomwalk.app.location.LocationSample
import com.randomwalk.app.location.LocationSampler
import com.randomwalk.app.location.LocationSamplingConfig
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug-only device harness: logs accepted fixes so a 1 s / 1 m request can
 * be checked with adb logcat. It adds no view and is never started from a
 * non-debuggable build.
 */
class LocationDebugProbe(context: Context) {
    private val appContext = context.applicationContext
    private var sampler: LocationSampler? = null
    private var edgeStart: LatLon? = null
    private var edgeProgress: EdgeProgressTracker? = null

    fun start() {
        if (sampler != null) return
        val created = LocationSampler(
            context = appContext,
            config = LocationSamplingConfig.DEBUG,
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
        edgeProgress = null
    }

    private fun onSample(sample: LocationSample) {
        val point = LatLon(sample.latitude, sample.longitude)
        val progress = edgeProgress
        if (progress == null) {
            val start = edgeStart
            if (start == null) {
                edgeStart = point
                Log.d(TAG, "edge_start lat=${point.latitude} lon=${point.longitude}")
                return
            }

            if (GeoMath.haversineMeters(start, point) < MIN_EDGE_SAMPLE_METERS) return

            val bearing = GeoMath.initialBearingDegrees(start, point)
            val radians = Math.toRadians(bearing)
            val end = GeoMath.offsetMeters(
                origin = start,
                eastMeters = sin(radians) * DEBUG_EDGE_METERS,
                northMeters = cos(radians) * DEBUG_EDGE_METERS,
            )
            val created = EdgeProgressTracker(listOf(start, end))
            edgeProgress = created
            Log.d(TAG, "edge_ready bearing=$bearing length_m=$DEBUG_EDGE_METERS")
            logProgress(created.update(point))
            return
        }

        Log.d(
            TAG,
            "sample lat=${point.latitude} lon=${point.longitude} " +
                "acc=${sample.accuracyMeters}",
        )
        logProgress(progress.update(point))
    }

    private fun logProgress(projection: PolylineProjection) {
        Log.d(
            TAG,
            "edge_progress progress_m=${projection.progressMeters} " +
                "fraction=${projection.progressFraction} " +
                "deviation_m=${projection.deviationMeters}",
        )
    }

    private companion object {
        const val TAG = "LocationDebugProbe"
        const val MIN_EDGE_SAMPLE_METERS = 3.0
        const val DEBUG_EDGE_METERS = 200.0
    }
}
