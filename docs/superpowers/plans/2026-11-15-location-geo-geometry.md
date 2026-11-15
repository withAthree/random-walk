# Location and Geographic Geometry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the tested geographic primitives, 2 km metric tile grid, filtered fused-location sampling, and current-edge progress logging that the route and data layers will consume.

**Architecture:** `com.randomwalk.app.geo` is pure Kotlin/JVM: WGS-84 haversine/bearing math, point-to-polyline projection, and a latitude-banded 2 km tile grid. `com.randomwalk.app.location` separates a pure `LocationFilter` from the Android `LocationSampler` (Play Services fused provider). A debug-only `LocationDebugProbe` wired from `MainActivity` logs accepted fixes and projection progress on device; it adds no view and never runs in a non-debuggable build.

**Tech Stack:** Kotlin 2.0.21, Android SDK 35 (minSdk 26), Play Services Location 21.3.0, JUnit 4. No new Gradle dependencies.

**Spec:** `openspec/changes/random-walk-app/design.md` (D6, D8, D9), `openspec/changes/random-walk-app/tasks.md` §3, `openspec/changes/random-walk-app/specs/walk-guidance/spec.md` (arrival at junctions), `openspec/changes/random-walk-app/specs/street-data/spec.md` (2 km tiles).

## Global Constraints

- All coordinates are WGS-84. No GCJ-02 conversion and no map rendering in this batch.
- Distances and bearings use a spherical Earth with `EARTH_RADIUS_METERS = 6_371_008.8` and `METERS_PER_DEGREE_LAT = 111_194.93`. Do not mix in ellipsoidal or UTM formulas.
- Tile size is exactly **2000 m** on the ground at the tile row's center latitude. Tile height is `2000 / 111_194.93` degrees of latitude; longitude width is `LAT_STEP_DEGREES / cos(centerLatitude)` so high-latitude tiles are not as narrow as a fixed-degree grid. The center latitude used for width is clamped to ±85° to avoid division by zero.
- `LatLon` accepts latitude in `[-90, 90]` and longitude in `[-180, 180]`; `GeoMath.normalizeLongitudeDegrees` maps 180° to -180°. Tiles do not span the antimeridian.
- Location request: `PRIORITY_HIGH_ACCURACY`, production interval **5000 ms**, minimum update distance **5 m**. The debug probe overrides this to **1000 ms / 1 m** so a short walk produces per-meter `edge_progress` lines; production behavior is unchanged. Filter thresholds: max accuracy **50 m**, max age **10_000 ms**, max walking speed **3.0 m/s**, teleport grace **30 m**.
- `LocationFilter` is pure Kotlin/JVM. `LocationSampler` is the only class that touches `FusedLocationProviderClient`; `MainActivity` must not request or filter locations directly.
- The debug probe is active only when `ApplicationInfo.FLAG_DEBUGGABLE` is set. It adds no view, text, permission, dependency, or release behavior. The walk screen still shows only the arrow and the distance.
- Do not change the compass, route generation, start screen, or SQLite schema in this batch.
- Builds use JDK 17: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2`. The default JDK 25 fails with `java.lang.IllegalArgumentException: 25`.
- WSL has no attached Android device. JVM tests and APK/androidTest compilation run in WSL; device logcat checks run on the Windows host. For Windows commands, install the APK copied from WSL or use a Windows-side Gradle/JDK; do not reuse the WSL `JAVA_HOME` path there. The available straight-line walking space is about **10 m**, so device checks use 10–15 m; do not require a 60–100 m walk. Do not mark 3.3 or 3.4 complete in `openspec/changes/random-walk-app/tasks.md` until the device check passes there.

## Review Focus

- A GPS fix exactly at the 50 m accuracy boundary or exactly 10 s old → accepted, not rejected by an off-by-one. Pinned by `acceptsAccuracyAtLimit` and `acceptsFixAtAgeLimit` in Task 3.
- A single GPS jump followed by a consistent fix → the filter recovers instead of rejecting every later fix. Pinned by `recoversAfterTeleportWhenNextFixIsConsistent` in Task 3.
- A polyline with duplicate vertices or all-identical vertices → projection returns a finite point and progress, never NaN or a divide-by-zero. Pinned by `overlappingVerticesAreSkipped` and `zeroLengthPolylineProjectsToItsOnlyPoint` in Task 1.
- The user walks past the end of the current edge → projection clamps to the endpoint and progress saturates at 100%, so arrival detection cannot see progress above 100%. Pinned by `pointBeyondEndClampsToEnd` in Task 1.
- A tile at 78°N or with negative coordinates → its bbox still contains the point, round-trips through `tile_id`, and stays about 2 km wide and tall. Pinned by `highLatitudeTileKeepsMetricSize` and `negativeCoordinatesRoundTrip` in Task 2.

---

### Task 1: Geographic math (3.1)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/geo/LatLon.kt`
- Create: `app/src/main/java/com/randomwalk/app/geo/GeoMath.kt`
- Test: `app/src/test/java/com/randomwalk/app/geo/GeoMathTest.kt`

**Interfaces:**
- Produces: `data class LatLon(val latitude: Double, val longitude: Double)`
- Produces: `GeoMath.EARTH_RADIUS_METERS: Double`
- Produces: `GeoMath.METERS_PER_DEGREE_LAT: Double`
- Produces: `GeoMath.haversineMeters(a: LatLon, b: LatLon): Double`
- Produces: `GeoMath.initialBearingDegrees(from: LatLon, to: LatLon): Double` in `[0, 360)`
- Produces: `GeoMath.normalizeBearingDegrees(degrees: Double): Double`
- Produces: `GeoMath.normalizeLongitudeDegrees(longitude: Double): Double`
- Produces: `GeoMath.offsetMeters(origin: LatLon, eastMeters: Double, northMeters: Double): LatLon`
- Produces: `GeoMath.projectOntoPolyline(point: LatLon, polyline: List<LatLon>): PolylineProjection`
- Produces: `data class PolylineProjection(val point: LatLon, val segmentIndex: Int, val segmentFraction: Double, val progressMeters: Double, val progressFraction: Double, val deviationMeters: Double)`
- Consumes: nothing.

- [ ] **Step 1: Write the failing geographic-math tests**

Create `app/src/test/java/com/randomwalk/app/geo/GeoMathTest.kt`:

```kotlin
package com.randomwalk.app.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {

    @Test
    fun haversineMatchesOneDegreeAtEquator() {
        assertEquals(
            111_194.93,
            GeoMath.haversineMeters(LatLon(0.0, 0.0), LatLon(0.0, 1.0)),
            0.5,
        )
        assertEquals(
            111_194.93,
            GeoMath.haversineMeters(LatLon(0.0, 0.0), LatLon(1.0, 0.0)),
            0.5,
        )
    }

    @Test
    fun bearingCardinalDirections() {
        val origin = LatLon(0.0, 0.0)
        assertEquals(0.0, GeoMath.initialBearingDegrees(origin, LatLon(1.0, 0.0)), 0.01)
        assertEquals(90.0, GeoMath.initialBearingDegrees(origin, LatLon(0.0, 1.0)), 0.01)
        assertEquals(180.0, GeoMath.initialBearingDegrees(origin, LatLon(-1.0, 0.0)), 0.01)
        assertEquals(270.0, GeoMath.initialBearingDegrees(origin, LatLon(0.0, -1.0)), 0.01)
    }

    @Test
    fun longitudeNormalizationWrapsAt180() {
        assertEquals(0.0, GeoMath.normalizeLongitudeDegrees(360.0), 1e-9)
        assertEquals(-180.0, GeoMath.normalizeLongitudeDegrees(180.0), 1e-9)
        assertEquals(170.0, GeoMath.normalizeLongitudeDegrees(-190.0), 1e-9)
    }

    @Test
    fun offsetMetersIsMetricAtEquatorAndHighLatitude() {
        val equator = LatLon(0.0, 0.0)
        assertEquals(
            100.0,
            GeoMath.haversineMeters(equator, GeoMath.offsetMeters(equator, 100.0, 0.0)),
            0.2,
        )
        assertEquals(
            100.0,
            GeoMath.haversineMeters(equator, GeoMath.offsetMeters(equator, 0.0, 100.0)),
            0.2,
        )

        val highLatitude = LatLon(60.0, 10.0)
        assertEquals(
            100.0,
            GeoMath.haversineMeters(
                highLatitude,
                GeoMath.offsetMeters(highLatitude, 100.0, 0.0),
            ),
            0.2,
        )
    }

    @Test
    fun projectionReturnsPointProgressAndDeviation() {
        val edge = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.002)) // ~222.4 m east
        val projection = GeoMath.projectOntoPolyline(LatLon(0.0005, 0.0005), edge)

        assertEquals(0, projection.segmentIndex)
        assertEquals(0.25, projection.segmentFraction, 0.001)
        assertEquals(0.25, projection.progressFraction, 0.001)
        assertEquals(55.6, projection.deviationMeters, 0.5)
        assertEquals(0.0, projection.point.latitude, 1e-6)
        assertEquals(0.0005, projection.point.longitude, 1e-6)
    }

    @Test
    fun pointBeyondEndClampsToEnd() {
        val edge = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001))
        val projection = GeoMath.projectOntoPolyline(LatLon(0.0, 0.002), edge)

        assertEquals(1.0, projection.progressFraction, 1e-9)
        assertEquals(edge.last().latitude, projection.point.latitude, 1e-9)
        assertEquals(edge.last().longitude, projection.point.longitude, 1e-9)
    }

    @Test
    fun pointBeforeStartClampsToStart() {
        val edge = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001))
        val projection = GeoMath.projectOntoPolyline(LatLon(0.0, -0.001), edge)

        assertEquals(0.0, projection.progressFraction, 1e-9)
        assertEquals(edge.first().latitude, projection.point.latitude, 1e-9)
        assertEquals(edge.first().longitude, projection.point.longitude, 1e-9)
    }

    @Test
    fun overlappingVerticesAreSkipped() {
        val edge = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.0), LatLon(0.0, 0.001))
        val projection = GeoMath.projectOntoPolyline(LatLon(0.0005, 0.0005), edge)

        assertEquals(1, projection.segmentIndex)
        assertEquals(0.5, projection.progressFraction, 0.001)
        assertEquals(55.6, projection.deviationMeters, 0.5)
    }

    @Test
    fun zeroLengthPolylineProjectsToItsOnlyPoint() {
        val edge = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.0))
        val projection = GeoMath.projectOntoPolyline(LatLon(0.001, 0.001), edge)

        assertEquals(0.0, projection.progressMeters, 1e-9)
        assertEquals(0.0, projection.progressFraction, 1e-9)
        assertEquals(edge.first().latitude, projection.point.latitude, 1e-9)
        assertEquals(edge.first().longitude, projection.point.longitude, 1e-9)
        assertEquals(157.2, projection.deviationMeters, 1.0)
    }

    @Test
    fun reversedPolylineMeasuresProgressFromNewFirstVertex() {
        val forward = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001))
        val reversed = forward.reversed()
        val point = LatLon(0.0, 0.00025)

        assertEquals(0.25, GeoMath.projectOntoPolyline(point, forward).progressFraction, 1e-9)
        assertEquals(0.75, GeoMath.projectOntoPolyline(point, reversed).progressFraction, 1e-9)
        assertEquals(270.0, GeoMath.initialBearingDegrees(reversed.first(), reversed.last()), 0.01)
    }

    @Test
    fun singlePointPolylineIsSafe() {
        val projection = GeoMath.projectOntoPolyline(
            LatLon(0.001, 0.0),
            listOf(LatLon(0.0, 0.0)),
        )

        assertEquals(0.0, projection.progressFraction, 1e-9)
        assertEquals(111.2, projection.deviationMeters, 0.5)
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.geo.GeoMathTest'
```

Expected: compilation fails with unresolved reference `LatLon` or `GeoMath`.

- [ ] **Step 3: Implement `LatLon` and `GeoMath`**

Create `LatLon.kt`:

```kotlin
package com.randomwalk.app.geo

data class LatLon(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "latitude out of range: $latitude"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "longitude out of range: $longitude"
        }
    }
}
```

Create `GeoMath.kt`:

```kotlin
package com.randomwalk.app.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    const val EARTH_RADIUS_METERS = 6_371_008.8
    const val METERS_PER_DEGREE_LAT = 111_194.93

    fun haversineMeters(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(normalizeLongitudeDegrees(b.longitude - a.longitude))
        val h = sin(dLat / 2.0).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLon / 2.0).let { it * it }
        return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }

    fun initialBearingDegrees(from: LatLon, to: LatLon): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(
            normalizeLongitudeDegrees(to.longitude - from.longitude),
        )
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return if (x == 0.0 && y == 0.0) 0.0 else {
            normalizeBearingDegrees(Math.toDegrees(atan2(y, x)))
        }
    }

    fun normalizeBearingDegrees(degrees: Double): Double {
        val normalized = degrees % 360.0
        return if (normalized < 0.0) normalized + 360.0 else normalized
    }

    fun normalizeLongitudeDegrees(longitude: Double): Double {
        var normalized = longitude % 360.0
        if (normalized >= 180.0) normalized -= 360.0
        if (normalized < -180.0) normalized += 360.0
        return normalized
    }

    fun offsetMeters(origin: LatLon, eastMeters: Double, northMeters: Double): LatLon {
        val latitude = (origin.latitude + northMeters / METERS_PER_DEGREE_LAT)
            .coerceIn(-90.0, 90.0)
        val longitude = normalizeLongitudeDegrees(
            origin.longitude + eastMeters / metersPerDegreeLongitude(origin.latitude),
        )
        return LatLon(latitude, longitude)
    }

    fun projectOntoPolyline(point: LatLon, polyline: List<LatLon>): PolylineProjection {
        require(polyline.isNotEmpty()) { "polyline must not be empty" }

        if (polyline.size == 1) {
            return PolylineProjection(
                point = polyline[0],
                segmentIndex = 0,
                segmentFraction = 0.0,
                progressMeters = 0.0,
                progressFraction = 0.0,
                deviationMeters = haversineMeters(point, polyline[0]),
            )
        }

        val lengths = DoubleArray(polyline.lastIndex) { index ->
            haversineMeters(polyline[index], polyline[index + 1])
        }
        val prefix = DoubleArray(polyline.size)
        for (index in lengths.indices) {
            prefix[index + 1] = prefix[index] + lengths[index]
        }
        val totalLength = prefix.last()

        var bestIndex = 0
        var bestFraction = 0.0
        var bestEast = 0.0
        var bestNorth = 0.0
        var bestDeviation = Double.POSITIVE_INFINITY

        for (index in lengths.indices) {
            val a = toLocal(point, polyline[index])
            val b = toLocal(point, polyline[index + 1])
            val dx = b.east - a.east
            val dy = b.north - a.north
            val lengthSquared = dx * dx + dy * dy
            val fraction = if (lengthSquared <= 1e-12) {
                0.0
            } else {
                (((0.0 - a.east) * dx + (0.0 - a.north) * dy) / lengthSquared)
                    .coerceIn(0.0, 1.0)
            }
            val east = a.east + fraction * dx
            val north = a.north + fraction * dy
            val deviation = hypot(east, north)
            if (deviation < bestDeviation) {
                bestDeviation = deviation
                bestIndex = index
                bestFraction = fraction
                bestEast = east
                bestNorth = north
            }
        }

        val projected = offsetMeters(point, bestEast, bestNorth)
        val progress = if (totalLength == 0.0) {
            0.0
        } else {
            prefix[bestIndex] + bestFraction * lengths[bestIndex]
        }
        return PolylineProjection(
            point = projected,
            segmentIndex = bestIndex,
            segmentFraction = bestFraction,
            progressMeters = progress,
            progressFraction = if (totalLength == 0.0) 0.0 else progress / totalLength,
            deviationMeters = haversineMeters(point, projected),
        )
    }

    private fun toLocal(origin: LatLon, point: LatLon): LocalPoint {
        val east = normalizeLongitudeDegrees(point.longitude - origin.longitude) *
            metersPerDegreeLongitude(origin.latitude)
        val north = (point.latitude - origin.latitude) * METERS_PER_DEGREE_LAT
        return LocalPoint(east, north)
    }

    private fun metersPerDegreeLongitude(latitude: Double): Double =
        METERS_PER_DEGREE_LAT * max(cos(Math.toRadians(latitude)), 1e-6)

    private data class LocalPoint(val east: Double, val north: Double)
}

data class PolylineProjection(
    val point: LatLon,
    val segmentIndex: Int,
    val segmentFraction: Double,
    val progressMeters: Double,
    val progressFraction: Double,
    val deviationMeters: Double,
)
```

- [ ] **Step 4: Run the test and verify it passes**

Run the same command. Expected: `BUILD SUCCESSFUL`, all 11 `GeoMathTest` tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/randomwalk/app/geo/LatLon.kt \
  app/src/main/java/com/randomwalk/app/geo/GeoMath.kt \
  app/src/test/java/com/randomwalk/app/geo/GeoMathTest.kt
git commit -m "feat: 实现地理距离与折线投影 (Task 3.1)"
```

---

### Task 2: 2 km metric tile grid (3.2)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/geo/TileGrid.kt`
- Test: `app/src/test/java/com/randomwalk/app/geo/TileGridTest.kt`

**Interfaces:**
- Produces: `data class TileBounds(val south: Double, val west: Double, val north: Double, val east: Double)` with `centerLatitude`, `centerLongitude`, and `contains(point: LatLon): Boolean`
- Produces: `data class Tile(val id: Long, val bounds: TileBounds)` with `center: LatLon`
- Produces: `TileGrid.TILE_SIZE_METERS: Double = 2000.0`
- Produces: `TileGrid.LAT_STEP_DEGREES: Double = 2000.0 / 111_194.93`
- Produces: `TileGrid.tileIdAt(latitude: Double, longitude: Double): Long`
- Produces: `TileGrid.tileBounds(tileId: Long): TileBounds`
- Produces: `TileGrid.tileFor(point: LatLon): Tile`
- Consumes: `LatLon`, `GeoMath.normalizeLongitudeDegrees`, `GeoMath.haversineMeters` (tests only).

- [ ] **Step 1: Write the failing tile-grid tests**

Create `app/src/test/java/com/randomwalk/app/geo/TileGridTest.kt`:

```kotlin
package com.randomwalk.app.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGridTest {

    @Test
    fun originTileContainsOriginAndRoundTrips() {
        val tile = TileGrid.tileFor(LatLon(0.0, 0.0))

        assertTrue(tile.bounds.contains(LatLon(0.0, 0.0)))
        assertEquals(tile.id, TileGrid.tileIdAt(tile.center.latitude, tile.center.longitude))
        assertEquals(
            2000.0,
            GeoMath.haversineMeters(
                LatLon(tile.bounds.south, tile.center.longitude),
                LatLon(tile.bounds.north, tile.center.longitude),
            ),
            1.0,
        )
        assertEquals(
            2000.0,
            GeoMath.haversineMeters(
                LatLon(tile.center.latitude, tile.bounds.west),
                LatLon(tile.center.latitude, tile.bounds.east),
            ),
            1.0,
        )
    }

    @Test
    fun negativeCoordinatesRoundTrip() {
        val point = LatLon(-33.8688, 151.2093)
        val tile = TileGrid.tileFor(point)

        assertTrue(tile.bounds.contains(point))
        assertTrue(tile.bounds.south < 0.0)
        assertTrue(tile.bounds.west > 0.0)
        assertEquals(tile.id, TileGrid.tileIdAt(tile.center.latitude, tile.center.longitude))
    }

    @Test
    fun westNegativeCoordinatesRoundTrip() {
        val point = LatLon(48.8566, -2.3522)
        val tile = TileGrid.tileFor(point)

        assertTrue(tile.bounds.contains(point))
        assertTrue(tile.bounds.west < 0.0)
        assertEquals(tile.id, TileGrid.tileIdAt(tile.center.latitude, tile.center.longitude))
    }

    @Test
    fun highLatitudeTileKeepsMetricSize() {
        val point = LatLon(78.2232, 15.6469)
        val tile = TileGrid.tileFor(point)

        assertTrue(tile.bounds.contains(point))
        assertEquals(
            2000.0,
            GeoMath.haversineMeters(
                LatLon(tile.center.latitude, tile.bounds.west),
                LatLon(tile.center.latitude, tile.bounds.east),
            ),
            1.0,
        )
        assertEquals(
            2000.0,
            GeoMath.haversineMeters(
                LatLon(tile.bounds.south, tile.center.longitude),
                LatLon(tile.bounds.north, tile.center.longitude),
            ),
            1.0,
        )
        assertEquals(tile.id, TileGrid.tileIdAt(tile.center.latitude, tile.center.longitude))
    }

    @Test
    fun adjacentRowsHaveDifferentIds() {
        val current = TileGrid.tileIdAt(0.0, 0.0)
        val next = TileGrid.tileIdAt(TileGrid.LAT_STEP_DEGREES, 0.0)

        assertTrue(current != next)
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.geo.TileGridTest'
```

Expected: compilation fails with unresolved reference `TileGrid`.

- [ ] **Step 3: Implement `TileGrid`**

Create `TileGrid.kt`:

```kotlin
package com.randomwalk.app.geo

import kotlin.math.cos
import kotlin.math.floor

data class TileBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    val centerLatitude: Double get() = (south + north) / 2.0
    val centerLongitude: Double get() = (west + east) / 2.0

    /** Half-open: the north and east edges belong to the next tile. */
    fun contains(point: LatLon): Boolean =
        point.latitude >= south && point.latitude < north &&
            point.longitude >= west && point.longitude < east
}

data class Tile(val id: Long, val bounds: TileBounds) {
    val center: LatLon get() = LatLon(bounds.centerLatitude, bounds.centerLongitude)
}

object TileGrid {
    const val TILE_SIZE_METERS = 2_000.0
    const val LAT_STEP_DEGREES = TILE_SIZE_METERS / GeoMath.METERS_PER_DEGREE_LAT

    private const val MAX_GRID_LATITUDE = 85.0

    fun tileFor(point: LatLon): Tile {
        val id = tileIdAt(point.latitude, point.longitude)
        return Tile(id, tileBounds(id))
    }

    fun tileIdAt(latitude: Double, longitude: Double): Long {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "latitude out of range: $latitude"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "longitude out of range: $longitude"
        }

        val latitudeIndex = floor(latitude / LAT_STEP_DEGREES).toInt()
        val longitudeIndex = floor(
            GeoMath.normalizeLongitudeDegrees(longitude) /
                longitudeStepDegrees(latitudeIndex),
        ).toInt()
        return encode(latitudeIndex, longitudeIndex)
    }

    fun tileBounds(tileId: Long): TileBounds {
        val latitudeIndex = (tileId shr 32).toInt()
        val longitudeIndex = tileId.toInt()
        val step = longitudeStepDegrees(latitudeIndex)

        return TileBounds(
            south = (latitudeIndex * LAT_STEP_DEGREES).coerceIn(-90.0, 90.0),
            west = (longitudeIndex * step).coerceIn(-180.0, 180.0),
            north = ((latitudeIndex + 1) * LAT_STEP_DEGREES).coerceIn(-90.0, 90.0),
            east = ((longitudeIndex + 1) * step).coerceIn(-180.0, 180.0),
        )
    }

    private fun longitudeStepDegrees(latitudeIndex: Int): Double {
        val centerLatitude = ((latitudeIndex + 0.5) * LAT_STEP_DEGREES)
            .coerceIn(-MAX_GRID_LATITUDE, MAX_GRID_LATITUDE)
        return LAT_STEP_DEGREES / cos(Math.toRadians(centerLatitude))
    }

    private fun encode(latitudeIndex: Int, longitudeIndex: Int): Long =
        (latitudeIndex.toLong() shl 32) or
            (longitudeIndex.toLong() and 0xFFFF_FFFFL)
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run the same command. Expected: `BUILD SUCCESSFUL`, all 5 `TileGridTest` tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/randomwalk/app/geo/TileGrid.kt \
  app/src/test/java/com/randomwalk/app/geo/TileGridTest.kt
git commit -m "feat: 实现 2km 米制瓦片网格 (Task 3.2)"
```

---

### Task 3: Fused-location sampling and filtering (3.3)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/location/LocationFilter.kt`
- Create: `app/src/main/java/com/randomwalk/app/location/LocationSampler.kt`
- Create: `app/src/main/java/com/randomwalk/app/debug/LocationDebugProbe.kt`
- Modify: `app/src/main/java/com/randomwalk/app/MainActivity.kt`
- Test: `app/src/test/java/com/randomwalk/app/location/LocationFilterTest.kt`
- Test: `app/src/test/java/com/randomwalk/app/location/LocationSamplingConfigTest.kt`

**Interfaces:**
- Produces: `data class LocationSample(val latitude: Double, val longitude: Double, val accuracyMeters: Double, val timestampMillis: Long)`
- Produces: `enum class LocationRejection { INVALID_COORDINATES, POOR_ACCURACY, STALE, TELEPORT }`
- Produces: `sealed interface LocationDecision { data class Accepted(val sample: LocationSample); data class Rejected(val reason: LocationRejection) }`
- Produces: `LocationFilter(maxAccuracyMeters: Double = 50.0, maxAgeMillis: Long = 10_000, maxSpeedMetersPerSecond: Double = 3.0, teleportGraceMeters: Double = 30.0)`
- Produces: `LocationFilter.evaluate(sample: LocationSample, nowMillis: Long): LocationDecision`
- Produces: `LocationFilter.reset()`
- Produces: `data class LocationSamplingConfig(val intervalMillis: Long, val minDistanceMeters: Float)` with `PRODUCTION = 5000 ms / 5 m` and `DEBUG = 1000 ms / 1 m`.
- Produces: `LocationSampler(context: Context, filter: LocationFilter = LocationFilter(), onAccepted: (LocationSample) -> Unit, onRejected: (LocationRejection) -> Unit = {}, clock: () -> Long = System::currentTimeMillis, config: LocationSamplingConfig = LocationSamplingConfig.PRODUCTION)`
- Produces: `LocationSampler.start()` and `LocationSampler.stop()`
- Produces: `LocationDebugProbe(context: Context).start()` and `.stop()`
- Consumes: `GeoMath.haversineMeters`, `LatLon`, `LocationPermissionGate.hasPreciseLocation`.

- [ ] **Step 1: Write the failing location-filter tests**

Create `app/src/test/java/com/randomwalk/app/location/LocationFilterTest.kt`:

```kotlin
package com.randomwalk.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFilterTest {

    @Test
    fun acceptsValidFirstFix() {
        val decision = LocationFilter().evaluate(
            LocationSample(31.2304, 121.4737, 8.0, 1_000),
            nowMillis = 2_000,
        )
        assertTrue(decision is LocationDecision.Accepted)
    }

    @Test
    fun acceptsAccuracyAtLimit() {
        val decision = LocationFilter().evaluate(
            LocationSample(0.0, 0.0, 50.0, 1_000),
            nowMillis = 2_000,
        )
        assertTrue(decision is LocationDecision.Accepted)
    }

    @Test
    fun rejectsAccuracyWorseThanLimit() {
        val decision = LocationFilter().evaluate(
            LocationSample(0.0, 0.0, 50.1, 1_000),
            nowMillis = 2_000,
        )
        assertEquals(
            LocationRejection.POOR_ACCURACY,
            (decision as LocationDecision.Rejected).reason,
        )
    }

    @Test
    fun acceptsFixAtAgeLimit() {
        val decision = LocationFilter().evaluate(
            LocationSample(0.0, 0.0, 5.0, 0),
            nowMillis = 10_000,
        )
        assertTrue(decision is LocationDecision.Accepted)
    }

    @Test
    fun rejectsStaleFix() {
        val decision = LocationFilter().evaluate(
            LocationSample(0.0, 0.0, 5.0, 0),
            nowMillis = 10_001,
        )
        assertEquals(
            LocationRejection.STALE,
            (decision as LocationDecision.Rejected).reason,
        )
    }

    @Test
    fun rejectsInvalidCoordinates() {
        val filter = LocationFilter()

        for (latitude in listOf(91.0, -91.0, Double.NaN)) {
            val decision = filter.evaluate(
                LocationSample(latitude, 0.0, 5.0, 1_000),
                nowMillis = 1_000,
            )
            assertEquals(
                LocationRejection.INVALID_COORDINATES,
                (decision as LocationDecision.Rejected).reason,
            )
        }
        for (longitude in listOf(181.0, -181.0, Double.NaN)) {
            val decision = filter.evaluate(
                LocationSample(0.0, longitude, 5.0, 1_000),
                nowMillis = 1_000,
            )
            assertEquals(
                LocationRejection.INVALID_COORDINATES,
                (decision as LocationDecision.Rejected).reason,
            )
        }
    }

    @Test
    fun acceptsNormalWalkingStep() {
        val filter = LocationFilter()
        assertTrue(
            filter.evaluate(LocationSample(0.0, 0.0, 5.0, 0), 0) is LocationDecision.Accepted,
        )

        val next = LocationSample(0.00005, 0.0, 5.0, 5_000) // ~5.6 m in 5 s
        assertTrue(filter.evaluate(next, 5_000) is LocationDecision.Accepted)
    }

    @Test
    fun rejectsTeleport() {
        val filter = LocationFilter()
        assertTrue(
            filter.evaluate(LocationSample(0.0, 0.0, 5.0, 0), 0) is LocationDecision.Accepted,
        )

        val jump = LocationSample(0.001, 0.0, 5.0, 5_000) // ~111 m in 5 s
        val decision = filter.evaluate(jump, 5_000)
        assertEquals(
            LocationRejection.TELEPORT,
            (decision as LocationDecision.Rejected).reason,
        )
    }

    @Test
    fun recoversAfterTeleportWhenNextFixIsConsistent() {
        val filter = LocationFilter()
        filter.evaluate(LocationSample(0.0, 0.0, 5.0, 0), 0)
        assertTrue(
            filter.evaluate(LocationSample(0.001, 0.0, 5.0, 5_000), 5_000) is LocationDecision.Rejected,
        )

        val next = LocationSample(0.0011, 0.0, 5.0, 10_000) // ~11 m from rejected fix
        assertTrue(filter.evaluate(next, 10_000) is LocationDecision.Accepted)
    }

    @Test
    fun resetForgetsHistory() {
        val filter = LocationFilter()
        filter.evaluate(LocationSample(0.0, 0.0, 5.0, 0), 0)
        filter.reset()

        val far = LocationSample(0.001, 0.0, 5.0, 5_000)
        assertTrue(filter.evaluate(far, 5_000) is LocationDecision.Accepted)
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.location.LocationFilterTest'
```

Expected: compilation fails with unresolved reference `LocationFilter`.

- [ ] **Step 3: Implement `LocationFilter`**

Create `LocationFilter.kt`:

```kotlin
package com.randomwalk.app.location

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import kotlin.math.max

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val timestampMillis: Long,
)

enum class LocationRejection {
    INVALID_COORDINATES,
    POOR_ACCURACY,
    STALE,
    TELEPORT,
}

sealed interface LocationDecision {
    data class Accepted(val sample: LocationSample) : LocationDecision
    data class Rejected(val reason: LocationRejection) : LocationDecision
}

class LocationFilter(
    private val maxAccuracyMeters: Double = 50.0,
    private val maxAgeMillis: Long = 10_000,
    private val maxSpeedMetersPerSecond: Double = 3.0,
    private val teleportGraceMeters: Double = 30.0,
) {
    private var lastAccepted: LocationSample? = null
    private var lastTeleportCandidate: LocationSample? = null

    fun evaluate(sample: LocationSample, nowMillis: Long): LocationDecision {
        if (!sample.latitude.isFinite() || sample.latitude !in -90.0..90.0 ||
            !sample.longitude.isFinite() || sample.longitude !in -180.0..180.0
        ) {
            return LocationDecision.Rejected(LocationRejection.INVALID_COORDINATES)
        }
        if (!sample.accuracyMeters.isFinite() || sample.accuracyMeters < 0.0 ||
            sample.accuracyMeters > maxAccuracyMeters
        ) {
            return LocationDecision.Rejected(LocationRejection.POOR_ACCURACY)
        }
        if (nowMillis - sample.timestampMillis > maxAgeMillis) {
            return LocationDecision.Rejected(LocationRejection.STALE)
        }

        val references = listOfNotNull(lastAccepted, lastTeleportCandidate)
        if (references.isNotEmpty()) {
            val plausible = references.any { reference ->
                val seconds = max(
                    0.0,
                    (sample.timestampMillis - reference.timestampMillis) / 1000.0,
                )
                val allowed = maxSpeedMetersPerSecond * seconds + teleportGraceMeters
                val distance = GeoMath.haversineMeters(
                    LatLon(reference.latitude, reference.longitude),
                    LatLon(sample.latitude, sample.longitude),
                )
                distance <= allowed
            }
            if (!plausible) {
                lastTeleportCandidate = sample
                return LocationDecision.Rejected(LocationRejection.TELEPORT)
            }
        }

        lastAccepted = sample
        lastTeleportCandidate = null
        return LocationDecision.Accepted(sample)
    }

    fun reset() {
        lastAccepted = null
        lastTeleportCandidate = null
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run the same command. Expected: `BUILD SUCCESSFUL`, all 10 `LocationFilterTest` tests pass.

- [ ] **Step 5: Implement `LocationSampler` and wire the debug probe**

First add `LocationSamplingConfigTest.kt` with tests that pin the production
request at 5 s / 5 m and the debug request at 1 s / 1 m. Run
`--tests com.randomwalk.app.location.LocationSamplingConfigTest` and watch it
fail to compile (missing `LocationSamplingConfig`), then implement the config
and rerun it to 2/2.

Create `LocationSampler.kt`:

```kotlin
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

data class LocationSamplingConfig(
    val intervalMillis: Long,
    val minDistanceMeters: Float,
) {
    init {
        require(intervalMillis > 0L) { "intervalMillis must be positive" }
        require(minDistanceMeters > 0f) { "minDistanceMeters must be positive" }
    }

    companion object {
        val PRODUCTION = LocationSamplingConfig(5_000L, 5f)
        val DEBUG = LocationSamplingConfig(1_000L, 1f)
    }
}

class LocationSampler(
    context: Context,
    private val filter: LocationFilter = LocationFilter(),
    private val onAccepted: (LocationSample) -> Unit,
    private val onRejected: (LocationRejection) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: LocationSamplingConfig = LocationSamplingConfig.PRODUCTION,
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
            config.intervalMillis,
        )
            .setMinUpdateIntervalMillis(config.intervalMillis)
            .setMinUpdateDistanceMeters(config.minDistanceMeters)
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
    }
}
```

Create `LocationDebugProbe.kt`:

```kotlin
package com.randomwalk.app.debug

import android.content.Context
import android.util.Log
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.location.LocationSample
import com.randomwalk.app.location.LocationSampler
import com.randomwalk.app.location.LocationSamplingConfig

/**
 * Debug-only device harness: logs accepted fixes so a 1 s / 1 m request can
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
```

Modify `MainActivity.kt`:

```kotlin
import android.content.pm.ApplicationInfo
import com.randomwalk.app.debug.LocationDebugProbe
```

Add a field next to `walkSurface`:

```kotlin
private var locationDebugProbe: LocationDebugProbe? = null
```

In `updatePermissionGate`, start the probe when precise permission is present:

```kotlin
if (LocationPermissionGate.hasPreciseLocation(this)) {
    showWalkSurface()
    startDebugProbeIfNeeded()
    return
}

stopDebugProbe()
if (walkSurface != null) {
    walkSurface?.stop()
    walkSurface = null
    setContentView(createPermissionGateView())
}
```

Add the stop calls to `onPause` and `onDestroy`:

```kotlin
override fun onPause() {
    stopDebugProbe()
    walkSurface?.stop()
    super.onPause()
}

override fun onDestroy() {
    stopDebugProbe()
    walkSurface?.stop()
    ImmersiveMode.exit(window)
    super.onDestroy()
}
```

Add the two helpers:

```kotlin
private fun startDebugProbeIfNeeded() {
    if (locationDebugProbe != null) return
    if (!LocationPermissionGate.hasPreciseLocation(this)) return
    val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (!debuggable) return
    locationDebugProbe = LocationDebugProbe(this).also { it.start() }
}

private fun stopDebugProbe() {
    locationDebugProbe?.stop()
    locationDebugProbe = null
}
```

- [ ] **Step 6: Compile and run all JVM tests**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`; no unresolved references to `LocationSampler` or `LocationDebugProbe`.

- [ ] **Step 7: Device check — accepted sample density**

Build the APK in WSL (Step 6). Then, on the Windows host with the phone
attached and `app-debug.apk` copied from `app/build/outputs/apk/debug/`:

```bash
adb install -r app-debug.apk
adb logcat -c
adb logcat -s LocationSampler LocationDebugProbe
```

If the repo is checked out on Windows with JDK 17 configured, `gradlew.bat :app:installDebug` is an equivalent install path; do not use the WSL `JAVA_HOME` path in a Windows shell.

Walk straight for 10–15 m outdoors, then turn and pace back and forth over that same 10 m for about 60 s. Expected:

- The debug probe requests updates about every 1 s / 1 m, so `accepted` lines should arrive at roughly meter-scale intervals while walking; with 10 m of space, expect several samples.
- Every accepted sample has `accuracyMeters <= 50.0`.
- Any `rejected` line names one of `POOR_ACCURACY`, `STALE`, or `TELEPORT`; nothing crashes and later accepted samples continue.
- `edge_start` appears once per app foreground session after the first accepted fix.

Do not mark 3.3 complete in `openspec/changes/random-walk-app/tasks.md` until this check passes on the Windows host.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/randomwalk/app/location/LocationFilter.kt \
  app/src/main/java/com/randomwalk/app/location/LocationSampler.kt \
  app/src/main/java/com/randomwalk/app/debug/LocationDebugProbe.kt \
  app/src/main/java/com/randomwalk/app/MainActivity.kt \
  app/src/test/java/com/randomwalk/app/location/LocationFilterTest.kt
git commit -m "feat: 加入定位采样与有效性过滤 (Task 3.3)"
```

---

### Task 4: Current-edge projection progress (3.4)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/geo/EdgeProgressTracker.kt`
- Test: `app/src/test/java/com/randomwalk/app/geo/EdgeProgressTrackerTest.kt`
- Modify: `app/src/main/java/com/randomwalk/app/debug/LocationDebugProbe.kt`

**Interfaces:**
- Produces: `class EdgeProgressTracker(edge: List<LatLon>)`
- Produces: `EdgeProgressTracker.update(position: LatLon): PolylineProjection`
- Consumes: `GeoMath.projectOntoPolyline`, `GeoMath.haversineMeters`, `GeoMath.initialBearingDegrees`, `GeoMath.offsetMeters`.

Route edges do not exist until section 4. The debug probe therefore builds a
200 m straight reference edge from the first two accepted fixes; the tracker
itself is not coupled to the probe, and Task 5.6 will pass the real current
edge to the same `update` method.

- [ ] **Step 1: Write the failing edge-progress tests**

Create `app/src/test/java/com/randomwalk/app/geo/EdgeProgressTrackerTest.kt`:

```kotlin
package com.randomwalk.app.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeProgressTrackerTest {

    private val edge = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.002)) // ~222.4 m east

    @Test
    fun progressIncreasesAlongStraightEdge() {
        val tracker = EdgeProgressTracker(edge)
        val fractions = listOf(0.0, 0.0005, 0.001, 0.0015, 0.002)
            .map { longitude -> tracker.update(LatLon(0.0, longitude)).progressFraction }

        assertEquals(0.0, fractions.first(), 1e-9)
        assertEquals(1.0, fractions.last(), 1e-9)
        for (index in 1 until fractions.size) {
            assertTrue("fractions=$fractions", fractions[index] > fractions[index - 1])
        }
    }

    @Test
    fun reportsDeviationAndProgressAtMidpoint() {
        val projection = EdgeProgressTracker(edge).update(LatLon(0.0005, 0.0005))

        assertEquals(0.25, projection.progressFraction, 0.001)
        assertEquals(55.6, projection.deviationMeters, 0.5)
    }

    @Test
    fun progressSaturatesAtEnd() {
        val projection = EdgeProgressTracker(edge).update(LatLon(0.0, 0.003))

        assertEquals(1.0, projection.progressFraction, 1e-9)
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.geo.EdgeProgressTrackerTest'
```

Expected: compilation fails with unresolved reference `EdgeProgressTracker`.

- [ ] **Step 3: Implement `EdgeProgressTracker` and log progress in the probe**

Create `EdgeProgressTracker.kt`:

```kotlin
package com.randomwalk.app.geo

class EdgeProgressTracker(private val edge: List<LatLon>) {
    init {
        require(edge.isNotEmpty()) { "edge must not be empty" }
    }

    fun update(position: LatLon): PolylineProjection =
        GeoMath.projectOntoPolyline(position, edge)
}
```

Modify `LocationDebugProbe.kt` to build a 200 m reference edge from the first
two accepted fixes and log progress on every later sample:

```kotlin
import com.randomwalk.app.geo.EdgeProgressTracker
import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.PolylineProjection
import kotlin.math.cos
import kotlin.math.sin
```

Add a tracker field:

```kotlin
private var edgeProgress: EdgeProgressTracker? = null
```

Clear it in `stop()`:

```kotlin
fun stop() {
    sampler?.stop()
    sampler = null
    edgeStart = null
    edgeProgress = null
}
```

Replace `onSample` with:

```kotlin
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
```

Add constants to the companion object:

```kotlin
private companion object {
    const val TAG = "LocationDebugProbe"
    const val MIN_EDGE_SAMPLE_METERS = 3.0
    const val DEBUG_EDGE_METERS = 200.0
}
```

- [ ] **Step 4: Run the tests and build**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`, all 3 `EdgeProgressTrackerTest` tests pass, and the debug probe compiles.

- [ ] **Step 5: Device check — progress is monotonic on a straight walk**

Build the APK in WSL (Step 4). Then, on the Windows host with the phone
attached and `app-debug.apk` copied from `app/build/outputs/apk/debug/`:

```bash
adb install -r app-debug.apk
adb logcat -c
adb logcat -s LocationDebugProbe
```

If the repo is checked out on Windows with JDK 17 configured, `gradlew.bat :app:installDebug` is an equivalent install path; do not use the WSL `JAVA_HOME` path in a Windows shell.

Walk straight for 10–15 m. Expected:

- `edge_ready` appears once, after the first two accepted fixes that are at
  least 3 m apart.
- Every later `edge_progress` line has a larger `progress_m` than the previous one while the walk stays straight.
- `fraction` increases but stays small because the synthetic reference edge is
  200 m; a 10 m walk reaches only about 0.05. The 1.0 saturation case is
  covered by `pointBeyondEndClampsToEnd`, not by this short walk.
- `deviation_m` stays small (roughly the GPS accuracy) on a straight path.

If GPS jitter causes one small regression, repeat the straight-line check; the pure projection test pins the math. Do not mark 3.4 complete in `openspec/changes/random-walk-app/tasks.md` until this check passes on the Windows host.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/randomwalk/app/geo/EdgeProgressTracker.kt \
  app/src/test/java/com/randomwalk/app/geo/EdgeProgressTrackerTest.kt \
  app/src/main/java/com/randomwalk/app/debug/LocationDebugProbe.kt
git commit -m "feat: 输出沿边投影进度 (Task 3.4)"
```

---

## Completion Tracking

After each task's JVM test command passes, mark the matching item in
`openspec/changes/random-walk-app/tasks.md`:

- 3.1 after Task 1.
- 3.2 after Task 2.
- 3.3 after Task 3's Windows-host device check.
- 3.4 after Task 4's Windows-host device check.

Keep the plan checkboxes separate from the OpenSpec task checkboxes; the device
checks are the reason 3.3 and 3.4 can lag behind their commits.
