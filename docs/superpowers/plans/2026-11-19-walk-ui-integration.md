# Walk UI and Background Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewire the Android UI onto the pure random-leg model and keep the walk alive while the screen is off or the app is backgrounded: a target-bearing-aware compass, a waiting state, a foreground `WalkService` that owns location and the session, a `WalkSurface` bound to the service, long-press reroll, and a `MainActivity` that goes straight from the permission gate to the walk surface.

**Architecture:** `WalkService` is a location-type foreground service that owns `WalkSession` and `LocationSampler`, publishes `WalkState` to listeners, and shows an ongoing notification. `WalkSurface` is the thin Android shell: it owns the compass, the Canvas screen, and the first-run hint, binds to a `WalkController` implemented by the service binder, and starts/stops only the compass as the activity becomes visible. `MainActivity` owns the permission gate and the service lifecycle: it starts and binds the service from the visible activity, unbinds when not visible, and never stops the walk merely because the activity paused. `CompassTargetTracker` is a pure heading/target state holder; `CompassController` delegates to it so a new leg can move the arrow without recreating the sensor listener.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Android SDK 35, minSdk 26, JUnit 4, AndroidX Test, existing `WalkSession`/`RandomLegGenerator`, Play Services Location. No new dependencies.

**Spec:** `openspec/changes/random-walk-no-map/specs/random-walk-guidance/spec.md` (Requirements: *Single-purpose leg view*, *Background walking*, *World-fixed target arrow*, *Remaining distance*, *Location permission and exit*), `openspec/changes/random-walk-no-map/design.md` D5–D9, and `openspec/changes/random-walk-no-map/tasks.md` §2.

## Global Constraints

- Build/test commands need JDK 17: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew ...`. The default `java` on this machine is 25 and is not the project toolchain.
- Do not add Gradle dependencies or test frameworks (no Robolectric, no new AndroidX artifacts).
- Do not change `WalkSession`, `RandomLegGenerator`, `WalkLeg`, `WalkState`, `LegGenerator`, or `ArrivalTracker`; they are already implemented and tested. This plan consumes them.
- Do not delete `data/`, `preparation/`, `walk/FakeWalk.kt`, `debug/LocationDebugProbe.kt`, obsolete strings/ids, or `INTERNET`; that is §3. Do not add the privacy dialog or change hint copy; that is §4.
- Waiting copy is exactly `定位中` and lives in `R.string.walk_waiting`.
- The walk screen shows only the arrow and remaining distance. No map, route, total distance, return control, or completion state; no field whose name contains `total` or `map` may be added to `WalkScreenView`.
- `FLAG_KEEP_SCREEN_ON` stays set while the activity is resumed (`ImmersiveMode` already handles it).
- `WalkService` owns `WalkSession` and `LocationSampler`; `WalkSurface` owns only the compass, screen, and hint. The service uses `START_NOT_STICKY` and `stopWithTask="true"`.
- The service is started only from the visible permission gate. Add `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, and `POST_NOTIFICATIONS`; do **not** add `ACCESS_BACKGROUND_LOCATION`. Request notification permission on Android 13+; denial must not block walking.
- Home, screen-off, and app-switching continue the walk. Back, the notification's `结束` action, and swipe-from-recents stop it.
- `WalkSurface.stop()` stops only the compass. `WalkSurface.release()` stops the compass and unregisters from the controller. The session is stopped only by Back/notification/swipe or by permission loss, through the service.
- A long press anywhere on the walk view, including the first-run hint, calls `WalkController.reroll()`; before the first fix it is a no-op.
- Mark a §2 checkbox in `openspec/changes/random-walk-no-map/tasks.md` only after the command named for that task passes. Use conventional commits (`feat:`, `test:`), matching `git log`.
- Instrumentation tests can only be compiled in this environment (`adb` is not on `PATH`). Compile with `:app:assembleDebugAndroidTest` here and run them on the device during §5.2. If a device is attached, the focused command is `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fully.qualified.TestClass>`.

## Review Focus

1. The walker turns the screen off or switches apps → location updates, arrival, reroll memory, and the current leg continue; returning shows the same leg and distance; the ongoing notification is present. Pinned by `WalkServiceTest` and the §5.2 checklist.
2. The activity pauses (notification shade, permission dialog, screen off) → the compass stops and the activity unbinds, but the walk is not stopped. Pinned by the lifecycle wiring in Task 5.
3. Permission granted but no accepted fix (never started location, or the accuracy filter keeps rejecting) → the screen and notification report `定位中` and show no target bearing or distance number. Pinned by `noAcceptedFixKeepsWaiting` (Task 6).
4. A long press arrives before any accepted fix → nothing is generated, nothing crashes, the screen stays waiting, and no reroll-memory entry is created. Pinned by `rerollBeforeFixIsANoOp` (Task 4).
5. The target bearing sits on the 0°/360° seam while the device heading is just east of north → the arrow rotates by the short arc (e.g. target 350°, heading 10° → -20°), not the long way around. Pinned by `worldFixedBearingWrapsAcrossNorth` (Task 1).

---

### Task 1: Compass target updates (tasks.md 2.1)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/compass/CompassTargetTracker.kt`
- Create: `app/src/test/java/com/randomwalk/app/compass/CompassTargetTrackerTest.kt`
- Modify: `app/src/main/java/com/randomwalk/app/compass/CompassController.kt:10-71`

**Interfaces:**
- Consumes: `CompassMath.normalizeBearingDegrees(Float): Float`, `CompassMath.arrowAngleDegrees(targetBearingDegrees: Float, deviceHeadingDegrees: Float): Float`.
- Produces: `class CompassTargetTracker(targetBearingDegrees: Float)` with `var targetBearingDegrees: Float` (private set), `fun onHeading(rawHeadingDegrees: Float): Float`, `fun updateTargetBearing(degrees: Float): Float?`, `fun resetHeading()`.
- Produces: `CompassController.updateTargetBearing(degrees: Float)`.

- [ ] **Step 1: Write the failing `CompassTargetTrackerTest`**

Create `app/src/test/java/com/randomwalk/app/compass/CompassTargetTrackerTest.kt`:

```kotlin
package com.randomwalk.app.compass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompassTargetTrackerTest {

    @Test
    fun changingTheTargetMovesTheArrowWithoutANewHeading() {
        val tracker = CompassTargetTracker(0f)
        assertEquals(-30f, tracker.onHeading(30f), 0.01f)
        assertEquals(60f, tracker.updateTargetBearing(90f)!!, 0.01f)
        assertEquals(90f, tracker.targetBearingDegrees, 0.01f)
    }

    @Test
    fun targetUpdateBeforeAnyHeadingEmitsNothing() {
        val tracker = CompassTargetTracker(0f)
        assertNull(tracker.updateTargetBearing(90f))
    }

    @Test
    fun targetBearingIsNormalized() {
        val tracker = CompassTargetTracker(-10f)
        assertEquals(350f, tracker.targetBearingDegrees, 0.01f)

        tracker.onHeading(350f)
        assertEquals(10f, tracker.updateTargetBearing(360f)!!, 0.01f)
        assertEquals(0f, tracker.targetBearingDegrees, 0.01f)
    }

    @Test
    fun worldFixedBearingWrapsAcrossNorth() {
        val tracker = CompassTargetTracker(350f)
        assertEquals(-20f, tracker.onHeading(10f), 0.01f)
    }

    @Test
    fun resetHeadingStopsImmediateUpdatesUntilTheNextHeading() {
        val tracker = CompassTargetTracker(0f)
        tracker.onHeading(30f)
        tracker.resetHeading()

        assertNull(tracker.updateTargetBearing(90f))
        assertEquals(0f, tracker.onHeading(90f), 0.01f)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest --tests "com.randomwalk.app.compass.CompassTargetTrackerTest"`

Expected: compilation failure with `Unresolved reference: CompassTargetTracker`.

- [ ] **Step 3: Implement `CompassTargetTracker` and delegate from `CompassController`**

`CompassTargetTracker.kt` holds the normalized target and the last normalized heading:

```kotlin
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
```

In `CompassController` change the constructor parameter `private val targetBearingDegrees: Float` to plain `targetBearingDegrees: Float`, add `private val targetTracker = CompassTargetTracker(targetBearingDegrees)`, and make these call-site changes:

- `start()`: call `targetTracker.resetHeading()` next to `smoother.reset()`.
- `stop()`: call `targetTracker.resetHeading()` after unregistering.
- `onSensorChanged()`: replace the final `onArrowAngleDegrees(CompassMath.arrowAngleDegrees(targetBearingDegrees, heading))` with `onArrowAngleDegrees(targetTracker.onHeading(heading))`.
- Add:

```kotlin
    fun updateTargetBearing(degrees: Float) {
        targetTracker.updateTargetBearing(degrees)?.let(onArrowAngleDegrees)
    }
```

Do not emit an angle when no heading is known; the view keeps its last arrow angle and still shows the distance.

- [ ] **Step 4: Run the compass tests to verify they pass**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest --tests "com.randomwalk.app.compass.*"`

Expected: PASS (`CompassTargetTrackerTest`, `CompassMathTest`, `HeadingSmootherTest`).

- [ ] **Step 5: Mark 2.1 and commit**

Set `- [x]` on task `2.1` in `openspec/changes/random-walk-no-map/tasks.md`, then:

```bash
git add app/src/main/java/com/randomwalk/app/compass/CompassTargetTracker.kt \
        app/src/main/java/com/randomwalk/app/compass/CompassController.kt \
        app/src/test/java/com/randomwalk/app/compass/CompassTargetTrackerTest.kt \
        openspec/changes/random-walk-no-map/tasks.md
git commit -m "feat: update the compass target bearing without recreating the controller"
```

---

### Task 2: Walk screen waiting state (tasks.md 2.2)

**Files:**
- Modify: `app/src/main/java/com/randomwalk/app/ui/WalkScreenView.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/androidTest/java/com/randomwalk/app/WalkScreenViewTest.kt`

**Interfaces:**
- Produces: `WalkScreenView.waitingForLocation: Boolean` (default `true`), `WalkScreenView.distanceText: String`.
- Produces: `@string/walk_waiting` = `定位中`.
- `WalkScreenView` keeps `arrowAngleDegrees` and `distanceMeters`; no other public state is added.

- [ ] **Step 1: Write the failing instrumentation view test**

Create `app/src/androidTest/java/com/randomwalk/app/WalkScreenViewTest.kt`:

```kotlin
package com.randomwalk.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.randomwalk.app.ui.WalkScreenView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkScreenViewTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun waitingStateShowsTheLocalizedLabelAndNoDistance() {
        val view = WalkScreenView(context)

        assertTrue(view.waitingForLocation)
        assertEquals(context.getString(R.string.walk_waiting), view.distanceText)
        assertFalse(view.distanceText.any(Char::isDigit))
    }

    @Test
    fun legStateShowsRemainingMetersAndArrowAngle() {
        val view = WalkScreenView(context)
        view.waitingForLocation = false
        view.distanceMeters = 87
        view.arrowAngleDegrees = -90f

        assertEquals("87 m", view.distanceText)
        assertEquals(-90f, view.arrowAngleDegrees, 0.01f)
    }

    @Test
    fun viewHasNoTotalDistanceOrMapSurface() {
        val declaredNames = WalkScreenView::class.java.declaredMethods.map { it.name.lowercase() } +
            WalkScreenView::class.java.declaredFields.map { it.name.lowercase() }

        assertFalse(declaredNames.any { it.contains("total") })
        assertFalse(declaredNames.any { it.contains("map") })
    }
}
```

- [ ] **Step 2: Run the test build to verify it fails**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:assembleDebugAndroidTest`

Expected: compilation failure with `Unresolved reference: waitingForLocation`, `Unresolved reference: distanceText`, and `Unresolved reference: walk_waiting`.

- [ ] **Step 3: Add the string and implement the waiting state**

Add to `app/src/main/res/values/strings.xml`:

```xml
    <string name="walk_waiting">定位中</string>
```

In `WalkScreenView`:

- Add `var waitingForLocation: Boolean = true` with a setter that calls `invalidate()`.
- Add `val distanceText: String get() = if (waitingForLocation) context.getString(R.string.walk_waiting) else "$distanceMeters m"`.
- In `onDraw`, draw `context.getString(R.string.walk_waiting)` centered (`width / 2f`, `height / 2f`) and return before the arrow when `waitingForLocation` is true.
- Change `drawDistance` to call `canvas.drawText(distanceText, width / 2f, height * 0.18f, textPaint)`.
- Build and rotate the arrow only in the non-waiting branch.

- [ ] **Step 4: Compile the instrumentation test to verify it passes**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:assembleDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`.

If a device is attached, run the focused test:

`JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.randomwalk.app.WalkScreenViewTest`

Expected: 3 tests pass. Without a device, the compile above is the available gate; the run happens in §5.2.

- [ ] **Step 5: Mark 2.2 and commit**

Set `- [x]` on task `2.2` in `openspec/changes/random-walk-no-map/tasks.md`, then:

```bash
git add app/src/main/java/com/randomwalk/app/ui/WalkScreenView.kt \
        app/src/main/res/values/strings.xml \
        app/src/androidTest/java/com/randomwalk/app/WalkScreenViewTest.kt \
        openspec/changes/random-walk-no-map/tasks.md
git commit -m "feat: show a localized waiting state on the walk screen"
```

---

### Task 3: Foreground walk service (tasks.md 2.3)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/ui/WalkController.kt`
- Create: `app/src/main/java/com/randomwalk/app/walk/WalkService.kt`
- Create: `app/src/main/res/drawable/ic_walk_notification.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/androidTest/java/com/randomwalk/app/WalkServiceTest.kt`

**Interfaces:**
- Produces: `interface WalkController { fun currentState(): WalkState; fun addListener(listener: (WalkState?) -> Unit); fun removeListener(listener: (WalkState?) -> Unit); fun reroll(); fun stopWalk() }`.
- Produces: `interface WalkLocationSource { fun start(); fun stop() }`.
- Produces: `object WalkServiceDependencies` with `var locationSourceFactory: (Context, (LocationSample) -> Unit) -> WalkLocationSource`, `var generatorFactory: () -> LegGenerator`, and `fun reset()`.
- Produces: `class WalkService : Service()` with `inner class LocalBinder : Binder(), WalkController` and constants `ACTION_START`, `ACTION_STOP`, `ACTION_REROLL`.

- [ ] **Step 1: Write the failing `WalkServiceTest`**

Create `app/src/androidTest/java/com/randomwalk/app/WalkServiceTest.kt`:

```kotlin
package com.randomwalk.app

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.location.LocationSample
import com.randomwalk.app.walk.LegGenerator
import com.randomwalk.app.walk.WalkLeg
import com.randomwalk.app.walk.WalkLocationSource
import com.randomwalk.app.walk.WalkService
import com.randomwalk.app.walk.WalkServiceDependencies
import com.randomwalk.app.walk.WalkState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WalkServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val origin = LatLon(48.8566, 2.3522)

    @get:Rule
    val permissions: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) {
            GrantPermissionRule.grant(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            GrantPermissionRule.grant(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    private class FakeSource : WalkLocationSource {
        var starts = 0
        var stops = 0
        override fun start() { starts++ }
        override fun stop() { stops++ }
    }

    private class FixedGenerator(private val leg: WalkLeg) : LegGenerator {
        var calls = 0
            private set

        override fun next(from: LatLon, excludedBearings: List<Float>): WalkLeg {
            calls++
            return leg
        }
    }

    private lateinit var source: FakeSource
    private lateinit var generator: FixedGenerator
    private var emit: ((LocationSample) -> Unit)? = null
    private var controller: WalkService.LocalBinder? = null
    private var bound = false
    private var connection: ServiceConnection? = null
    private val states = mutableListOf<WalkState?>()
    private val stopLatch = CountDownLatch(1)

    @Before
    fun setUp() {
        source = FakeSource()
        generator = FixedGenerator(
            WalkLeg(
                target = GeoMath.offsetMeters(origin, 0.0, 80.0),
                bearingDegrees = 0f,
                distanceMeters = 80,
            ),
        )
        WalkServiceDependencies.locationSourceFactory = { _, onAccepted ->
            emit = onAccepted
            source
        }
        WalkServiceDependencies.generatorFactory = { generator }
        states.clear()
        controller = null
        bound = false
    }

    @After
    fun tearDown() {
        connection?.let { if (bound) context.unbindService(it) }
        context.stopService(Intent(context, WalkService::class.java))
        WalkServiceDependencies.reset()
    }

    private fun startAndBind() {
        context.startForegroundService(
            Intent(context, WalkService::class.java).setAction(WalkService.ACTION_START),
        )
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as WalkService.LocalBinder
                controller = binder
                binder.addListener { state ->
                    synchronized(states) { states += state }
                    if (state == null) stopLatch.countDown()
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                controller = null
            }
        }
        connection = conn
        bound = context.bindService(
            Intent(context, WalkService::class.java),
            conn,
            Context.BIND_AUTO_CREATE,
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    private fun awaitState(predicate: (WalkState?) -> Boolean): WalkState? {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val snapshot = synchronized(states) { states.toList() }
            snapshot.lastOrNull(predicate)?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    private fun activeNotificationId(): Int? =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .activeNotifications
            .firstOrNull { it.packageName == context.packageName }
            ?.id

    @Test
    fun startShowsWaitingAndNotification_thenFirstFixStartsALeg() {
        startAndBind()

        assertTrue(controller!!.currentState().waitingForLocation)
        assertEquals(0, generator.calls)
        assertEquals(1, source.starts)
        assertTrue(activeNotificationId() != null)

        emit?.invoke(LocationSample(origin.latitude, origin.longitude, 5.0, 0L))

        val state = awaitState { it?.waitingForLocation == false }
        assertEquals(80, state!!.remainingMeters)
        assertEquals(1, generator.calls)
        assertFalse(state.waitingForLocation)
    }

    @Test
    fun rerollGeneratesANewLeg() {
        startAndBind()
        emit?.invoke(LocationSample(origin.latitude, origin.longitude, 5.0, 0L))
        awaitState { it?.waitingForLocation == false }

        controller!!.reroll()

        assertEquals(2, generator.calls)
    }

    @Test
    fun stopStopsTheSourceAndEndsTheService() {
        startAndBind()
        emit?.invoke(LocationSample(origin.latitude, origin.longitude, 5.0, 0L))
        awaitState { it?.waitingForLocation == false }

        controller!!.stopWalk()

        assertTrue(stopLatch.await(5, TimeUnit.SECONDS))
        assertEquals(1, source.stops)
    }
}
```

- [ ] **Step 2: Run the test build to verify it fails**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:assembleDebugAndroidTest`

Expected: compilation failure with `Unresolved reference: WalkService`, `WalkServiceDependencies`, and `WalkLocationSource`.

- [ ] **Step 3: Implement `WalkController`, `WalkService`, the notification resources, and the manifest**

`app/src/main/java/com/randomwalk/app/ui/WalkController.kt`:

```kotlin
package com.randomwalk.app.ui

import com.randomwalk.app.walk.WalkState

interface WalkController {
    fun currentState(): WalkState
    fun addListener(listener: (WalkState?) -> Unit)
    fun removeListener(listener: (WalkState?) -> Unit)
    fun reroll()
    fun stopWalk()
}
```

`app/src/main/java/com/randomwalk/app/walk/WalkService.kt`:

```kotlin
package com.randomwalk.app.walk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.randomwalk.app.MainActivity
import com.randomwalk.app.R
import com.randomwalk.app.location.LocationSample
import com.randomwalk.app.location.LocationSampler
import com.randomwalk.app.permission.LocationPermissionGate
import com.randomwalk.app.ui.WalkController
import java.util.concurrent.CopyOnWriteArrayList

interface WalkLocationSource {
    fun start()
    fun stop()
}

object WalkServiceDependencies {
    private val defaultLocationSourceFactory:
        (Context, (LocationSample) -> Unit) -> WalkLocationSource = { context, onAccepted ->
        val sampler = LocationSampler(context, onAccepted = onAccepted)
        object : WalkLocationSource {
            override fun start() = sampler.start()
            override fun stop() = sampler.stop()
        }
    }

    private val defaultGeneratorFactory: () -> LegGenerator = { RandomLegGenerator() }

    var locationSourceFactory: (Context, (LocationSample) -> Unit) -> WalkLocationSource =
        defaultLocationSourceFactory
    var generatorFactory: () -> LegGenerator = defaultGeneratorFactory

    fun reset() {
        locationSourceFactory = defaultLocationSourceFactory
        generatorFactory = defaultGeneratorFactory
    }
}

class WalkService : Service() {

    inner class LocalBinder : Binder(), WalkController {
        override fun currentState(): WalkState = engine?.state ?: WalkState()

        override fun addListener(listener: (WalkState?) -> Unit) {
            listeners += listener
            listener(currentState())
        }

        override fun removeListener(listener: (WalkState?) -> Unit) {
            listeners -= listener
        }

        override fun reroll() {
            engine?.reroll()
        }

        override fun stopWalk() {
            stopWalkAndSelf()
        }
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(WalkState?) -> Unit>()
    private var engine: WalkSession? = null
    private var locationSource: WalkLocationSource? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startWalk()
            ACTION_STOP -> stopWalkAndSelf()
            ACTION_REROLL -> engine?.reroll()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startWalk() {
        if (engine != null) {
            startForegroundNotification(currentState())
            return
        }
        if (!LocationPermissionGate.hasPreciseLocation(this)) {
            stopSelf()
            return
        }

        stopping = false
        startForegroundNotification(WalkState())

        val session = WalkSession(WalkServiceDependencies.generatorFactory()) { state ->
            publish(state)
        }
        engine = session

        val source = WalkServiceDependencies.locationSourceFactory(this) { sample ->
            session.onLocation(sample)
        }
        locationSource = source
        source.start()
        publish(session.state)
    }

    private fun publish(state: WalkState) {
        if (stopping) return
        mainHandler.post {
            if (stopping) return@post
            updateNotification(state)
            listeners.forEach { it(state) }
        }
    }

    private fun startForegroundNotification(state: WalkState) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: WalkState) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: WalkState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WalkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (state.waitingForLocation) {
            getString(R.string.walk_waiting)
        } else {
            getString(R.string.walk_remaining_notification, state.remainingMeters ?: 0)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_walk_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_walk_notification),
                    getString(R.string.walk_stop),
                    stop,
                ).build(),
            )
            .build()
    }

    private fun stopWalkAndSelf() {
        if (stopping) return
        stopping = true
        locationSource?.stop()
        locationSource = null
        engine?.stop()
        engine = null
        mainHandler.post {
            listeners.forEach { it(null) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopWalkAndSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopping = true
        locationSource?.stop()
        locationSource = null
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.walk_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.walk_notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.randomwalk.app.action.START_WALK"
        const val ACTION_STOP = "com.randomwalk.app.action.STOP_WALK"
        const val ACTION_REROLL = "com.randomwalk.app.action.REROLL"
        private const val CHANNEL_ID = "walk"
        private const val NOTIFICATION_ID = 1
    }
}
```

`app/src/main/res/drawable/ic_walk_notification.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,2 L19,22 L12,17 L5,22 Z" />
</vector>
```

Add to `app/src/main/res/values/strings.xml` (keep `walk_waiting` from Task 2):

```xml
    <string name="walk_remaining_notification">剩余 %1$d m</string>
    <string name="walk_stop">结束</string>
    <string name="walk_notification_channel">步行</string>
    <string name="walk_notification_channel_description">步行时保持定位</string>
```

Add to `app/src/main/AndroidManifest.xml` beside the location permissions (keep `INTERNET` until §3):

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Add inside `<application>`:

```xml
        <service
            android:name=".walk.WalkService"
            android:exported="false"
            android:foregroundServiceType="location"
            android:stopWithTask="true" />
```

Do not add `ACCESS_BACKGROUND_LOCATION`: the service is started from the visible activity and its `location` type makes it a foreground service.

- [ ] **Step 4: Compile and run the service test**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:assembleDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`.

If a device is attached:

`JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.randomwalk.app.WalkServiceTest`

Expected: 3 tests pass. Without a device, the compile is the available gate; run it in §5.2.

- [ ] **Step 5: Mark 2.3 and commit**

Set `- [x]` on task `2.3`, then:

```bash
git add app/src/main/java/com/randomwalk/app/ui/WalkController.kt \
        app/src/main/java/com/randomwalk/app/walk/WalkService.kt \
        app/src/main/res/drawable/ic_walk_notification.xml \
        app/src/main/res/values/strings.xml \
        app/src/main/AndroidManifest.xml \
        app/src/androidTest/java/com/randomwalk/app/WalkServiceTest.kt \
        openspec/changes/random-walk-no-map/tasks.md
git commit -m "feat: run the walk in a location foreground service"
```

---

### Task 4: `WalkSurface` binds to the service (tasks.md 2.4)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/compass/WalkCompass.kt`
- Modify: `app/src/main/java/com/randomwalk/app/compass/CompassController.kt`
- Modify: `app/src/main/java/com/randomwalk/app/ui/WalkSurface.kt`
- Create: `app/src/androidTest/java/com/randomwalk/app/WalkSurfaceTest.kt`

**Interfaces:**
- Produces: `interface WalkCompass { fun start(); fun stop(); fun updateTargetBearing(degrees: Float) }`.
- Produces: `class WalkSurface(context: Context, compassFactory: (Context, (Float) -> Unit) -> WalkCompass = { context, onAngle -> CompassController(context, 0f, onAngle) })` with `fun bind(controller: WalkController)`, `fun unbind()`, `var onWalkEnded: (() -> Unit)?`, `fun start()`, `fun stop()`, `fun release()`.
- `CompassController` implements `WalkCompass`.

- [ ] **Step 1: Write the failing `WalkSurfaceTest`**

Create `app/src/androidTest/java/com/randomwalk/app/WalkSurfaceTest.kt`:

```kotlin
package com.randomwalk.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.randomwalk.app.compass.WalkCompass
import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.ui.FirstRunHint
import com.randomwalk.app.ui.WalkController
import com.randomwalk.app.ui.WalkScreenView
import com.randomwalk.app.ui.WalkSurface
import com.randomwalk.app.walk.WalkLeg
import com.randomwalk.app.walk.WalkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkSurfaceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class FakeCompass : WalkCompass {
        var starts = 0
        var stops = 0
        val bearings = mutableListOf<Float>()

        override fun start() { starts++ }
        override fun stop() { stops++ }
        override fun updateTargetBearing(degrees: Float) { bearings += degrees }
    }

    private class FakeController : WalkController {
        var state = WalkState()
        val listeners = mutableListOf<(WalkState?) -> Unit>()
        var rerolls = 0
        var stops = 0

        override fun currentState(): WalkState = state

        override fun addListener(listener: (WalkState?) -> Unit) {
            listeners += listener
            listener(state)
        }

        override fun removeListener(listener: (WalkState?) -> Unit) {
            listeners -= listener
        }

        override fun reroll() { rerolls++ }

        override fun stopWalk() { stops++ }

        fun emit(next: WalkState?) {
            state = next ?: WalkState()
            listeners.forEach { it(next) }
        }
    }

    @Before
    fun markHintShown() {
        FirstRunHint.from(context).markShown()
    }

    @Test
    fun waitingStateShowsTheWaitingLabelAndNoTarget() {
        val compass = FakeCompass()
        val surface = WalkSurface(context) { _, _ -> compass }
        val controller = FakeController()

        surface.bind(controller)

        val screen = surface.findViewById<WalkScreenView>(R.id.walk_screen)
        assertTrue(screen.waitingForLocation)
        assertEquals(context.getString(R.string.walk_waiting), screen.distanceText)
        assertTrue(compass.bearings.isEmpty())
    }

    @Test
    fun legStateRendersDistanceAndUpdatesTheCompass() {
        val compass = FakeCompass()
        val surface = WalkSurface(context) { _, _ -> compass }
        val controller = FakeController()
        surface.bind(controller)

        controller.emit(
            WalkState(
                leg = WalkLeg(
                    target = GeoMath.offsetMeters(LatLon(48.8566, 2.3522), 0.0, 80.0),
                    bearingDegrees = 0f,
                    distanceMeters = 80,
                ),
                remainingMeters = 80,
                targetBearingDegrees = 90f,
            ),
        )

        val screen = surface.findViewById<WalkScreenView>(R.id.walk_screen)
        assertFalse(screen.waitingForLocation)
        assertEquals("80 m", screen.distanceText)
        assertEquals(listOf(90f), compass.bearings)
    }

    @Test
    fun longPressRerollsAndStartStopControlsOnlyTheCompass() {
        val compass = FakeCompass()
        val surface = WalkSurface(context) { _, _ -> compass }
        val controller = FakeController()
        surface.bind(controller)

        surface.start()
        surface.performLongClick()
        surface.stop()

        assertEquals(1, compass.starts)
        assertEquals(1, compass.stops)
        assertEquals(1, controller.rerolls)
    }

    @Test
    fun rerollBeforeFixIsANoOp() {
        val compass = FakeCompass()
        val surface = WalkSurface(context) { _, _ -> compass }
        val controller = FakeController()
        surface.bind(controller)

        surface.performLongClick()

        assertEquals(1, controller.rerolls)
        assertEquals(context.getString(R.string.walk_waiting), surface.distanceTextForTest())
    }

    private fun WalkSurface.distanceTextForTest(): String =
        findViewById<WalkScreenView>(R.id.walk_screen).distanceText
}
```

- [ ] **Step 2: Run the test build to verify it fails**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:assembleDebugAndroidTest`

Expected: compilation failure with `Unresolved reference: WalkCompass` and `bind`.

- [ ] **Step 3: Implement `WalkCompass`, make `CompassController` implement it, and rewrite `WalkSurface`**

`app/src/main/java/com/randomwalk/app/compass/WalkCompass.kt`:

```kotlin
package com.randomwalk.app.compass

interface WalkCompass {
    fun start()
    fun stop()
    fun updateTargetBearing(degrees: Float)
}
```

In `CompassController`, change the declaration to `class CompassController(...) : SensorEventListener, WalkCompass` and add `override` to `start()`, `stop()`, and `updateTargetBearing()`.

`WalkSurface.kt`:

```kotlin
package com.randomwalk.app.ui

import android.content.Context
import android.widget.FrameLayout
import com.randomwalk.app.R
import com.randomwalk.app.compass.CompassController
import com.randomwalk.app.compass.WalkCompass
import com.randomwalk.app.walk.WalkState

class WalkSurface(
    context: Context,
    compassFactory: (Context, (Float) -> Unit) -> WalkCompass = { ctx, onAngle ->
        CompassController(ctx, 0f, onAngle)
    },
) : FrameLayout(context) {

    private val screenView = WalkScreenView(context)
    private val compass = compassFactory(context) { angle ->
        screenView.arrowAngleDegrees = angle
    }
    private val firstRunHint = FirstRunHint.from(context)
    private var hintOverlay: WalkHintOverlay? = null
    private var controller: WalkController? = null
    var onWalkEnded: (() -> Unit)? = null

    private val removeHint = Runnable {
        hintOverlay?.let { removeView(it) }
        hintOverlay = null
    }

    private val stateListener: (WalkState?) -> Unit = { state ->
        if (state == null) {
            onWalkEnded?.invoke()
        } else {
            render(state)
        }
    }

    init {
        id = R.id.walk_surface
        addView(
            screenView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        isLongClickable = true
        setOnLongClickListener {
            controller?.reroll()
            true
        }
        showFirstRunHintIfNeeded()
    }

    fun bind(controller: WalkController) {
        this.controller = controller
        controller.addListener(stateListener)
        render(controller.currentState())
    }

    fun unbind() {
        controller?.removeListener(stateListener)
        controller = null
    }

    fun start() {
        compass.start()
    }

    fun stop() {
        compass.stop()
    }

    fun release() {
        stop()
        unbind()
        hintOverlay?.removeCallbacks(removeHint)
        hintOverlay = null
    }

    private fun render(state: WalkState) {
        screenView.waitingForLocation = state.waitingForLocation
        if (state.waitingForLocation) return
        screenView.distanceMeters = state.remainingMeters ?: 0
        state.targetBearingDegrees?.let(compass::updateTargetBearing)
    }

    override fun onDetachedFromWindow() {
        hintOverlay?.removeCallbacks(removeHint)
        super.onDetachedFromWindow()
    }

    private fun showFirstRunHintIfNeeded() {
        if (!firstRunHint.shouldShow()) return
        firstRunHint.markShown()

        val overlay = WalkHintOverlay(context)
        overlay.setOnLongClickListener {
            removeHint.run()
            controller?.reroll()
            true
        }
        hintOverlay = overlay
        addView(
            overlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        overlay.postDelayed(removeHint, 3000L)
    }
}
```

The surface itself no longer imports `FakeWalk`, owns `LocationSampler`, or calls `WalkSession`; the service does that.

- [ ] **Step 4: Compile and run the surface test**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:assembleDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`.

If a device is attached:

`JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.randomwalk.app.WalkSurfaceTest`

Expected: 4 tests pass.

- [ ] **Step 5: Mark 2.4 and commit**

Set `- [x]` on task `2.4`, then:

```bash
git add app/src/main/java/com/randomwalk/app/compass/WalkCompass.kt \
        app/src/main/java/com/randomwalk/app/compass/CompassController.kt \
        app/src/main/java/com/randomwalk/app/ui/WalkSurface.kt \
        app/src/androidTest/java/com/randomwalk/app/WalkSurfaceTest.kt \
        openspec/changes/random-walk-no-map/tasks.md
git commit -m "feat: bind the walk surface to the foreground service"
```

---

### Task 5: `MainActivity` starts and binds the service (tasks.md 2.5)

**Files:**
- Modify: `app/src/main/java/com/randomwalk/app/MainActivity.kt`
- Modify: `app/src/androidTest/java/com/randomwalk/app/WalkScreenTest.kt`
- Modify: `app/src/androidTest/java/com/randomwalk/app/WalkHintTest.kt`
- Modify: `app/src/androidTest/java/com/randomwalk/app/LocationPermissionManifestTest.kt`
- Delete: `app/src/androidTest/java/com/randomwalk/app/PreparationScreenTest.kt`

**Interfaces:**
- Consumes: `WalkService.ACTION_START`, `WalkService.LocalBinder`, `WalkController`, `WalkSurface.bind/unbind/release`.
- Produces: `MainActivity` with no preparation/start-placeholder/debug-data flow; precise permission → start/bind service → `WalkSurface`; no service stop in `onPause`/`onStop`; Back stops the walk.

- [ ] **Step 1: Update the instrumentation tests first**

In `WalkScreenTest`, remove the `EXTRA_SKIP_DATA_PREPARATION`/test-center extras and grant precise location (use `GrantPermissionRule` as in `WalkServiceTest`). Set a fake, non-emitting `WalkServiceDependencies.locationSourceFactory` in `@Before` and reset it in `@After` so the screen deterministically waits. Launch `MainActivity` and assert:

```kotlin
onActivity {
    assertNotNull(it.findViewById(R.id.walk_surface))
    val screen = it.findViewById<WalkScreenView>(R.id.walk_screen)
    assertTrue(screen.waitingForLocation)
    assertEquals(it.getString(R.string.walk_waiting), screen.distanceText)
    assertNull(it.findViewById(R.id.start_placeholder))
}
```

In `WalkHintTest`, remove the extras, use the same fake non-emitting source, clear the first-run hint preference, launch, assert the hint overlay is present, long-press it, and assert it is gone (`controller.reroll()` is a no-op before a fix, so the screen stays waiting).

In `LocationPermissionManifestTest`, keep the existing fine/coarse assertions and add:

```kotlin
val requested = context.packageManager
    .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    .requestedPermissions
    .toList()
assertTrue(requested.contains(Manifest.permission.FOREGROUND_SERVICE))
assertTrue(requested.contains(Manifest.permission.FOREGROUND_SERVICE_LOCATION))
assertTrue(requested.contains(Manifest.permission.POST_NOTIFICATIONS))
assertFalse(requested.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION))

val service = context.packageManager.getServiceInfo(
    ComponentName(context, WalkService::class.java),
    0,
)
assertTrue(service.flags and ServiceInfo.FLAG_STOP_WITH_TASK != 0)
if (Build.VERSION.SDK_INT >= 29) {
    assertTrue(
        service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0,
    )
}
```

Do not remove the existing `INTERNET` assertion yet; §3 removes `INTERNET` and updates that test. Delete `PreparationScreenTest.kt` because `MainActivity` no longer exposes the preparation flow; §3 deletes the remaining preparation classes and their tests.

- [ ] **Step 2: Run the test build to verify it fails**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:assembleDebugAndroidTest`

Expected: compilation failure because `MainActivity` still has the old flow and the tests reference the new IDs/behaviors, or a runtime failure in the old flow. (If it compiles because the old classes still exist, the behavior assertions still fail on a device.)

- [ ] **Step 3: Rewrite `MainActivity` around the permission gate and service lifecycle**

Replace the preparation/debug/start-placeholder code with this structure (keep `createPermissionGateView`, `requestLocationPermission`, `onPermissionActionClicked`, `actionLabel`, and `openAppSettings` from the current file):

```kotlin
package com.randomwalk.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import com.randomwalk.app.permission.LocationPermissionGate
import com.randomwalk.app.ui.ImmersiveMode
import com.randomwalk.app.ui.WalkController
import com.randomwalk.app.ui.WalkSurface
import com.randomwalk.app.walk.WalkService

class MainActivity : Activity() {

    private lateinit var messageView: android.widget.TextView
    private lateinit var actionButton: android.widget.Button
    private var permissionRequested = false
    private var notificationPermissionRequested = false
    private var walkSurface: WalkSurface? = null
    private var walkController: WalkController? = null
    private var serviceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            walkController = service as WalkService.LocalBinder
            walkSurface?.bind(walkController!!)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            walkController = null
            walkSurface?.unbind()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createPermissionGateView())
        ImmersiveMode.enter(window)
        if (savedInstanceState == null && !LocationPermissionGate.hasPreciseLocation(this)) {
            requestLocationPermission()
        } else {
            updatePermissionGate()
        }
    }

    override fun onStart() {
        super.onStart()
        if (LocationPermissionGate.hasPreciseLocation(this)) {
            startWalkService()
            bindWalkService()
        }
    }

    override fun onResume() {
        super.onResume()
        ImmersiveMode.enter(window)
        updatePermissionGate()
        walkSurface?.start()
    }

    override fun onPause() {
        walkSurface?.stop()
        super.onPause()
    }

    override fun onStop() {
        unbindWalkService()
        walkSurface?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        unbindWalkService()
        walkSurface?.release()
        walkSurface = null
        ImmersiveMode.exit(window)
        super.onDestroy()
    }

    override fun onBackPressed() {
        stopWalk()
        super.onBackPressed()
    }

    private fun startWalkService() {
        if (!LocationPermissionGate.hasPreciseLocation(this)) return
        maybeRequestNotificationPermission()
        startForegroundService(
            Intent(this, WalkService::class.java).setAction(WalkService.ACTION_START),
        )
    }

    private fun bindWalkService() {
        if (serviceBound) return
        serviceBound = bindService(
            Intent(this, WalkService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun unbindWalkService() {
        if (!serviceBound) return
        unbindService(connection)
        serviceBound = false
        walkController = null
        walkSurface?.unbind()
    }

    private fun stopWalk() {
        walkController?.stopWalk()
        stopService(Intent(this, WalkService::class.java))
        walkController = null
    }

    private fun showWalkSurface() {
        if (walkSurface != null) return
        val surface = WalkSurface(this)
        surface.onWalkEnded = { if (!isFinishing) finish() }
        walkSurface = surface
        setContentView(surface)
        walkController?.let(surface::bind)
        surface.start()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (notificationPermissionRequested) return
        notificationPermissionRequested = true
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS,
        )
    }

    private fun updatePermissionGate() {
        if (!::messageView.isInitialized) return

        if (LocationPermissionGate.hasPreciseLocation(this)) {
            showWalkSurface()
            startWalkService()
            bindWalkService()
            return
        }

        stopWalk()
        walkSurface?.release()
        walkSurface = null
        setContentView(createPermissionGateView())

        if (permissionRequested) {
            messageView.setText(R.string.permission_requesting)
            actionButton.visibility = View.GONE
            return
        }

        when {
            LocationPermissionGate.hasApproximateLocation(this) -> {
                messageView.setText(R.string.permission_approximate_only)
                actionButton.visibility = View.VISIBLE
                actionButton.setText(actionLabel())
            }

            else -> {
                messageView.setText(R.string.permission_required)
                actionButton.visibility = View.VISIBLE
                actionButton.setText(actionLabel())
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LocationPermissionGate.REQUEST_CODE) {
            permissionRequested = false
            if (LocationPermissionGate.hasPreciseLocation(this)) {
                maybeRequestNotificationPermission()
            }
            updatePermissionGate()
        }
    }

    // Keep createPermissionGateView(), requestLocationPermission(),
    // onPermissionActionClicked(), actionLabel(), and openAppSettings()
    // from the current file unchanged.

    companion object {
        private const val REQUEST_NOTIFICATIONS = 200
    }
}
```

Key lifecycle rules:

- `onPause`/`onStop` stop the compass and unbind; they never call `stopWalk()` or `stopService()`.
- Back calls `stopWalk()` (binder stop, then `stopService` as a fallback) and finishes.
- Removing the task stops the service through `stopWithTask` and `onTaskRemoved`.
- Permission loss calls `stopWalk()`, releases the surface, and shows the gate.
- Notification permission denial is not fatal; `startWalkService()` still runs.

- [ ] **Step 4: Compile the instrumentation tests**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:assembleDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`.

If a device is attached, run the focused tests:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.randomwalk.app.WalkScreenTest
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.randomwalk.app.WalkHintTest
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.randomwalk.app.LocationPermissionManifestTest
```

Expected: all pass.

- [ ] **Step 5: Mark 2.5 and commit**

Set `- [x]` on task `2.5`, then:

```bash
git add app/src/main/java/com/randomwalk/app/MainActivity.kt \
        app/src/androidTest/java/com/randomwalk/app/WalkScreenTest.kt \
        app/src/androidTest/java/com/randomwalk/app/WalkHintTest.kt \
        app/src/androidTest/java/com/randomwalk/app/LocationPermissionManifestTest.kt \
        openspec/changes/random-walk-no-map/tasks.md
git rm app/src/androidTest/java/com/randomwalk/app/PreparationScreenTest.kt
git commit -m "feat: keep the walk service alive across activity pauses"
```

---

### Task 6: No-fix path (tasks.md 2.6)

**Files:**
- Modify: `app/src/androidTest/java/com/randomwalk/app/WalkServiceTest.kt` (add one test)
- Modify: `app/src/androidTest/java/com/randomwalk/app/WalkScreenViewTest.kt` (already covers the view)

**Interfaces:**
- Consumes: the fake non-emitting service source from Task 3.

- [ ] **Step 1: Add the failing no-fix assertion**

Add to `WalkServiceTest`:

```kotlin
@Test
fun noAcceptedFixKeepsWaiting() {
    startAndBind()

    repeat(5) {
        Thread.sleep(100)
        assertTrue(controller!!.currentState().waitingForLocation)
        assertEquals(0, generator.calls)
        assertTrue(activeNotificationId() != null)
    }
}
```

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:assembleDebugAndroidTest`

Expected: `BUILD SUCCESSFUL` (the production service already satisfies this; the test is the regression gate).

- [ ] **Step 2: Mark 2.6 and commit the test**

Set `- [x]` on task `2.6`, then:

```bash
git add app/src/androidTest/java/com/randomwalk/app/WalkServiceTest.kt \
        openspec/changes/random-walk-no-map/tasks.md
git commit -m "test: pin the no-fix waiting path for the walk service"
```

---

### Task 7: Full verification

- [ ] **Step 1: Run all JVM unit tests**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest`

Expected: all pass.

- [ ] **Step 2: Compile the instrumentation suite and the debug APK**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Inspect the merged manifest**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:processDebugMainManifest
grep -E "uses-permission|WalkService|foregroundServiceType|stopWithTask" \
  app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
```

Expected: fine/coarse location, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`; no `ACCESS_BACKGROUND_LOCATION`; `WalkService` with `foregroundServiceType="location"` and `stopWithTask="true"`. `INTERNET` is still present until §3.

- [ ] **Step 4: Review the diff against the constraints**

Confirm:

- no `onPause`/`onStop` call to `stopWalk()`/`stopService()`;
- no `ACCESS_BACKGROUND_LOCATION`;
- `WalkSurface` no longer owns `WalkSession` or `LocationSampler`;
- the notification stop action and `stopWithTask` are present;
- Back and permission loss stop the service;
- the walk screen has only arrow + distance.

- [ ] **Step 5: Commit any remaining plan/doc changes**

```bash
git add docs/superpowers/plans/2026-11-19-walk-ui-integration.md \
        openspec/changes/random-walk-no-map
git commit -m "docs: plan background walk service integration"
```
