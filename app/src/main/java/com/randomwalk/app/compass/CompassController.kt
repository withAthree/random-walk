package com.randomwalk.app.compass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

class CompassController(
    context: Context,
    targetBearingDegrees: Float,
    private val onArrowAngleDegrees: (Float) -> Unit,
) : SensorEventListener, WalkCompass {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private val smoother = HeadingSmoother()
    private val targetTracker = CompassTargetTracker(targetBearingDegrees)
    private var registered = false
    private var lastTimestampNanos = 0L
    private var lowAccuracy = false

    override fun start() {
        if (registered) return
        val sensor = rotationVector
        if (sensor == null) {
            Log.w(TAG, "TYPE_ROTATION_VECTOR unavailable; arrow stays at its last angle")
            return
        }
        smoother.reset()
        targetTracker.resetHeading()
        lastTimestampNanos = 0L
        lowAccuracy = false
        registered = sensorManager.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_GAME,
        )
        if (!registered) Log.w(TAG, "registerListener returned false")
    }

    override fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
        targetTracker.resetHeading()
        lastTimestampNanos = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        val rawBearing = CompassMath.forwardBearingDegrees(rotationMatrix)
        val deltaSeconds = if (lastTimestampNanos == 0L) {
            0f
        } else {
            (event.timestamp - lastTimestampNanos) / 1_000_000_000f
        }
        lastTimestampNanos = event.timestamp

        val heading = smoother.update(rawBearing, deltaSeconds, lowAccuracy)
        onArrowAngleDegrees(targetTracker.onHeading(heading))
    }

    override fun updateTargetBearing(degrees: Float) {
        targetTracker.updateTargetBearing(degrees)?.let(onArrowAngleDegrees)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return
        lowAccuracy = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
    }

    private companion object {
        const val TAG = "CompassController"
    }
}
