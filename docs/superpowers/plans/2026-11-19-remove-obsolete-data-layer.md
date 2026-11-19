# Remove the Obsolete Data and Preparation Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the street-data, preparation, and debug scaffolding that the no-map random-leg product no longer uses, and remove its network permission, test dependencies, resources, and schema document, leaving a compiling app whose debug APK requests only location, foreground-service, and notification permissions.

**Architecture:** This is a behavior-preserving removal, not a feature change. Delete in dependency order — preparation UI first, then the `data/` package, then dead geo helpers — so the project compiles and the JVM suite passes after every task. The only production code touched after deletion is the manifest, the Gradle test configuration, and resource files. Existing walk, compass, location, and UI behavior must not change.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Android SDK 35, minSdk 26, JUnit 4, AndroidX Test, Gradle 8.x. No new dependencies.

**Spec:** `openspec/changes/random-walk-no-map/design.md` D7 (remove the data layer, preparation flow, and network permission), `openspec/changes/random-walk-no-map/proposal.md` (the BREAKING removal and Impact sections), and `openspec/changes/random-walk-no-map/tasks.md` §3.

## Global Constraints

- Build/test commands need JDK 17: prefix every Gradle invocation with `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2`.
- The current JVM suite is green before this plan starts: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest`.
- Do not change the behavior or signatures of `WalkService`, `WalkSurface`, `WalkController`, `WalkSession`, `RandomLegGenerator`, `WalkLeg`, `ArrivalTracker`, `CompassController`, `LocationSampler`, or `MainActivity`.
- Keep the baseline geo primitives explicitly reused by the proposal: `GeoMath.haversineMeters`, `GeoMath.initialBearingDegrees`, `GeoMath.offsetMeters`, `GeoMath.projectOntoPolyline`, and `data class PolylineProjection`. Only the helpers that existed solely for the deleted data layer (`TileGrid`, `E7`, `EdgeProgressTracker`) are removed.
- Delete; do not leave feature flags, compatibility shims, comments saying “removed”, or empty packages.
- Do not edit historical material: `docs/superpowers/plans/**` and `openspec/changes/archive/**` stay as-is even though they mention the deleted layer. Scope every `rg` absence check to `app/src` (and to current `docs/` when noted).
- No new Gradle dependencies, no Robolectric, no new AndroidX artifacts.
- Deletion is committed with `git rm` so the removals are visible in the diff.
- Mark a §3 checkbox in `openspec/changes/random-walk-no-map/tasks.md` only after the command named for that task passes.
- Instrumentation tests can only be compiled in this environment (`adb` is not on `PATH`). Compile with `:app:assembleDebugAndroidTest`; the device run stays in `tasks.md` §5.2.
- The local `aapt` is `/home/hkx/Android/Sdk/build-tools/35.0.0/aapt`.

## File Structure

| Path | Action | Responsibility |
|---|---|---|
| `app/src/main/java/com/randomwalk/app/preparation/` | Delete | Old preparation controller and its Android implementation |
| `app/src/main/java/com/randomwalk/app/ui/PreparationScreen.kt` | Delete | Preparation UI |
| `app/src/main/java/com/randomwalk/app/ui/PreparationText.kt` | Delete | Preparation failure-to-string mapping |
| `app/src/main/java/com/randomwalk/app/walk/FakeWalk.kt` | Delete | Pre-random-leg fake target constants |
| `app/src/main/java/com/randomwalk/app/debug/LocationDebugProbe.kt` | Delete | Debug-only location/progress harness |
| `app/src/main/java/com/randomwalk/app/data/` | Delete | SQLite/OSM/Overpass/tile/pack/graph data layer |
| `app/src/test/java/com/randomwalk/app/data/` | Delete | Tests and the JDBC test database for the data layer |
| `app/src/main/java/com/randomwalk/app/geo/TileGrid.kt` | Delete | 2 km tile grid used only by the data layer |
| `app/src/main/java/com/randomwalk/app/geo/E7.kt` | Delete | Integer coordinate codec used only by the data layer |
| `app/src/main/java/com/randomwalk/app/geo/EdgeProgressTracker.kt` | Delete | Current-edge progress helper used only by the debug probe |
| `app/src/main/AndroidManifest.xml` | Modify | Remove `INTERNET` |
| `app/src/androidTest/java/com/randomwalk/app/LocationPermissionManifestTest.kt` | Modify | Assert `INTERNET` is absent |
| `app/build.gradle.kts` | Modify | Remove `org.json`, `sqlite-jdbc`, and the UnitTest `noJdk` override |
| `app/src/main/res/values/strings.xml` | Modify | Remove preparation and start-placeholder strings |
| `app/src/main/res/values/ids.xml` | Modify | Remove preparation and start-placeholder ids |
| `docs/street-data-schema.md` | Delete | Obsolete schema documentation |
| `openspec/changes/random-walk-no-map/tasks.md` | Modify | Record the §3 verification and check the boxes |

## Review Focus

1. A symbol from the deleted layer survives in a file outside the deleted directories → the build fails or the `rg` scan finds it. Pinned by the absence scans in Tasks 1–3 and `:app:testDebugUnitTest`.
2. The shipped APK still requests `android.permission.INTERNET` through the manifest or a merged dependency → the permission is visible to the installer and to app stores. Pinned by the `aapt dump permissions` check in Task 6.
3. The removal accidentally drops a kept permission or the `WalkService` foreground-service declaration → background walking stops working or the service cannot start. Pinned by `LocationPermissionManifestTest` (Task 4) and the merged-manifest grep in Task 4.
4. A resource still referenced by `MainActivity`, `WalkSurface`, `WalkScreenView`, or the notification is deleted → resource compilation or instrumentation compilation fails. Pinned by `:app:assembleDebug` and `:app:assembleDebugAndroidTest` in Task 5.
5. A stale SQLite/OSM file from an older install gets opened or migrated after the upgrade → the app crashes or keeps a second product path. Pinned by the absence of any remaining `data/` reference in Task 2; the design intentionally requires uninstall/reinstall for this pre-release APK, so no migration code is added.

---

### Task 1: Remove the preparation UI, fake walk, and debug probe

**Files:**
- Delete: `app/src/main/java/com/randomwalk/app/preparation/AndroidPreparationController.kt`
- Delete: `app/src/main/java/com/randomwalk/app/preparation/PreparationController.kt`
- Delete: `app/src/main/java/com/randomwalk/app/ui/PreparationScreen.kt`
- Delete: `app/src/main/java/com/randomwalk/app/ui/PreparationText.kt`
- Delete: `app/src/test/java/com/randomwalk/app/ui/PreparationTextTest.kt`
- Delete: `app/src/main/java/com/randomwalk/app/walk/FakeWalk.kt`
- Delete: `app/src/main/java/com/randomwalk/app/debug/LocationDebugProbe.kt`

**Interfaces:**
- Consumes: nothing from these files. `MainActivity` already reaches the walk surface through `WalkService`/`WalkController`; no kept production file imports `preparation.*`, `PreparationScreen`, `PreparationText`, `FakeWalk`, or `LocationDebugProbe`.
- Produces: the same public walk API as today with these classes absent. The `ui/`, `walk/`, and `debug/` packages still compile.

- [ ] **Step 1: Confirm the preparation classes are self-contained**

Run:

```bash
rg -n "PreparationScreen|PreparationText|PreparationController|PreparationUiState|FakeWalk|LocationDebugProbe" \
  app/src/main/java app/src/test app/src/androidTest
```

Expected: hits only inside the files listed for deletion; no hit in `MainActivity.kt`, `WalkSurface.kt`, `WalkService.kt`, or any test under `androidTest/`.

- [ ] **Step 2: Delete the preparation UI, fake walk, and debug probe**

Run:

```bash
git rm -r app/src/main/java/com/randomwalk/app/preparation
git rm app/src/main/java/com/randomwalk/app/ui/PreparationScreen.kt \
       app/src/main/java/com/randomwalk/app/ui/PreparationText.kt \
       app/src/test/java/com/randomwalk/app/ui/PreparationTextTest.kt \
       app/src/main/java/com/randomwalk/app/walk/FakeWalk.kt \
       app/src/main/java/com/randomwalk/app/debug/LocationDebugProbe.kt
```

Expected: `git status` shows the seven deletions (the two `preparation/` files, two `ui/Preparation*` files, `PreparationTextTest.kt`, `FakeWalk.kt`, `LocationDebugProbe.kt`).

- [ ] **Step 3: Verify no references remain**

Run:

```bash
rg -n "PreparationScreen|PreparationText|PreparationController|PreparationUiState|FakeWalk|LocationDebugProbe" \
  app/src/main/java app/src/test app/src/androidTest
```

Expected: no output (exit code 1).

- [ ] **Step 4: Run the JVM suite**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`; all remaining tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/com/randomwalk/app/preparation \
           app/src/main/java/com/randomwalk/app/ui \
           app/src/main/java/com/randomwalk/app/walk \
           app/src/main/java/com/randomwalk/app/debug \
           app/src/test/java/com/randomwalk/app/ui
git commit -m "refactor: remove preparation UI, fake walk, and debug probe"
```

---

### Task 2: Remove the street-data package and its tests

**Files:**
- Delete: `app/src/main/java/com/randomwalk/app/data/` (all 26 files: `DataReadinessGate.kt`, `GeometryCodec.kt`, `PreparationFailure.kt`, `PreparationResult.kt`, `StreetDatabase.kt`, `StreetDatabaseProvider.kt`, `StreetRepository.kt`, `TileCache.kt`, `TileCoverage.kt`, `TilePlanner.kt`, `db/AndroidSqlDatabase.kt`, `db/SqlDatabase.kt`, `db/StreetSchema.kt`, `db/StreetStore.kt`, `model/StreetGraph.kt`, `net/HttpUrlConnectionTransport.kt`, `net/OnlineOsmFetcher.kt`, `net/TileFetcher.kt`, `osm/OsmModels.kt`, `osm/OsmParser.kt`, `osm/PolylineSimplifier.kt`, `osm/StreetGraphBuilder.kt`, `osm/WalkableWayFilter.kt`, `pack/AssetSource.kt`, `pack/PackFiles.kt`, `pack/PackInstaller.kt`)
- Delete: `app/src/test/java/com/randomwalk/app/data/` (all 15 files: `DataReadinessGateTest.kt`, `GeometryCodecTest.kt`, `StreetGraphTest.kt`, `StreetRepositoryTest.kt`, `TileCacheTest.kt`, `TilePlannerTest.kt`, `db/JdbcSqlDatabase.kt`, `db/StreetStoreTest.kt`, `net/OnlineOsmFetcherTest.kt`, `osm/OsmParserTest.kt`, `osm/PolylineSimplifierTest.kt`, `osm/StreetGraphBuilderTest.kt`, `osm/WalkableWayFilterTest.kt`, `pack/PackInstallerTest.kt`)

**Interfaces:**
- Consumes: nothing. Task 1 already removed the preparation controller, the only non-data consumer of `data.*`.
- Produces: the `com.randomwalk.app.data` package is absent. `GeoMath`, `LatLon`, `E7`, and the walk model remain untouched at this point.

- [ ] **Step 1: Confirm no kept file imports the data package**

Run:

```bash
rg -n "com\.randomwalk\.app\.data|Street(Repository|Database|Store|Graph|Schema)|Overpass|Osm(Parser|Models|Way)|PackInstaller|TileCache|TilePlanner|TileCoverage|GeometryCodec|DataReadinessGate" \
  app/src/main/java app/src/test app/src/androidTest
```

Expected: hits only under `app/src/main/java/com/randomwalk/app/data/` and `app/src/test/java/com/randomwalk/app/data/`.

- [ ] **Step 2: Delete the data source and test trees**

Run:

```bash
git rm -r app/src/main/java/com/randomwalk/app/data
git rm -r app/src/test/java/com/randomwalk/app/data
```

Expected: both directories are gone; `git status` lists the removals.

- [ ] **Step 3: Verify no data-layer references remain**

Run:

```bash
rg -n "com\.randomwalk\.app\.data|Street(Repository|Database|Store|Graph|Schema)|Overpass|Osm(Parser|Models|Way)|PackInstaller|TileCache|TilePlanner|TileCoverage|GeometryCodec|DataReadinessGate" \
  app/src/main/java app/src/test app/src/androidTest
```

Expected: no output (exit code 1). `TileGrid` and `E7` are still present and are handled in Task 3.

- [ ] **Step 4: Run the JVM suite**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`; the data tests are gone and the remaining geo/compass/location/walk tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/com/randomwalk/app/data app/src/test/java/com/randomwalk/app/data
git commit -m "refactor: remove the street-data layer and its tests"
```

---

### Task 3: Remove the dead tile grid, E7 codec, and edge-progress helper

**Files:**
- Delete: `app/src/main/java/com/randomwalk/app/geo/TileGrid.kt`
- Delete: `app/src/test/java/com/randomwalk/app/geo/TileGridTest.kt`
- Delete: `app/src/main/java/com/randomwalk/app/geo/E7.kt`
- Delete: `app/src/main/java/com/randomwalk/app/geo/EdgeProgressTracker.kt`
- Delete: `app/src/test/java/com/randomwalk/app/geo/EdgeProgressTrackerTest.kt`
- Keep: `app/src/main/java/com/randomwalk/app/geo/GeoMath.kt` and `app/src/test/java/com/randomwalk/app/geo/GeoMathTest.kt` (including `projectOntoPolyline` and `PolylineProjection`)

**Interfaces:**
- Consumes: `GeoMath.projectOntoPolyline` remains the baseline projection API; `EdgeProgressTracker` was only its debug-probe wrapper.
- Produces: `geo.TileGrid`, `geo.TileBounds`, `geo.Tile`, `geo.E7`, and `geo.EdgeProgressTracker` are absent. `GeoMath.haversineMeters`, `GeoMath.initialBearingDegrees`, `GeoMath.offsetMeters`, `GeoMath.normalizeBearingDegrees`, `GeoMath.normalizeLongitudeDegrees`, `GeoMath.projectOntoPolyline`, and `PolylineProjection` remain.

- [ ] **Step 1: Confirm the three helpers have no remaining consumers**

Run:

```bash
rg -n "\b(TileGrid|TileBounds|E7|EdgeProgressTracker)\b" \
  app/src/main/java app/src/test app/src/androidTest
```

Expected: hits only inside `geo/TileGrid.kt`, `geo/TileGridTest.kt`, `geo/E7.kt`, `geo/EdgeProgressTracker.kt`, and `geo/EdgeProgressTrackerTest.kt`.

- [ ] **Step 2: Delete the helpers and their tests**

Run:

```bash
git rm app/src/main/java/com/randomwalk/app/geo/TileGrid.kt \
       app/src/test/java/com/randomwalk/app/geo/TileGridTest.kt \
       app/src/main/java/com/randomwalk/app/geo/E7.kt \
       app/src/main/java/com/randomwalk/app/geo/EdgeProgressTracker.kt \
       app/src/test/java/com/randomwalk/app/geo/EdgeProgressTrackerTest.kt
```

- [ ] **Step 3: Verify the §3.1 symbol scan is clean and the baseline projection API survives**

Run:

```bash
rg -n -i "street|overpass|tile|preparation|fakewalk" app/src/main/java app/src/test app/src/androidTest
rg -n "projectOntoPolyline|PolylineProjection" \
  app/src/main/java/com/randomwalk/app/geo/GeoMath.kt \
  app/src/test/java/com/randomwalk/app/geo/GeoMathTest.kt
```

Expected: the first command has no output (exit code 1); the second finds `projectOntoPolyline` in both files and `PolylineProjection` in `GeoMath.kt` and `GeoMathTest.kt`.

- [ ] **Step 4: Run the JVM suite**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`; `GeoMathTest`, `LocationFilterTest`, compass tests, and all walk tests pass.

- [ ] **Step 5: Mark tasks.md 3.1 and commit**

Tick `- [ ] 3.1` to `- [x] 3.1` in `openspec/changes/random-walk-no-map/tasks.md`.

```bash
git add -A app/src/main/java/com/randomwalk/app/geo \
           app/src/test/java/com/randomwalk/app/geo \
           openspec/changes/random-walk-no-map/tasks.md
git commit -m "refactor: remove dead tile, E7, and edge-progress helpers"
```

---

### Task 4: Remove INTERNET, the JSON/SQLite test dependencies, and the UnitTest JDK override

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:9` (remove the `INTERNET` line)
- Modify: `app/src/androidTest/java/com/randomwalk/app/LocationPermissionManifestTest.kt:34-37` (replace the `INTERNET` assertion)
- Modify: `app/build.gradle.kts:40-46` (remove the `noJdk` override block)
- Modify: `app/build.gradle.kts:52-53` (remove the `org.json` and `sqlite-jdbc` test dependencies)

**Interfaces:**
- Consumes: no source uses `org.json`, `sqlite-jdbc`, or `com.sun.net.httpserver` after Task 2.
- Produces: the manifest requests exactly `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, and `POST_NOTIFICATIONS`; no `INTERNET`, no `ACCESS_BACKGROUND_LOCATION`. The `WalkService` declaration is unchanged.

- [ ] **Step 1: Change the manifest test to assert INTERNET is absent**

In `app/src/androidTest/java/com/randomwalk/app/LocationPermissionManifestTest.kt`, replace:

```kotlin
assertTrue(
    "INTERNET missing from manifest",
    requested.contains(Manifest.permission.INTERNET),
)
```

with:

```kotlin
assertFalse(
    "INTERNET must not be requested",
    requested.contains(Manifest.permission.INTERNET),
)
```

Expected on a device before Step 2: this test fails with `INTERNET must not be requested`.

- [ ] **Step 2: Remove INTERNET from the manifest**

Delete this line from `app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 3: Remove the UnitTest JDK override and data test dependencies**

Delete this block from `app/build.gradle.kts`:

```kotlin
// Unit tests run on the JVM and use com.sun.net.httpserver for the stub server,
// which is absent from android.jar. Compile them against the real JDK instead.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("UnitTest")) {
        compilerOptions.noJdk.set(false)
    }
}
```

Delete these two lines from the `dependencies` block:

```kotlin
testImplementation("org.json:json:20240303")
testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
```

Keep `testImplementation("junit:junit:4.13.2")` and all `androidTestImplementation` lines.

- [ ] **Step 4: Verify the build, merged manifest, and test classpath**

Run:

```bash
rg -n "com\.sun|org\.json|sqlite" app/src app/build.gradle.kts
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest :app:processDebugMainManifest :app:assembleDebug :app:assembleDebugAndroidTest
grep -E "uses-permission" app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
```

Expected: the `rg` command has no output; Gradle reports `BUILD SUCCESSFUL`; `grep` prints exactly the five permissions listed in the Interfaces block and no `INTERNET` or `ACCESS_BACKGROUND_LOCATION` line.

- [ ] **Step 5: Mark tasks.md 3.2 and commit**

Tick `- [ ] 3.2` to `- [x] 3.2` in `openspec/changes/random-walk-no-map/tasks.md`.

```bash
git add app/src/main/AndroidManifest.xml \
        app/src/androidTest/java/com/randomwalk/app/LocationPermissionManifestTest.kt \
        app/build.gradle.kts \
        openspec/changes/random-walk-no-map/tasks.md
git commit -m "build: drop INTERNET permission and data test dependencies"
```

---

### Task 5: Remove obsolete strings, ids, and the street-data schema document

**Files:**
- Modify: `app/src/main/res/values/strings.xml:16-25` (remove the preparation and start-placeholder strings)
- Modify: `app/src/main/res/values/ids.xml:6-12` (remove the preparation and start-placeholder ids)
- Delete: `docs/street-data-schema.md`

**Interfaces:**
- Consumes: no kept code references `preparation_*`, `start_placeholder_*`, `R.id.preparation_*`, or `R.id.start_placeholder*` after Task 1.
- Produces: `strings.xml` keeps only `app_name`, the six `permission_*` strings, and the five `walk_*` strings; `ids.xml` keeps only `walk_surface`, `walk_screen`, and `walk_hint`.

- [ ] **Step 1: Remove the obsolete string resources**

Delete these ten lines from `app/src/main/res/values/strings.xml`:

```xml
<string name="preparation_preparing">正在准备步行数据…</string>
<string name="preparation_no_network">需要网络连接才能准备此区域</string>
<string name="preparation_timeout">准备超时，请重试</string>
<string name="preparation_server_error">数据准备失败，请重试</string>
<string name="preparation_insufficient_coverage">该区域无可用步行数据</string>
<string name="preparation_location_unavailable">无法获取当前位置，请到户外重试</string>
<string name="preparation_cancel">取消</string>
<string name="preparation_retry">重试</string>
<string name="start_placeholder_title">随机漫步</string>
<string name="start_placeholder_action">重新准备</string>
```

Keep the `app_name`, `permission_*`, and `walk_*` strings.

- [ ] **Step 2: Remove the obsolete ids**

Delete these seven lines from `app/src/main/res/values/ids.xml`:

```xml
<item name="preparation_screen" type="id" />
<item name="preparation_status" type="id" />
<item name="preparation_progress" type="id" />
<item name="preparation_primary_action" type="id" />
<item name="preparation_secondary_action" type="id" />
<item name="start_placeholder" type="id" />
<item name="start_placeholder_action" type="id" />
```

- [ ] **Step 3: Delete the schema document**

Run:

```bash
git rm docs/street-data-schema.md
```

- [ ] **Step 4: Verify no obsolete resource or documentation terms remain**

Run:

```bash
rg -n -i "preparation_|start_placeholder|street|overpass|odbl" app/src
rg -n -i "street|overpass|odbl" docs --glob '!docs/superpowers/plans/**'
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Expected: both `rg` commands have no output (exit code 1); Gradle reports `BUILD SUCCESSFUL`.

- [ ] **Step 5: Mark tasks.md 3.3 and commit**

Tick `- [ ] 3.3` to `- [x] 3.3` in `openspec/changes/random-walk-no-map/tasks.md`.

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/res/values/ids.xml \
        docs/street-data-schema.md \
        openspec/changes/random-walk-no-map/tasks.md
git commit -m "chore: remove obsolete preparation resources and schema doc"
```

---

### Task 6: Verify the debug APK no longer requests INTERNET and record the result

**Files:**
- Modify: `openspec/changes/random-walk-no-map/tasks.md` (append a verification note and tick 3.1–3.4)

**Interfaces:**
- Consumes: the debug APK produced by `:app:assembleDebug`.
- Produces: a recorded command and result for §3.4; no source changes.

- [ ] **Step 1: Build the debug APK**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 2: Dump and check the APK permissions**

Run:

```bash
AAPT=/home/hkx/Android/Sdk/build-tools/35.0.0/aapt
"$AAPT" dump permissions app/build/outputs/apk/debug/app-debug.apk
if "$AAPT" dump permissions app/build/outputs/apk/debug/app-debug.apk | grep -q "android.permission.INTERNET"; then
  echo "FAIL: INTERNET is still present"
  exit 1
elif "$AAPT" dump permissions app/build/outputs/apk/debug/app-debug.apk | grep -q "android.permission.ACCESS_BACKGROUND_LOCATION"; then
  echo "FAIL: ACCESS_BACKGROUND_LOCATION is present"
  exit 1
else
  echo "PASS: INTERNET and ACCESS_BACKGROUND_LOCATION are absent"
fi
```

Expected: the dump lists only `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, and `POST_NOTIFICATIONS`; the final line is `PASS: INTERNET and ACCESS_BACKGROUND_LOCATION are absent`.

- [ ] **Step 3: Record the verification in the change notes**

Append a `## Verification notes` section at the end of `openspec/changes/random-walk-no-map/tasks.md` with the date, the exact `aapt` command, and the permission list from Step 2. Example shape:

```markdown
## Verification notes

- 2026-11-19, §3.4: `/home/hkx/Android/Sdk/build-tools/35.0.0/aapt dump permissions app/build/outputs/apk/debug/app-debug.apk` → INTERNET and ACCESS_BACKGROUND_LOCATION absent; permissions: ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION, POST_NOTIFICATIONS.
```

Use the actual output from Step 2, not the example text.

- [ ] **Step 4: Confirm all four §3 checkboxes are ticked and commit**

Verify `- [x] 3.1`, `- [x] 3.2`, `- [x] 3.3`, and `- [x] 3.4` in `openspec/changes/random-walk-no-map/tasks.md`.

```bash
git add openspec/changes/random-walk-no-map/tasks.md
git commit -m "docs: record data-layer removal verification"
```

---

## Self-Review

- **Spec coverage:** tasks.md 3.1 → Tasks 1–3; 3.2 → Task 4; 3.3 → Task 5; 3.4 → Task 6. The design's D7 also names `data/`, preparation, `FakeWalk`, `LocationDebugProbe`, `INTERNET`, SQLite/JSON test dependencies, and the UnitTest `noJdk` override — all assigned.
- **Step scan:** every step is a delete, an exact edit, a single `rg`/Gradle verification, or a commit. No step says “clean up” or “handle references” without naming the command.
- **Type consistency:** no new types. The only signatures named are the kept `GeoMath.projectOntoPolyline`/`PolylineProjection` baseline and the removed classes; no task redefines a class under a second name.
- **Review Focus:** each of the five lines maps to a named verification in Tasks 1–6, with the APK permission risk deferred to the only step that can prove it (Task 6).
- **Proportion:** the document is mostly exact deletion lists and commands; it does not reproduce implementation code.
