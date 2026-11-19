# Random Leg Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the pure-Kotlin random-leg model — a leg value, a uniform bearing/distance generator with direction exclusions, a timestamp-based arrival tracker, and a session that owns the current leg, remaining distance, target bearing, reroll, and reroll memory — so the walk UI can later be rewired onto it without Android dependencies.

**Architecture:** `LegGenerator` is a one-method interface; `RandomLegGenerator` draws bearings and distances from an injected `kotlin.random.Random` and builds targets with `GeoMath.offsetMeters`. `ArrivalTracker` converts timestamped `LocationSample`s into a continuous-dwell verdict. `WalkSession` composes the two, owns all mutable state, and pushes an immutable `WalkState` snapshot to a callback. The whole package imports only existing pure-Kotlin types (`LatLon`, `GeoMath`, `LocationSample`), so every behavior is pinned by JVM tests.

**Tech Stack:** Kotlin (AGP/Kotlin Android plugin already configured), JUnit 4, existing `GeoMath`/`LatLon`/`LocationSample`. No new dependencies.

**Spec:** `openspec/changes/random-walk-no-map/specs/random-walk-guidance/spec.md` (Requirements: *Random direction and distance*, *Arrival starts the next leg*, *Blocked target reroll*, *Reroll memory*, *Memory-guided bearing*), `openspec/changes/random-walk-no-map/design.md` D2–D5a, and `openspec/changes/random-walk-no-map/tasks.md` §1.

## Global Constraints

- All new production code lives in `com.randomwalk.app.walk`. The `walk` package MUST NOT import `android.*`. Allowed imports: `com.randomwalk.app.geo.LatLon`, `com.randomwalk.app.geo.GeoMath`, `com.randomwalk.app.location.LocationSample`, `kotlin.random.Random`, `kotlin.math.*`, `org.junit.*` (tests only).
- Bearing is always in `[0, 360)`; normalize with `GeoMath.normalizeBearingDegrees`.
- Leg distance is an integer in `50..100` inclusive (`RandomLegGenerator.MIN_DISTANCE_METERS = 50`, `MAX_DISTANCE_METERS = 100`).
- The exclusion band is ±`45.0` degrees around each excluded bearing (`EXCLUSION_HALF_WIDTH_DEGREES = 45f`).
- Arrival radius is `10.0` meters inclusive, continuous dwell is `1_500` ms inclusive. These values live only in `ArrivalTracker` (constructor defaults), never in UI code.
- Reroll memory: at most `6` entries; written only by `reroll()`, never by arrival; oldest evicted first; an entry is active only while the latest accepted location is at most `500.0` m from its position; cleared by `stop()`; never displayed, counted, or persisted.
- `remainingMeters` is the rounded straight-line (haversine) distance to the target, never a session total. `targetBearingDegrees` is `GeoMath.initialBearingDegrees(currentPosition, target)`.
- A session starts a leg only after a location sample. Before that it reports the waiting state and MUST NOT invent a target.
- Do NOT modify `WalkSurface`, `WalkScreenView`, `CompassController`, `MainActivity`, `FakeWalk`, or any resource. UI integration is §2 of `tasks.md` and is out of scope here; `WalkSurface` keeps rendering `FakeWalk` until then.
- Do not add Gradle dependencies or Android test dependencies.
- Build/test commands use JDK 17: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew ...`.
- Mark `1.1`–`1.4` in `openspec/changes/random-walk-no-map/tasks.md` only after the command named for that checkbox passes. Use conventional commit messages (`feat:`, `test:`), matching `git log`.

## Review Focus

1. Reroll memory holds six bearings that jointly block all 360° (about 60° apart) → a reroll must still produce a valid leg, must not hang, and must not keep the abandoned target. Pinned by `allDirectionsExcludedDropsOldestAndStillDraws` (Task 1) and `repeatedRerollsAtOneSpotAlwaysProduceALeg` (Task 4).
2. An excluded bearing near north (e.g. 350°) makes the band wrap across 0°/360° → no new bearing may land inside `[305°, 35°]`. Pinned by `exclusionBandWrapsAcrossNorth` (Task 1).
3. The walker stands exactly on the target before the dwell completes (remaining 0 m, bearing undefined) → no NaN, no crash, no new leg until 1.5 s. Pinned by `atTheTargetTheRemainingDistanceIsZeroAndTheBearingIsFinite` (Task 3).
4. The walker dips into the 10 m circle, leaves, then returns → the dwell restarts; time spent inside before leaving must not count. Pinned by `leavingTheRadiusResetsTheDwell`, `shortPassThroughDoesNotArrive` (Task 2) and `shortVisitDoesNotStartTheNextLeg` (Task 3).
5. `Location.time` arrives out of order or duplicated → arrival must not fire early; the dwell stays anchored to the first in-radius timestamp. Pinned by `outOfOrderTimestampsDoNotArriveEarly` (Task 2).

---

### Task 1: WalkLeg and RandomLegGenerator (tasks.md 1.1)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/walk/WalkLeg.kt`
- Create: `app/src/main/java/com/randomwalk/app/walk/RandomLegGenerator.kt`
- Create: `app/src/test/java/com/randomwalk/app/walk/RandomLegGeneratorTest.kt`

**Interfaces:**
- Consumes: `LatLon` and `GeoMath` from `com.randomwalk.app.geo`.
- Produces: `data class WalkLeg(val target: LatLon, val bearingDegrees: Float, val distanceMeters: Int)`
- Produces: `fun interface LegGenerator { fun next(from: LatLon, excludedBearings: List<Float> = emptyList()): WalkLeg }`
- Produces: `class RandomLegGenerator(private val random: Random = Random.Default) : LegGenerator` with `next(from, excludedBearings): WalkLeg`
- Produces: `RandomLegGenerator.MIN_DISTANCE_METERS = 50`, `MAX_DISTANCE_METERS = 100`, `EXCLUSION_HALF_WIDTH_DEGREES = 45f`

- [ ] **Step 1: Write the failing `RandomLegGeneratorTest`**

Create `app/src/test/java/com/randomwalk/app/walk/RandomLegGeneratorTest.kt`:

```kotlin
package com.randomwalk.app.walk

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomLegGeneratorTest {
    private val origin = LatLon(48.8566, 2.3522)

    private fun angularDistance(a: Float, b: Float): Float {
        val delta = abs((a - b) % 360f)
        return if (delta > 180f) 360f - delta else delta
    }

    @Test
    fun sameSeedProducesSameLeg() {
        assertEquals(
            RandomLegGenerator(Random(7)).next(origin),
            RandomLegGenerator(Random(7)).next(origin),
        )
    }

    @Test
    fun bearingAndDistanceStayInConfiguredRange() {
        val generator = RandomLegGenerator(Random(11))
        repeat(200) {
            val leg = generator.next(origin)
            assertTrue(leg.bearingDegrees >= 0f && leg.bearingDegrees < 360f)
            assertTrue(leg.distanceMeters in 50..100)
        }
    }

    @Test
    fun targetLiesAtRequestedBearingAndDistance() {
        val leg = RandomLegGenerator(Random(11)).next(origin)
        val radians = Math.toRadians(leg.bearingDegrees.toDouble())
        val expected = GeoMath.offsetMeters(
            origin,
            eastMeters = sin(radians) * leg.distanceMeters,
            northMeters = cos(radians) * leg.distanceMeters,
        )
        assertEquals(expected.latitude, leg.target.latitude, 1e-6)
        assertEquals(expected.longitude, leg.target.longitude, 1e-6)
        assertEquals(
            leg.distanceMeters.toDouble(),
            GeoMath.haversineMeters(origin, leg.target),
            0.5,
        )
        assertEquals(
            leg.bearingDegrees.toDouble(),
            GeoMath.initialBearingDegrees(origin, leg.target),
            0.01,
        )
    }

    @Test
    fun excludedBearingsAreAvoided() {
        val generator = RandomLegGenerator(Random(23))
        repeat(200) {
            val leg = generator.next(origin, listOf(0f, 90f))
            assertTrue(angularDistance(leg.bearingDegrees, 0f) > 45f)
            assertTrue(angularDistance(leg.bearingDegrees, 90f) > 45f)
        }
    }

    @Test
    fun exclusionBandWrapsAcrossNorth() {
        val generator = RandomLegGenerator(Random(29))
        repeat(200) {
            val leg = generator.next(origin, listOf(350f))
            assertTrue(angularDistance(leg.bearingDegrees, 350f) > 45f)
        }
    }

    @Test
    fun allDirectionsExcludedDropsOldestAndStillDraws() {
        val leg = RandomLegGenerator(Random(31))
            .next(origin, listOf(0f, 90f, 180f, 270f))
        assertTrue(angularDistance(leg.bearingDegrees, 0f) <= 45f)
        assertTrue(leg.distanceMeters in 50..100)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest --tests "com.randomwalk.app.walk.RandomLegGeneratorTest"`

Expected: compilation failure with `Unresolved reference: WalkLeg` and `Unresolved reference: RandomLegGenerator`.

- [ ] **Step 3: Implement `WalkLeg` and `RandomLegGenerator`**

`WalkLeg.kt` is the three-property data class above.

`RandomLegGenerator.kt` declares the `LegGenerator` fun interface (default `excludedBearings = emptyList()` lives on the interface method, because Kotlin overrides may not repeat defaults) and the class with constants. Bearing draw:

1. Normalize each excluded bearing into `[0, 360)` and treat it as a blocked closed interval `[b - 45, b + 45]`, splitting at 0/360 when it wraps.
2. Subtract the union of blocked intervals from `[0, 360)` to get allowed arcs and their total length.
3. If the total allowed length is `<= 1e-6`, remove the first (oldest) excluded bearing from a working copy and go back to step 1; repeat until an arc exists. With an empty working list, the allowed arc is the whole circle.
4. Draw `offset = random.nextDouble() * totalAllowedLength` and map it into the arcs **proportionally to arc length**, so the draw is uniform over allowed directions and, with no exclusions, uniform over `[0, 360)`.

Then draw `val distanceMeters = random.nextInt(MIN_DISTANCE_METERS, MAX_DISTANCE_METERS + 1)`, and build the target with:

```kotlin
val radians = Math.toRadians(bearingDegrees.toDouble())
val target = GeoMath.offsetMeters(
    from,
    eastMeters = sin(radians) * distanceMeters,
    northMeters = cos(radians) * distanceMeters,
)
```

Return `WalkLeg(target, bearingDegrees.toFloat(), distanceMeters)` with `bearingDegrees` normalized through `GeoMath.normalizeBearingDegrees`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest --tests "com.randomwalk.app.walk.RandomLegGeneratorTest"`

Expected: PASS, 6 tests.

- [ ] **Step 5: Mark 1.1 and commit**

Set `- [x]` on task `1.1` in `openspec/changes/random-walk-no-map/tasks.md`, then:

```bash
git add app/src/main/java/com/randomwalk/app/walk/WalkLeg.kt \
        app/src/main/java/com/randomwalk/app/walk/RandomLegGenerator.kt \
        app/src/test/java/com/randomwalk/app/walk/RandomLegGeneratorTest.kt \
        openspec/changes/random-walk-no-map/tasks.md
git commit -m "feat: generate random walk legs with direction exclusions"
```

---

### Task 2: ArrivalTracker (tasks.md 1.2)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/walk/ArrivalTracker.kt`
- Create: `app/src/test/java/com/randomwalk/app/walk/ArrivalTrackerTest.kt`

**Interfaces:**
- Consumes: `LatLon`, `GeoMath`, `LocationSample` (`latitude: Double`, `longitude: Double`, `accuracyMeters: Double`, `timestampMillis: Long`).
- Produces: `class ArrivalTracker(private val radiusMeters: Double = 10.0, private val dwellMillis: Long = 1_500L)` with `fun update(target: LatLon, sample: LocationSample): Boolean` and `fun reset()`

- [ ] **Step 1: Write the failing `ArrivalTrackerTest`**

Create `app/src/test/java/com/randomwalk/app/walk/ArrivalTrackerTest.kt`:

```kotlin
package com.randomwalk.app.walk

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.location.LocationSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalTrackerTest {
    private val target = LatLon(48.8566, 2.3522)

    private fun sampleAt(eastMeters: Double, northMeters: Double, timeMillis: Long): LocationSample {
        val point = GeoMath.offsetMeters(target, eastMeters, northMeters)
        return LocationSample(point.latitude, point.longitude, 5.0, timeMillis)
    }

    @Test
    fun arrivesAfterContinuousTenMeterDwellForOneAndAHalfSeconds() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(target, sampleAt(9.0, 0.0, 0L)))
        assertFalse(tracker.update(target, sampleAt(9.0, 0.0, 1_000L)))
        assertTrue(tracker.update(target, sampleAt(9.0, 0.0, 1_500L)))
    }

    @Test
    fun leavingTheRadiusResetsTheDwell() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(target, sampleAt(0.0, 0.0, 0L)))
        assertFalse(tracker.update(target, sampleAt(20.0, 0.0, 1_200L)))
        assertFalse(tracker.update(target, sampleAt(5.0, 0.0, 1_500L)))
        assertFalse(tracker.update(target, sampleAt(5.0, 0.0, 2_500L)))
        assertTrue(tracker.update(target, sampleAt(5.0, 0.0, 3_000L)))
    }

    @Test
    fun shortPassThroughDoesNotArrive() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(target, sampleAt(0.0, 0.0, 0L)))
        assertFalse(tracker.update(target, sampleAt(30.0, 0.0, 1_000L)))
        assertFalse(tracker.update(target, sampleAt(40.0, 0.0, 2_000L)))
    }

    @Test
    fun movingInsideTheRadiusKeepsTheDwell() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(target, sampleAt(9.0, 0.0, 0L)))
        assertFalse(tracker.update(target, sampleAt(-9.0, 0.0, 800L)))
        assertTrue(tracker.update(target, sampleAt(0.0, 9.0, 1_500L)))
    }

    @Test
    fun outOfOrderTimestampsDoNotArriveEarly() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(target, sampleAt(0.0, 0.0, 1_000L)))
        assertFalse(tracker.update(target, sampleAt(0.0, 0.0, 500L)))
        assertFalse(tracker.update(target, sampleAt(0.0, 0.0, 2_499L)))
        assertTrue(tracker.update(target, sampleAt(0.0, 0.0, 2_500L)))
    }

    @Test
    fun resetDropsTheCurrentDwell() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(target, sampleAt(0.0, 0.0, 0L)))
        tracker.reset()
        assertFalse(tracker.update(target, sampleAt(0.0, 0.0, 1_500L)))
        assertTrue(tracker.update(target, sampleAt(0.0, 0.0, 3_000L)))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest --tests "com.randomwalk.app.walk.ArrivalTrackerTest"`

Expected: compilation failure with `Unresolved reference: ArrivalTracker`.

- [ ] **Step 3: Implement `ArrivalTracker`**

Keep one nullable private field `insideSinceMillis: Long?`.

`update(target, sample)`:

1. Compute `distance = GeoMath.haversineMeters(LatLon(sample.latitude, sample.longitude), target)`.
2. If `distance > radiusMeters`, clear `insideSinceMillis` and return `false`.
3. Otherwise, if `insideSinceMillis == null`, set it to `sample.timestampMillis` and return `false`.
4. Return `sample.timestampMillis - insideSinceMillis >= dwellMillis` (a negative delta from an out-of-order sample is simply `false`; do not move the anchor).

`reset()` sets the field to `null`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest --tests "com.randomwalk.app.walk.ArrivalTrackerTest"`

Expected: PASS, 6 tests.

- [ ] **Step 5: Mark 1.2 and commit**

Set `- [x]` on task `1.2` in `openspec/changes/random-walk-no-map/tasks.md`, then:

```bash
git add app/src/main/java/com/randomwalk/app/walk/ArrivalTracker.kt \
        app/src/test/java/com/randomwalk/app/walk/ArrivalTrackerTest.kt \
        openspec/changes/random-walk-no-map/tasks.md
git commit -m "feat: detect arrival by continuous radius dwell"
```

---

### Task 3: WalkSession core (part of tasks.md 1.3)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/walk/WalkSession.kt`
- Create: `app/src/test/java/com/randomwalk/app/walk/WalkSessionTest.kt`

**Interfaces:**
- Consumes: `LegGenerator`, `WalkLeg`, `ArrivalTracker`, `LatLon`, `GeoMath`, `LocationSample`.
- Produces: `data class WalkState(val leg: WalkLeg? = null, val remainingMeters: Int? = null, val targetBearingDegrees: Float? = null)` with `val waitingForLocation: Boolean get() = leg == null`
- Produces: `class WalkSession(private val generator: LegGenerator, private val arrivalTracker: ArrivalTracker = ArrivalTracker(), private val onUpdate: (WalkState) -> Unit = {})` with `val state: WalkState`, `fun onLocation(sample: LocationSample)`
- `reroll()` and `stop()` are added in Task 4; do not add them yet.

- [ ] **Step 1: Write the failing core `WalkSessionTest`**

Create `app/src/test/java/com/randomwalk/app/walk/WalkSessionTest.kt`:

```kotlin
package com.randomwalk.app.walk

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.location.LocationSample
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSessionTest {
    private val origin = LatLon(48.8566, 2.3522)

    private fun sample(point: LatLon, atMillis: Long) =
        LocationSample(point.latitude, point.longitude, 5.0, atMillis)

    private fun legAt(
        bearingDegrees: Float,
        from: LatLon = origin,
        distanceMeters: Int = 100,
    ): WalkLeg {
        val radians = Math.toRadians(bearingDegrees.toDouble())
        return WalkLeg(
            target = GeoMath.offsetMeters(
                from,
                eastMeters = sin(radians) * distanceMeters,
                northMeters = cos(radians) * distanceMeters,
            ),
            bearingDegrees = bearingDegrees,
            distanceMeters = distanceMeters,
        )
    }

    private class RecordingGenerator(vararg legs: WalkLeg) : LegGenerator {
        data class Call(val from: LatLon, val excludedBearings: List<Float>)

        val calls = mutableListOf<Call>()
        private val legs = legs.toList()
        private var index = 0

        override fun next(from: LatLon, excludedBearings: List<Float>): WalkLeg {
            calls += Call(from, excludedBearings)
            return legs[minOf(index++, legs.lastIndex)]
        }
    }

    @Test
    fun waitsUntilAUsableLocationArrives() {
        val updates = mutableListOf<WalkState>()
        val session = WalkSession(RecordingGenerator(legAt(0f)), onUpdate = updates::add)
        assertTrue(session.state.waitingForLocation)
        assertNull(session.state.leg)
        assertNull(session.state.remainingMeters)
        assertTrue(updates.isEmpty())
    }

    @Test
    fun firstAcceptedLocationStartsALeg() {
        val generator = RecordingGenerator(legAt(0f))
        val session = WalkSession(generator)
        session.onLocation(sample(origin, 0L))

        assertEquals(origin, generator.calls.single().from)
        assertEquals(emptyList<Float>(), generator.calls.single().excludedBearings)
        assertEquals(legAt(0f), session.state.leg)
        assertTrue(!session.state.waitingForLocation)
        assertEquals(100, session.state.remainingMeters)
        assertEquals(0f, session.state.targetBearingDegrees!!, 0.5f)
    }

    @Test
    fun remainingDistanceIncreasesWhenWalkingAway() {
        val session = WalkSession(RecordingGenerator(legAt(0f)))
        session.onLocation(sample(origin, 0L))
        session.onLocation(sample(GeoMath.offsetMeters(origin, 0.0, -20.0), 5_000L))
        assertEquals(120, session.state.remainingMeters)
    }

    @Test
    fun targetBearingFollowsTheWalker() {
        val session = WalkSession(RecordingGenerator(legAt(0f)))
        session.onLocation(sample(origin, 0L))
        session.onLocation(sample(GeoMath.offsetMeters(origin, 10.0, 0.0), 5_000L))
        assertEquals(354.29, session.state.targetBearingDegrees!!.toDouble(), 0.2)
    }

    @Test
    fun arrivalStartsTheNextLegFromTheCurrentPosition() {
        val northLeg = legAt(0f)
        val eastLeg = legAt(90f, from = northLeg.target)
        val generator = RecordingGenerator(northLeg, eastLeg)
        val session = WalkSession(generator)

        session.onLocation(sample(origin, 0L))
        session.onLocation(sample(northLeg.target, 500L))
        session.onLocation(sample(northLeg.target, 2_000L))

        assertEquals(2, generator.calls.size)
        assertEquals(northLeg.target, generator.calls.last().from)
        assertEquals(eastLeg, session.state.leg)
    }

    @Test
    fun shortVisitDoesNotStartTheNextLeg() {
        val northLeg = legAt(0f)
        val generator = RecordingGenerator(northLeg, legAt(90f))
        val session = WalkSession(generator)

        session.onLocation(sample(origin, 0L))
        session.onLocation(sample(northLeg.target, 500L))
        session.onLocation(sample(GeoMath.offsetMeters(northLeg.target, 30.0, 0.0), 1_200L))

        assertEquals(1, generator.calls.size)
        assertEquals(northLeg, session.state.leg)
    }

    @Test
    fun atTheTargetTheRemainingDistanceIsZeroAndTheBearingIsFinite() {
        val northLeg = legAt(0f)
        val session = WalkSession(RecordingGenerator(northLeg, legAt(90f)))

        session.onLocation(sample(origin, 0L))
        session.onLocation(sample(northLeg.target, 500L))

        assertEquals(0, session.state.remainingMeters)
        assertTrue(session.state.targetBearingDegrees!!.isFinite())
        assertEquals(northLeg, session.state.leg)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest --tests "com.randomwalk.app.walk.WalkSessionTest"`

Expected: compilation failure with `Unresolved reference: WalkSession` / `WalkState`.

- [ ] **Step 3: Implement the session core in `WalkSession.kt`**

State fields: `private var currentLeg: WalkLeg? = null`, `private var currentPosition: LatLon? = null`, and `var state: WalkState = WalkState(); private set`.

`onLocation(sample)`:

1. Build `position = LatLon(sample.latitude, sample.longitude)` and store it as `currentPosition`.
2. If `currentLeg == null`, call the private `startLeg(position)` and return.
3. If `arrivalTracker.update(currentLeg.target, sample)` returns `true`, call `startLeg(position)`; this replaces the leg before emitting, so the view never sees a finished leg with 0 m.
4. Otherwise publish the updated remaining distance/bearing for the existing leg.

`startLeg(position)`:

1. `currentLeg = generator.next(position, excludedBearings = emptyList())` for now (Task 4 replaces the empty list with active memory).
2. `arrivalTracker.reset()`.
3. Publish the new state.

Publishing (`updateState`): compute `remainingMeters = GeoMath.haversineMeters(position, leg.target).roundToInt()` and `targetBearingDegrees = GeoMath.initialBearingDegrees(position, leg.target).toFloat()`, assign a new `WalkState(leg, remainingMeters, targetBearingDegrees)`, then call `onUpdate(state)`. Call it once per state change, and not at construction (the initial `WalkState()` already reports waiting).

- [ ] **Step 4: Run the core test to verify it passes**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest --tests "com.randomwalk.app.walk.WalkSessionTest"`

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit (do not mark 1.3 yet)**

Task 1.3 also requires reroll and memory, delivered in Task 4.

```bash
git add app/src/main/java/com/randomwalk/app/walk/WalkSession.kt \
        app/src/test/java/com/randomwalk/app/walk/WalkSessionTest.kt
git commit -m "feat: own the active leg and remaining distance in a walk session"
```

---

### Task 4: Reroll, reroll memory, full suite (tasks.md 1.3 and 1.4)

**Files:**
- Modify: `app/src/main/java/com/randomwalk/app/walk/WalkSession.kt`
- Modify: `app/src/test/java/com/randomwalk/app/walk/WalkSessionTest.kt`
- Modify: `openspec/changes/random-walk-no-map/tasks.md`

**Interfaces:**
- Produces: `WalkSession.reroll()`, `WalkSession.stop()`
- Produces: `WalkSession.MAX_REROLL_MEMORY = 6`, `WalkSession.REROLL_MEMORY_RADIUS_METERS = 500.0`
- Consumes: everything from Tasks 1–3; `startLeg` now passes active excluded bearings to `generator.next`.

- [ ] **Step 1: Append the failing reroll/memory tests to `WalkSessionTest`**

Add inside `WalkSessionTest` (the `legAt`, `sample`, and `RecordingGenerator` helpers already exist):

```kotlin
    @Test
    fun rerollWithoutAFixIsANoOp() {
        val generator = RecordingGenerator(legAt(0f))
        val session = WalkSession(generator)
        session.reroll()
        assertTrue(generator.calls.isEmpty())
        assertTrue(session.state.waitingForLocation)
    }

    @Test
    fun rerollStartsANewLegFromTheCurrentPosition() {
        val generator = RecordingGenerator(legAt(0f), legAt(90f))
        val session = WalkSession(generator)
        session.onLocation(sample(origin, 0L))
        val moved = GeoMath.offsetMeters(origin, 20.0, 0.0)
        session.onLocation(sample(moved, 5_000L))

        session.reroll()

        assertEquals(moved, generator.calls.last().from)
        assertEquals(legAt(90f), session.state.leg)
    }

    @Test
    fun rerollRecordsTheAbandonedBearing() {
        val generator = RecordingGenerator(legAt(0f), legAt(90f))
        val session = WalkSession(generator)
        session.onLocation(sample(origin, 0L))

        session.reroll()

        assertEquals(listOf(0f), generator.calls.last().excludedBearings)
    }

    @Test
    fun arrivalDoesNotRecordMemory() {
        val northLeg = legAt(0f)
        val generator = RecordingGenerator(northLeg, legAt(90f))
        val session = WalkSession(generator)
        session.onLocation(sample(origin, 0L))
        session.onLocation(sample(northLeg.target, 500L))
        session.onLocation(sample(northLeg.target, 2_000L))

        assertEquals(2, generator.calls.size)
        assertEquals(emptyList<Float>(), generator.calls.last().excludedBearings)
    }

    @Test
    fun memoryKeepsAtMostSixEntriesAndDropsTheOldest() {
        val generator = RecordingGenerator(
            legAt(0f), legAt(10f), legAt(20f), legAt(30f),
            legAt(40f), legAt(50f), legAt(60f), legAt(70f),
        )
        val session = WalkSession(generator)
        session.onLocation(sample(origin, 0L))
        repeat(7) { session.reroll() }

        assertEquals(listOf(10f, 20f, 30f, 40f, 50f, 60f), generator.calls.last().excludedBearings)
    }

    @Test
    fun entryBeyondFiveHundredMetersNoLongerExcludes() {
        val generator = RecordingGenerator(legAt(0f), legAt(90f), legAt(180f))
        val session = WalkSession(generator)
        session.onLocation(sample(origin, 0L))
        session.reroll()

        val far = GeoMath.offsetMeters(origin, 600.0, 0.0)
        session.onLocation(sample(far, 5_000L))
        session.reroll()

        assertEquals(listOf(90f), generator.calls.last().excludedBearings)
    }

    @Test
    fun arrivalAvoidsTheActiveMemory() {
        val generator = RecordingGenerator(legAt(0f), legAt(90f), legAt(180f))
        val session = WalkSession(generator)
        session.onLocation(sample(origin, 0L))
        session.reroll()

        val rerollTarget = legAt(90f).target
        session.onLocation(sample(rerollTarget, 5_000L))
        session.onLocation(sample(rerollTarget, 6_500L))

        assertEquals(listOf(0f), generator.calls.last().excludedBearings)
    }

    @Test
    fun stopClearsTheMemoryAndTheActiveLeg() {
        val generator = RecordingGenerator(legAt(0f), legAt(90f), legAt(180f))
        val session = WalkSession(generator)
        session.onLocation(sample(origin, 0L))
        session.reroll()

        session.stop()
        assertTrue(session.state.waitingForLocation)
        assertNull(session.state.leg)

        session.onLocation(sample(origin, 10_000L))
        assertEquals(emptyList<Float>(), generator.calls.last().excludedBearings)
    }

    @Test
    fun repeatedRerollsAtOneSpotAlwaysProduceALeg() {
        val session = WalkSession(RandomLegGenerator(Random(5)))
        session.onLocation(sample(origin, 0L))
        repeat(8) { session.reroll() }

        assertTrue(!session.state.waitingForLocation)
        assertTrue(session.state.leg!!.bearingDegrees >= 0f)
        assertTrue(session.state.leg!!.bearingDegrees < 360f)
        assertTrue(session.state.leg!!.distanceMeters in 50..100)
    }
```

Add `import kotlin.random.Random` to the test file when adding these.

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest --tests "com.randomwalk.app.walk.WalkSessionTest"`

Expected: compilation failure with `Unresolved reference: reroll` and `Unresolved reference: stop`.

- [ ] **Step 3: Implement reroll, memory, and stop in `WalkSession.kt`**

Add `private data class RememberedBearing(val bearingDegrees: Float, val position: LatLon)` and `private val rerollMemory = mutableListOf<RememberedBearing>()`.

`reroll()`:

1. Read `position = currentPosition ?: return` and `leg = currentLeg ?: return`.
2. Append `RememberedBearing(leg.bearingDegrees, position)`. If the list now holds more than `MAX_REROLL_MEMORY`, remove index 0 (oldest).
3. Call `startLeg(position)`.

`startLeg(position)` now calls:

```kotlin
generator.next(position, excludedBearings = activeExclusions(position))
```

where `activeExclusions(position)` keeps, in insertion order, the bearings whose stored position is at most `REROLL_MEMORY_RADIUS_METERS` from `position`, measured with `GeoMath.haversineMeters`. Entries beyond that distance stay in memory but are not passed to the generator.

`stop()`: clear `rerollMemory`, set `currentLeg = null` and `currentPosition = null`, call `arrivalTracker.reset()`, then assign `state = WalkState()` and call `onUpdate(state)`.

Keep the generator's all-directions-excluded handling untouched: the session passes all active bearings, and `RandomLegGenerator` drops the oldest for that draw only. Do not mutate session memory from the generator.

- [ ] **Step 4: Run the focused test to verify it passes**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest --tests "com.randomwalk.app.walk.WalkSessionTest"`

Expected: PASS, 16 tests.

- [ ] **Step 5: Run the full JVM suite**

Run: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL; the existing `geo`, `compass`, `location`, `data`, and `ui` unit tests still pass alongside the three new walk test classes.

- [ ] **Step 6: Mark 1.3 and 1.4 and commit**

Set `- [x]` on tasks `1.3` and `1.4` in `openspec/changes/random-walk-no-map/tasks.md`, then:

```bash
git add app/src/main/java/com/randomwalk/app/walk/WalkSession.kt \
        app/src/test/java/com/randomwalk/app/walk/WalkSessionTest.kt \
        openspec/changes/random-walk-no-map/tasks.md
git commit -m "feat: reroll blocked legs while remembering abandoned bearings"
```

---

## Self-Review Notes

- **Spec coverage:** `Random direction and distance` → Task 1; `Arrival starts the next leg` → Tasks 2–4; `Blocked target reroll` / `Reroll memory` / `Memory-guided bearing` → Task 4; remaining UI-facing requirements (`Single-purpose leg view`, `World-fixed target arrow`, `Remaining distance`, hint, privacy, permission) belong to `tasks.md` §2–§4 and are deliberately not implemented here.
- **Deliberate boundary:** `WalkSession` exposes only the snapshot, `onLocation`, `reroll`, and `stop`; §2 will consume these through `state`, so no Android type leaks into the `walk` package.
