# Compass Loop (Fake Data) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the loop `TYPE_ROTATION_VECTOR → world-fixed bearing → shortest-arc smoothing → Canvas arrow` against a fake target 1000 m due north, and add the one-time first-run hint.

**Architecture:** `CompassMath` is pure math: it turns a row-major device-to-world rotation matrix into a walking bearing and an on-screen arrow angle. `HeadingSmoother` is pure shortest-arc exponential smoothing. `CompassController` owns sensor registration and feeds `WalkScreenView`. `WalkSurface` composes the screen and the one-time hint; `MainActivity` temporarily swaps the permission gate for the walk surface once precise location is granted. No location fixes, route generation, start screen, or distance countdown in this batch.

**Tech Stack:** Kotlin 2.0.21, Android SDK 35 (minSdk 26), platform `SensorManager` / `Sensor.TYPE_ROTATION_VECTOR`, custom `View` + `Canvas`, JUnit 4, AndroidX Test (`ActivityScenario`, `GrantPermissionRule`). No new Gradle dependencies.

**Spec:** `openspec/changes/random-walk-app/design.md` (D2, D11), `openspec/changes/random-walk-app/tasks.md` section 2, `openspec/changes/random-walk-app/specs/walk-guidance/spec.md` (arrow and smoothing requirements).

## Global Constraints

- Section 2 is fake-data only: fixed target **1000 m due north** (`target bearing = 0°`), fixed remaining distance **1000 m**. Do not add location sampling, route generation, map rendering, start screen, or distance countdown.
- After precise location is granted, `MainActivity` shows the walk surface directly. This is temporary until tasks 6.1/6.2; keep the permission gate behavior for denied/approximate cases unchanged.
- The walk surface shows only the arrow and the distance figure. The 2.4 first-run hint is the only permitted text overlay.
- Direction source is nine-axis `Sensor.TYPE_ROTATION_VECTOR`. Do not substitute `TYPE_GAME_ROTATION_VECTOR`, raw magnetometer, or `TYPE_ORIENTATION`.
- Arrow is world-fixed: `screen angle = target bearing − device heading`, so the arrow points up when the user faces the target and the user turns their body, not the phone.
- `CompassMath.forwardBearingDegrees` consumes a row-major `FloatArray(9)` from `SensorManager.getRotationMatrixFromVector`: rows are `(east, north, up)`, columns are device `(X, Y, Z)`. Do not transpose it.
- Pitch is derived as `atan2(R[7], R[8])` (device Y-up divided by device Z-up), **not** `asin(R[7])`; the latter fails the roll-invariance test.
- Smoothing time constants: **0.2 s** normal, **1.5 s** when sensor accuracy is LOW or UNRELIABLE. The smoother must take the shortest arc across 0°/360°, and must keep responding while standing still (no freeze).
- The first-run hint copy is exactly `箭头朝上 = 你正对要去的方向，转身对齐它`, displayed for **3000 ms**, dismissed immediately by long press, and shown only once across app restarts.
- Lock `MainActivity` to portrait for this batch. The Canvas "up" axis is then always device +Y; display-rotation compensation is deferred until the real walk screen.
- Keep `com.randomwalk.app`, minSdk 26, compile/target 35, dark theme, immersive mode, and `FLAG_KEEP_SCREEN_ON`.
- Do not add Compose, AppCompat, Robolectric, or any new dependency.
- WSL builds require JDK 17: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2`. The default JDK 25 fails with `java.lang.IllegalArgumentException: 25`.
- WSL has no attached Android device (`adb devices` is empty). JVM tests and APK/androidTest compilation run in WSL; `connectedDebugAndroidTest` and the manual checks run on the Windows host.
- Mark tasks `[x]` in `openspec/changes/random-walk-app/tasks.md` only after the device checklist passes.

## Review Focus

- Phone held edge-on so device Y and Z are both horizontal → bearing stays finite, no `NaN`, arrow keeps rendering. Pinned by `degenerateHoldFallsBackToProjectedY` in Task 1.
- Heading crosses 0°/360° in either direction → smoother takes the short arc, never a full sweep. Pinned by both `crossesNorth*` tests in Task 2.
- Target is exactly opposite the heading (±180°) → arrow points down once, not up or undefined. Pinned by `arrowAngleAtOppositeBearing` in Task 3.
- Sensor pauses and resumes, or a frame gap arrives → smoother resets and does not blend a stale heading across the gap. Pinned by `resetStartsFromRaw` and `nonPositiveDeltaKeepsHeading` in Task 2; checked again by pausing/resuming on device.
- Hint is long-pressed away or the app is relaunched → it never appears a second time. Pinned by `FirstRunHintTest` and `WalkHintTest` in Task 4.

---

### Task 1: World-fixed bearing math (2.1)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/compass/CompassMath.kt`
- Test: `app/src/test/java/com/randomwalk/app/compass/CompassMathTest.kt`

**Interfaces:**
- Produces: `CompassMath.forwardBearingDegrees(rotationMatrix: FloatArray): Float`
- Produces: `CompassMath.normalizeBearingDegrees(degrees: Float): Float`
- Produces: `CompassMath.shortestDeltaDegrees(fromDegrees: Float, toDegrees: Float): Float`
- Consumes: row-major device-to-world matrix from `SensorManager.getRotationMatrixFromVector`.

- [ ] **Step 1: Write the failing bearing tests**

Create `CompassMathTest.kt`:

```kotlin
package com.randomwalk.app.compass

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class CompassMathTest {

    @Test
    fun pitchDoesNotChangeBearing() {
        for (pitch in listOf(0f, 30f, 45f, 60f, 90f)) {
            assertBearingEquals(
                37f,
                CompassMath.forwardBearingDegrees(rotationMatrix(pitch, 0f, 37f)),
                "pitch=$pitch",
            )
        }
    }

    @Test
    fun rollAboutForwardAxisDoesNotChangeBearing() {
        for (pitch in listOf(0f, 30f, 45f, 60f, 90f)) {
            for (roll in listOf(-60f, -30f, 0f, 30f, 60f)) {
                assertBearingEquals(
                    123f,
                    CompassMath.forwardBearingDegrees(rotationMatrix(pitch, roll, 123f)),
                    "pitch=$pitch roll=$roll",
                )
            }
        }
    }

    @Test
    fun bearingWrapsAcrossNorth() {
        assertBearingEquals(
            350f,
            CompassMath.forwardBearingDegrees(rotationMatrix(45f, 0f, 350f)),
        )
        assertBearingEquals(
            10f,
            CompassMath.forwardBearingDegrees(rotationMatrix(45f, 0f, 10f)),
        )
    }

    @Test
    fun degenerateHoldFallsBackToProjectedY() {
        // Device Y is north and device Z is east: pitch is undefined, but the
        // horizontal forward direction is still north.
        val matrix = floatArrayOf(
            0f, 0f, 1f,
            0f, 1f, 0f,
            -1f, 0f, 0f,
        )
        assertBearingEquals(0f, CompassMath.forwardBearingDegrees(matrix), "edge-on hold")
    }

    private fun assertBearingEquals(expected: Float, actual: Float, message: String = "") {
        val diff = abs(((actual - expected + 540f) % 360f) - 180f)
        assertTrue("$message expected=$expected actual=$actual diff=$diff", diff <= 1f)
    }

    /**
     * Builds a row-major device-to-world matrix. At pitch=0/roll=0/yaw=0:
     * X=east, Y=north, Z=up. Positive pitch raises device Y toward world up.
     * Roll rotates X/Y/Z about the current forward direction.
     */
    private fun rotationMatrix(pitchDeg: Float, rollDeg: Float, yawDeg: Float): FloatArray {
        val p = Math.toRadians(pitchDeg.toDouble())
        val r = Math.toRadians(rollDeg.toDouble())
        val yaw = Math.toRadians(yawDeg.toDouble())
        val forward = doubleArrayOf(sin(yaw), cos(yaw), 0.0)
        val right = doubleArrayOf(cos(yaw), -sin(yaw), 0.0)
        val up = doubleArrayOf(0.0, 0.0, 1.0)

        fun rotateAboutForward(v: DoubleArray): DoubleArray {
            val c = cos(r)
            val s = sin(r)
            val dot = forward[0] * v[0] + forward[1] * v[1] + forward[2] * v[2]
            val cross = doubleArrayOf(
                forward[1] * v[2] - forward[2] * v[1],
                forward[2] * v[0] - forward[0] * v[2],
                forward[0] * v[1] - forward[1] * v[0],
            )
            return doubleArrayOf(
                v[0] * c + cross[0] * s + forward[0] * dot * (1 - c),
                v[1] * c + cross[1] * s + forward[1] * dot * (1 - c),
                v[2] * c + cross[2] * s + forward[2] * dot * (1 - c),
            )
        }

        val y0 = doubleArrayOf(
            cos(p) * forward[0] + sin(p) * up[0],
            cos(p) * forward[1] + sin(p) * up[1],
            cos(p) * forward[2] + sin(p) * up[2],
        )
        val z0 = doubleArrayOf(
            -sin(p) * forward[0] + cos(p) * up[0],
            -sin(p) * forward[1] + cos(p) * up[1],
            -sin(p) * forward[2] + cos(p) * up[2],
        )
        val x = rotateAboutForward(right)
        val yAxis = rotateAboutForward(y0)
        val z = rotateAboutForward(z0)
        return floatArrayOf(
            x[0].toFloat(), yAxis[0].toFloat(), z[0].toFloat(),
            x[1].toFloat(), yAxis[1].toFloat(), z[1].toFloat(),
            x[2].toFloat(), yAxis[2].toFloat(), z[2].toFloat(),
        )
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.compass.CompassMathTest'
```

Expected: compilation fails with unresolved reference `CompassMath`.

- [ ] **Step 3: Implement `CompassMath`**

Create `CompassMath.kt`:

```kotlin
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

    private fun bearingOf(east: Float, north: Float): Float =
        normalizeBearingDegrees(Math.toDegrees(atan2(east, north).toDouble()).toFloat())
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run the same command. Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/randomwalk/app/compass/CompassMath.kt \
  app/src/test/java/com/randomwalk/app/compass/CompassMathTest.kt
git commit -m "feat: 实现世界固定方位合成 (Task 2.1)"
```

---

### Task 2: Shortest-arc heading smoothing (2.2)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/compass/HeadingSmoother.kt`
- Test: `app/src/test/java/com/randomwalk/app/compass/HeadingSmootherTest.kt`

**Interfaces:**
- Produces: `HeadingSmoother(normalTimeConstantSeconds: Float = 0.2f, lowAccuracyTimeConstantSeconds: Float = 1.5f)`
- Produces: `HeadingSmoother.update(rawBearingDegrees: Float, deltaSeconds: Float, lowAccuracy: Boolean): Float`
- Produces: `HeadingSmoother.reset()`
- Consumes: `CompassMath.normalizeBearingDegrees` and `CompassMath.shortestDeltaDegrees`.

- [ ] **Step 1: Write the failing smoother tests**

Create `HeadingSmootherTest.kt`:

```kotlin
package com.randomwalk.app.compass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HeadingSmootherTest {

    @Test
    fun firstUpdateReturnsRaw() {
        val smoother = HeadingSmoother()
        assertEquals(42f, smoother.update(42f, 0f, lowAccuracy = false), 0.01f)
    }

    @Test
    fun crossesNorthByShortestArc() {
        val smoother = HeadingSmoother()
        smoother.update(350f, 0.2f, lowAccuracy = false)
        val result = smoother.update(10f, 0.2f, lowAccuracy = false)
        assertBearingEquals(2.64f, result)
    }

    @Test
    fun crossesNorthBackwardsByShortestArc() {
        val smoother = HeadingSmoother()
        smoother.update(10f, 0.2f, lowAccuracy = false)
        val result = smoother.update(350f, 0.2f, lowAccuracy = false)
        assertBearingEquals(357.36f, result)
    }

    @Test
    fun lowAccuracyMovesSlower() {
        val normal = HeadingSmoother()
        val low = HeadingSmoother()
        normal.update(0f, 0.2f, lowAccuracy = false)
        low.update(0f, 0.2f, lowAccuracy = false)

        val normalResult = normal.update(90f, 0.2f, lowAccuracy = false)
        val lowResult = low.update(90f, 0.2f, lowAccuracy = true)

        assertEquals(56.9f, normalResult, 0.2f)
        assertEquals(11.2f, lowResult, 0.2f)
        assertTrue("low accuracy must lag behind normal", lowResult < normalResult)
    }

    @Test
    fun resetStartsFromRaw() {
        val smoother = HeadingSmoother()
        smoother.update(0f, 0.2f, lowAccuracy = false)
        smoother.update(90f, 0.2f, lowAccuracy = false)
        smoother.reset()
        assertEquals(350f, smoother.update(350f, 0.2f, lowAccuracy = false), 0.01f)
    }

    @Test
    fun nonPositiveDeltaKeepsHeading() {
        val smoother = HeadingSmoother()
        smoother.update(10f, 0.2f, lowAccuracy = false)
        assertEquals(10f, smoother.update(200f, 0f, lowAccuracy = false), 0.01f)
        assertEquals(10f, smoother.update(200f, -0.5f, lowAccuracy = false), 0.01f)
    }

    private fun assertBearingEquals(expected: Float, actual: Float) {
        val diff = abs(((actual - expected + 540f) % 360f) - 180f)
        assertTrue("expected=$expected actual=$actual diff=$diff", diff <= 0.1f)
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.compass.HeadingSmootherTest'
```

Expected: compilation fails with unresolved reference `HeadingSmoother`.

- [ ] **Step 3: Implement `HeadingSmoother`**

Create `HeadingSmoother.kt`:

```kotlin
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
```

- [ ] **Step 4: Run the test and verify it passes**

Run the same command. Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/randomwalk/app/compass/HeadingSmoother.kt \
  app/src/test/java/com/randomwalk/app/compass/HeadingSmootherTest.kt
git commit -m "feat: 实现最短弧低通平滑 (Task 2.2)"
```

---

### Task 3: Fake walk surface and compass loop (2.3)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/walk/FakeWalk.kt`
- Create: `app/src/main/java/com/randomwalk/app/compass/CompassController.kt`
- Create: `app/src/main/java/com/randomwalk/app/ui/WalkScreenView.kt`
- Create: `app/src/main/java/com/randomwalk/app/ui/WalkSurface.kt`
- Create: `app/src/main/res/values/ids.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/randomwalk/app/MainActivity.kt`
- Test: `app/src/test/java/com/randomwalk/app/compass/CompassMathTest.kt` (add arrow-angle tests)
- Test: `app/src/androidTest/java/com/randomwalk/app/WalkScreenTest.kt`

**Interfaces:**
- Produces: `FakeWalk.TARGET_BEARING_DEGREES: Float = 0f`
- Produces: `FakeWalk.REMAINING_METERS: Int = 1000`
- Produces: `CompassMath.arrowAngleDegrees(targetBearingDegrees: Float, deviceHeadingDegrees: Float): Float`
- Produces: `CompassController(context: Context, targetBearingDegrees: Float, onArrowAngleDegrees: (Float) -> Unit)`
- Produces: `CompassController.start()` / `CompassController.stop()`
- Produces: `WalkScreenView.arrowAngleDegrees: Float` and `WalkScreenView.distanceMeters: Int`
- Produces: `WalkSurface.start()` / `WalkSurface.stop()`
- Consumes: `CompassMath.forwardBearingDegrees`, `HeadingSmoother.update`, `Sensor.TYPE_ROTATION_VECTOR`.

- [ ] **Step 1: Add failing arrow-angle tests**

Add to the existing `CompassMathTest`:

```kotlin
@Test
fun arrowAngleIsTargetMinusHeading() {
    assertEquals(0f, CompassMath.arrowAngleDegrees(0f, 0f), 0.01f)
    assertEquals(-90f, CompassMath.arrowAngleDegrees(0f, 90f), 0.01f)
    assertEquals(90f, CompassMath.arrowAngleDegrees(0f, 270f), 0.01f)
    assertEquals(20f, CompassMath.arrowAngleDegrees(10f, 350f), 0.01f)
    assertEquals(-20f, CompassMath.arrowAngleDegrees(350f, 10f), 0.01f)
}

@Test
fun arrowAngleAtOppositeBearingPointsDown() {
    assertEquals(180f, CompassMath.arrowAngleDegrees(0f, 180f), 0.01f)
}
```

Add the import `org.junit.Assert.assertEquals`.

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.compass.CompassMathTest'
```

Expected: compilation fails with unresolved reference `arrowAngleDegrees`.

- [ ] **Step 3: Add `CompassMath.arrowAngleDegrees`**

Add the Task 3 interface to `CompassMath`:

```kotlin
fun arrowAngleDegrees(targetBearingDegrees: Float, deviceHeadingDegrees: Float): Float =
    shortestDeltaDegrees(deviceHeadingDegrees, targetBearingDegrees)
```

Run the same command. Expected: all `CompassMathTest` tests pass, including 180° → 180°.

- [ ] **Step 4: Add the fake walk target**

Create `FakeWalk.kt`:

```kotlin
package com.randomwalk.app.walk

/**
 * Temporary stand-in for the route generator (tasks 5.x/6.2). The target is
 * 1000 m due north of the start point; the distance on screen stays fixed.
 */
object FakeWalk {
    const val TARGET_BEARING_DEGREES = 0f
    const val REMAINING_METERS = 1000
}
```

- [ ] **Step 5: Add the sensor controller**

Create `CompassController.kt`:

```kotlin
package com.randomwalk.app.compass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

class CompassController(
    context: Context,
    private val targetBearingDegrees: Float,
    private val onArrowAngleDegrees: (Float) -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private val smoother = HeadingSmoother()
    private var registered = false
    private var lastTimestampNanos = 0L
    private var lowAccuracy = false

    fun start() {
        if (registered) return
        val sensor = rotationVector
        if (sensor == null) {
            Log.w(TAG, "TYPE_ROTATION_VECTOR unavailable; arrow stays at its last angle")
            return
        }
        smoother.reset()
        lastTimestampNanos = 0L
        lowAccuracy = false
        registered = sensorManager.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_GAME,
        )
        if (!registered) Log.w(TAG, "registerListener returned false")
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
        lastTimestampNanos = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        if (!SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)) return

        val rawBearing = CompassMath.forwardBearingDegrees(rotationMatrix)
        val deltaSeconds = if (lastTimestampNanos == 0L) {
            0f
        } else {
            (event.timestamp - lastTimestampNanos) / 1_000_000_000f
        }
        lastTimestampNanos = event.timestamp

        val heading = smoother.update(rawBearing, deltaSeconds, lowAccuracy)
        onArrowAngleDegrees(
            CompassMath.arrowAngleDegrees(targetBearingDegrees, heading),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return
        lowAccuracy = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
    }

    private companion object {
        const val TAG = "CompassController"
    }
}
```

- [ ] **Step 6: Add ids and the Canvas view**

Create `app/src/main/res/values/ids.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <item name="walk_surface" type="id" />
    <item name="walk_screen" type="id" />
</resources>
```

Create `WalkScreenView.kt`. It must:
- Extend `View`, black background inherited from the window, antialiased white paint.
- Expose `var arrowAngleDegrees: Float` and `var distanceMeters: Int`; each setter calls `invalidate()`.
- In `onDraw`, rotate the canvas by `arrowAngleDegrees` around the arrow center and draw an arrow that points up (toward negative Y) at 0°. Use a shaft plus a triangular head, centered around `(width / 2, height * 0.55f)`.
- Draw the distance as `"$distanceMeters m"` centered above the arrow (for example at `height * 0.18f`) with white text.
- Set `id = R.id.walk_screen` in `init` so tests can find it.

The exact pixel sizes are an implementer choice; the invariant is: 0° = up, positive angles rotate clockwise, white on black, only the arrow and the distance figure.

- [ ] **Step 7: Add the walk surface**

Create `WalkSurface.kt`:

```kotlin
package com.randomwalk.app.ui

import android.content.Context
import android.widget.FrameLayout
import com.randomwalk.app.R
import com.randomwalk.app.compass.CompassController
import com.randomwalk.app.walk.FakeWalk

class WalkSurface(context: Context) : FrameLayout(context) {

    private val screenView = WalkScreenView(context).apply {
        distanceMeters = FakeWalk.REMAINING_METERS
    }

    private val compass = CompassController(
        context = context,
        targetBearingDegrees = FakeWalk.TARGET_BEARING_DEGREES,
    ) { angle ->
        screenView.arrowAngleDegrees = angle
    }

    init {
        id = R.id.walk_surface
        addView(
            screenView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun start() {
        compass.start()
    }

    fun stop() {
        compass.stop()
    }
}
```

`CompassController` delivers sensor callbacks on the main thread because it is registered from the main thread; the setter's `invalidate()` is therefore safe.

- [ ] **Step 8: Lock portrait and wire `MainActivity`**

In `AndroidManifest.xml`, add `android:screenOrientation="portrait"` to the `MainActivity` element.

In `MainActivity`:
- Add `private var walkSurface: WalkSurface? = null`.
- `updatePermissionGate()`: if `LocationPermissionGate.hasPreciseLocation(this)` is true, call `showWalkSurface()` and return; put this check before the `permissionRequested` early-return.
- If the walk surface is currently shown but precise permission is gone, call `walkSurface?.stop()`, set `walkSurface = null`, and recreate the gate view with `setContentView(createPermissionGateView())` before continuing the existing denied/approximate branches.
- `showWalkSurface()`: if `walkSurface != null`, return. Create `WalkSurface(this)`, assign it, call `setContentView(surface)`, then `surface.start()`.
- `onResume`: after `ImmersiveMode.enter(window)`, call `updatePermissionGate()` and then `walkSurface?.start()`.
- Add `onPause`: call `walkSurface?.stop()` before `super.onPause()`.
- `onDestroy`: call `walkSurface?.stop()` before `ImmersiveMode.exit(window)`.
- Keep all existing permission-request, settings-intent, and immersive behavior intact. The "位置权限已授予" text is no longer shown once the walk surface replaces it.

- [ ] **Step 9: Add the walk-screen instrumentation test**

Create `WalkScreenTest.kt`:

```kotlin
package com.randomwalk.app

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.randomwalk.app.ui.WalkScreenView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkScreenTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @Test
    fun precisePermissionShowsFakeWalkScreen() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val screen = activity.findViewById<WalkScreenView>(R.id.walk_screen)
                assertNotNull("walk screen not shown", screen)
                assertEquals(1000, screen.distanceMeters)
                screen.arrowAngleDegrees = -90f
                assertEquals(-90f, screen.arrowAngleDegrees, 0.01f)
            }
        } finally {
            scenario.close()
        }
    }
}
```

- [ ] **Step 10: Build, run JVM tests, and compile androidTest**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`; all JVM tests pass; both APKs compile.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/AndroidManifest.xml \
  app/src/main/java/com/randomwalk/app/walk/FakeWalk.kt \
  app/src/main/java/com/randomwalk/app/compass/CompassController.kt \
  app/src/main/java/com/randomwalk/app/ui/WalkScreenView.kt \
  app/src/main/java/com/randomwalk/app/ui/WalkSurface.kt \
  app/src/main/res/values/ids.xml \
  app/src/main/java/com/randomwalk/app/MainActivity.kt \
  app/src/test/java/com/randomwalk/app/compass/CompassMathTest.kt \
  app/src/androidTest/java/com/randomwalk/app/WalkScreenTest.kt
git commit -m "feat: 完成假数据罗盘漫步页 (Task 2.3)"
```

---

### Task 4: First-run hint overlay (2.4)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/ui/FirstRunHint.kt`
- Create: `app/src/main/java/com/randomwalk/app/ui/WalkHintOverlay.kt`
- Modify: `app/src/main/java/com/randomwalk/app/ui/WalkSurface.kt`
- Modify: `app/src/main/res/values/ids.xml` (add `walk_hint`)
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/randomwalk/app/ui/FirstRunHintTest.kt`
- Test: `app/src/androidTest/java/com/randomwalk/app/WalkHintTest.kt`

**Interfaces:**
- Produces: `FirstRunHint(readShown: () -> Boolean, writeShown: () -> Unit)`
- Produces: `FirstRunHint.shouldShow(): Boolean` / `FirstRunHint.markShown()`
- Produces: `FirstRunHint.from(context: Context): FirstRunHint`
- Produces: `WalkHintOverlay(context: Context)` (TextView subclass, id `R.id.walk_hint`)

- [ ] **Step 1: Write the failing once-only test**

Create `FirstRunHintTest.kt`:

```kotlin
package com.randomwalk.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunHintTest {

    @Test
    fun showsUntilMarkedShown() {
        var shown = false
        val hint = FirstRunHint(
            readShown = { shown },
            writeShown = { shown = true },
        )

        assertTrue(hint.shouldShow())
        hint.markShown()
        assertFalse(hint.shouldShow())
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.ui.FirstRunHintTest'
```

Expected: compilation fails with unresolved reference `FirstRunHint`.

- [ ] **Step 3: Implement `FirstRunHint`**

Create `FirstRunHint.kt`:

```kotlin
package com.randomwalk.app.ui

import android.content.Context

class FirstRunHint(
    private val readShown: () -> Boolean,
    private val writeShown: () -> Unit,
) {
    fun shouldShow(): Boolean = !readShown()

    fun markShown() {
        writeShown()
    }

    companion object {
        private const val PREFS_NAME = "walk"
        private const val KEY_HINT_SHOWN = "hint_shown"

        fun from(context: Context): FirstRunHint {
            val prefs = context.applicationContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE,
            )
            return FirstRunHint(
                readShown = { prefs.getBoolean(KEY_HINT_SHOWN, false) },
                writeShown = { prefs.edit().putBoolean(KEY_HINT_SHOWN, true).commit() },
            )
        }
    }
}
```

Run the test. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Add the hint string, id, and overlay**

Add to `strings.xml`:

```xml
<string name="walk_hint">箭头朝上 = 你正对要去的方向，转身对齐它</string>
```

Add to `ids.xml`:

```xml
<item name="walk_hint" type="id" />
```

Create `WalkHintOverlay.kt`:

```kotlin
package com.randomwalk.app.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.TextView
import com.randomwalk.app.R

class WalkHintOverlay(context: Context) : TextView(context) {
    init {
        id = R.id.walk_hint
        text = context.getString(R.string.walk_hint)
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER
        setBackgroundColor(Color.argb(0xCC, 0x00, 0x00, 0x00))
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        isClickable = true
        isLongClickable = true
    }
}
```

- [ ] **Step 5: Wire the hint into `WalkSurface`**

In `WalkSurface`:
- Add `private val firstRunHint = FirstRunHint.from(context)`.
- Add `private var hintOverlay: WalkHintOverlay? = null`.
- Add `private val removeHint = Runnable { hintOverlay?.let { removeView(it) }; hintOverlay = null }`.
- At the end of `init`, call `showFirstRunHintIfNeeded()`.
- `showFirstRunHintIfNeeded()`: if `firstRunHint.shouldShow()` is false, return. Otherwise call `firstRunHint.markShown()` **before** adding the overlay, create `WalkHintOverlay(context)` with a long-click listener that runs `removeHint` and returns true, assign `hintOverlay`, add it full-screen, and call `overlay.postDelayed(removeHint, 3000L)`.
- Override `onDetachedFromWindow()`: `hintOverlay?.removeCallbacks(removeHint)` before `super.onDetachedFromWindow()`.

The hint is marked shown as soon as it is displayed, not after 3 s, so killing the app mid-hint does not show it again.

- [ ] **Step 6: Add the instrumentation hint test**

Create `WalkHintTest.kt`:

```kotlin
package com.randomwalk.app

import android.Manifest
import android.content.Context
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkHintTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @Before
    fun clearHintPreference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("walk", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun hintShowsOnceAndLongPressDismissesIt() {
        val first = ActivityScenario.launch(MainActivity::class.java)
        try {
            first.onActivity { activity ->
                val hint = activity.findViewById<View>(R.id.walk_hint)
                assertNotNull("first-run hint missing", hint)
                hint.performLongClick()
                assertNull(activity.findViewById<View>(R.id.walk_hint))
            }
        } finally {
            first.close()
        }

        val second = ActivityScenario.launch(MainActivity::class.java)
        try {
            second.onActivity { activity ->
                assertNull("hint must not reappear", activity.findViewById<View>(R.id.walk_hint))
            }
        } finally {
            second.close()
        }
    }
}
```

- [ ] **Step 7: Build and run tests**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`; `FirstRunHintTest` passes; androidTest compiles.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/randomwalk/app/ui/FirstRunHint.kt \
  app/src/main/java/com/randomwalk/app/ui/WalkHintOverlay.kt \
  app/src/main/java/com/randomwalk/app/ui/WalkSurface.kt \
  app/src/main/res/values/ids.xml \
  app/src/main/res/values/strings.xml \
  app/src/test/java/com/randomwalk/app/ui/FirstRunHintTest.kt \
  app/src/androidTest/java/com/randomwalk/app/WalkHintTest.kt
git commit -m "feat: 加入首启一次性提示 (Task 2.4)"
```

---

### Device verification batch (Windows, after Task 4)

Run once for 2.1–2.4:

1. From WSL: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` → green.
2. Copy `app/build/outputs/apk/debug/app-debug.apk` to the Windows host and install it with `adb install -r app-debug.apk` (run from the folder containing that file).
3. `adb shell pm clear com.randomwalk.app`; launch; grant **precise** location.
4. Confirm the screen contains only the arrow and `1000 m`, with no status/navigation bars and the screen staying on.
5. Establish north with a real compass or known landmark. Hold the phone flat with its top pointing north → arrow points up.
6. Turn your body 90° clockwise (face east) without rotating the phone relative to your body → arrow points to the left edge of the screen. Turn another 90° → arrow points down.
7. Repeat the facing-north check while holding the phone flat, at about 45°, and upright; switch hands. The arrow must indicate the same direction in every pose, with no calibration and no leveling prompt.
8. Stand still and rotate in place → arrow follows immediately with the 0.2 s feel; it must not freeze.
9. Bring the phone near metal or a speaker → arrow slows to the 1–2 s feel rather than jumping; it still never freezes.
10. Clear app data, launch → hint appears; long-press it → disappears immediately; force-stop and relaunch → hint does not appear again.
11. Background the app (Home) and return → arrow resumes without a stale jump; revoke precise location in system settings and return → the permission gate returns and the sensor stops.
12. `./gradlew :app:connectedDebugAndroidTest` on the Windows host → smoke, permission, immersive, walk-screen, and hint tests pass.
13. If all pass, mark `2.1`–`2.4` as `[x]` in `openspec/changes/random-walk-app/tasks.md` and commit:

```bash
git add openspec/changes/random-walk-app/tasks.md
git commit -m "chore: 完成罗盘闭环设备验收 (Tasks 2.1–2.4)"
```

**Failure handling:** if any device check fails, do not mark the task; report the exact device/OS, the phone pose, and the observed arrow/distance state. Do not compensate by changing the math thresholds without a failing test first.

---

## Self-Review

**Spec coverage**

- 2.1 world-fixed bearing, hold-angle invariance, roll invariance → Task 1 tests.
- 2.2 shortest-arc smoothing, 0.2 s / 1.5 s, no freeze while standing still → Task 2 tests plus device checks 8–9.
- 2.3 Canvas arrow + distance, fake north target, sensor loop, permission-gated entry → Task 3.
- 2.4 one-time 3 s hint, long-press dismiss, only first time → Task 4.
- Out of scope for this batch, per `tasks.md`: 25 m look-ahead and junction decisions (5.4/5.5), real location and route length (3.x/5.x), start screen (6.1), completion animation (6.3), about page (6.4).

**Type consistency**

- `CompassMath.forwardBearingDegrees` / `normalizeBearingDegrees` / `shortestDeltaDegrees` are defined in Task 1 and consumed by `HeadingSmoother` in Task 2 and `CompassController` in Task 3.
- `CompassMath.arrowAngleDegrees` is added in Task 3 Step 3 and consumed by `CompassController` in Step 5.
- `HeadingSmoother.update(raw, dt, lowAccuracy)` matches both the tests and the controller call.
- `WalkScreenView.arrowAngleDegrees` / `distanceMeters` match the instrumentation test and the `WalkSurface` wiring.
- `FirstRunHint.from(context)` matches the `WalkSurface` call in Task 4.

**Review Focus mapping**

- Edge-on degenerate hold → `degenerateHoldFallsBackToProjectedY` (Task 1).
- 0/360 crossing in both directions → `crossesNorthByShortestArc` and `crossesNorthBackwardsByShortestArc` (Task 2).
- Opposite bearing → `arrowAngleAtOppositeBearingPointsDown` (Task 3).
- Pause/resume and non-positive dt → `resetStartsFromRaw`, `nonPositiveDeltaKeepsHeading` (Task 2), device check 11.
- Hint long-press and relaunch → `FirstRunHintTest`, `WalkHintTest` (Task 4).

**Proportion**

The plan pins the math, the sensor lifecycle, the exact fake constants, the one UI seam that later tasks replace, and the two one-time-hint persistence points. It deliberately leaves arrow pixel geometry, paint styling, and the gate view internals to the implementer.
