package com.randomwalk.app.compass

class CompassTargetTracker(targetBearingDegrees: Float) {

    var targetBearingDegrees: Float = CompassMath.normalizeBearingDegrees(targetBearingDegrees)
        private set

    private var headingDegrees: Float? = null

    fun onHeading(rawHeadingDegrees: Float): Float {
        val heading = CompassMath.normalizeBearingDegrees(rawHeadingDegrees)
        headingDegrees = heading
        return CompassMath.arrowAngleDegrees(targetBearingDegrees, heading)
    }

    fun updateTargetBearing(degrees: Float): Float? {
        targetBearingDegrees = CompassMath.normalizeBearingDegrees(degrees)
        return headingDegrees?.let { CompassMath.arrowAngleDegrees(targetBearingDegrees, it) }
    }

    fun resetHeading() {
        headingDegrees = null
    }
}
