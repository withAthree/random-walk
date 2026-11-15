package com.randomwalk.app.compass

import kotlin.math.exp

class HeadingSmoother(
    private val normalTimeConstantSeconds: Float = 0.2f,
    private val lowAccuracyTimeConstantSeconds: Float = 1.5f,
) {
    init {
        require(normalTimeConstantSeconds > 0f) { "normal time constant must be positive" }
        require(lowAccuracyTimeConstantSeconds > 0f) { "low-accuracy time constant must be positive" }
    }

    private var initialized = false
    private var heading = 0f

    fun update(rawBearingDegrees: Float, deltaSeconds: Float, lowAccuracy: Boolean): Float {
        if (!initialized) {
            heading = CompassMath.normalizeBearingDegrees(rawBearingDegrees)
            initialized = true
            return heading
        }
        if (deltaSeconds <= 0f) return heading

        val tau = if (lowAccuracy) lowAccuracyTimeConstantSeconds else normalTimeConstantSeconds
        val alpha = 1f - exp(-deltaSeconds / tau)
        val delta = CompassMath.shortestDeltaDegrees(heading, rawBearingDegrees)
        heading = CompassMath.normalizeBearingDegrees(heading + alpha * delta)
        return heading
    }

    fun reset() {
        initialized = false
        heading = 0f
    }
}
