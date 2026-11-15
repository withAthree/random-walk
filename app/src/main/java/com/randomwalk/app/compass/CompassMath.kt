package com.randomwalk.app.compass

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object CompassMath {

    /**
     * Bearing in [0, 360), clockwise from north, of the device's walking-forward
     * direction. [rotationMatrix] is the row-major matrix returned by
     * SensorManager.getRotationMatrixFromVector: rows (east, north, up),
     * columns device (X, Y, Z).
     */
    fun forwardBearingDegrees(rotationMatrix: FloatArray): Float {
        require(rotationMatrix.size == 9) { "rotationMatrix must have 9 elements" }

        val yEast = rotationMatrix[1]
        val yNorth = rotationMatrix[4]
        val yUp = rotationMatrix[7]
        val zEast = rotationMatrix[2]
        val zNorth = rotationMatrix[5]
        val zUp = rotationMatrix[8]

        // Edge-on hold: device Y and Z are both horizontal, so pitch is undefined.
        // Fall back to the horizontal projection of device Y rather than returning NaN.
        if (hypot(yUp, zUp) < 1e-4f) {
            return bearingOf(yEast, yNorth)
        }

        // Pitch of device Y relative to the screen normal. This form is
        // invariant under roll about the forward axis; asin(yUp) is not.
        val pitch = atan2(yUp, zUp)
        val forwardEast = cos(pitch) * yEast - sin(pitch) * zEast
        val forwardNorth = cos(pitch) * yNorth - sin(pitch) * zNorth
        return bearingOf(forwardEast, forwardNorth)
    }

    fun normalizeBearingDegrees(degrees: Float): Float {
        val normalized = degrees % 360f
        return if (normalized < 0f) normalized + 360f else normalized
    }

    /** Signed shortest rotation from [fromDegrees] to [toDegrees], in (-180, 180]. */
    fun shortestDeltaDegrees(fromDegrees: Float, toDegrees: Float): Float {
        val delta = normalizeBearingDegrees(toDegrees - fromDegrees + 180f) - 180f
        return if (delta <= -180f) delta + 360f else delta
    }

    /**
     * On-screen arrow angle in degrees: how far clockwise the arrow must rotate
     * from up to point toward [targetBearingDegrees] when the device heading is
     * [deviceHeadingDegrees]. Positive values rotate the Canvas clockwise.
     */
    fun arrowAngleDegrees(targetBearingDegrees: Float, deviceHeadingDegrees: Float): Float =
        shortestDeltaDegrees(deviceHeadingDegrees, targetBearingDegrees)

    private fun bearingOf(east: Float, north: Float): Float =
        normalizeBearingDegrees(Math.toDegrees(atan2(east, north).toDouble()).toFloat())
}
