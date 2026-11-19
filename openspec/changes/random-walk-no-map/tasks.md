# Tasks

## Baseline

The old `random-walk-app` change completed the foundation this change reuses: tasks 1.1–1.4, 2.1–2.4, 3.1, and 3.3. Those checkboxes stay in the old change and are not re-opened here. The tasks below only add the no-map behavior, verify the reused baseline, and remove what is now obsolete.

## 1. Random leg core

- [x] 1.1 Add `walk/WalkLeg.kt` and `walk/RandomLegGenerator.kt`; inject the random source; use `GeoMath.offsetMeters` to build the target. The generator takes `excludedBearings`, draws uniformly outside ±45° of each, and drops the oldest exclusion when nothing is left. Add `RandomLegGeneratorTest` covering seeded bearings in `[0, 360)`, distances in `50..100`, a target at the requested bearing/distance from the supplied position, exclusion-band avoidance, and the all-excluded fallback. Verify with `./gradlew :app:testDebugUnitTest --tests "com.randomwalk.app.walk.RandomLegGeneratorTest"`.
- [x] 1.2 Add `walk/ArrivalTracker.kt` using sample timestamps. Add `ArrivalTrackerTest` covering continuous 10 m dwell for 1.5 s, reset after leaving the radius, and a short pass-through that does not advance. Verify with `./gradlew :app:testDebugUnitTest --tests "com.randomwalk.app.walk.ArrivalTrackerTest"`.
- [x] 1.3 Add `walk/WalkSession.kt`: start only after a usable location, compute remaining straight-line distance and target bearing, replace the leg on arrival, and implement `reroll()` from the latest accepted location. Keep the reroll memory here: at most 6 `(bearing, position)` entries, written only by `reroll()`, active within 500 m, cleared on stop, and passed to the generator as `excludedBearings`. Add `WalkSessionTest` for waiting without a fix, distance increase on a detour, arrival starting a new leg, reroll using the current position, reroll writing the abandoned bearing, arrival not writing it, an entry older than 500 m no longer excluding, and stop clearing the memory. Verify with `./gradlew :app:testDebugUnitTest --tests "com.randomwalk.app.walk.WalkSessionTest"`.
- [x] 1.4 Run the full JVM suite and confirm the existing geo/compass/location tests still pass with the new model: `./gradlew :app:testDebugUnitTest`.

## 2. Walk UI integration

- [x] 2.1 Add `CompassController.updateTargetBearing(degrees)` so a new leg or a moved walker can update the arrow without recreating the controller. Add or update a compass test that verifies the world-fixed angle changes with the target bearing; verify with `./gradlew :app:testDebugUnitTest --tests "com.randomwalk.app.compass.*"`.
- [x] 2.2 Extend `ui/WalkScreenView.kt` with the waiting state (“定位中”) and keep the existing arrow/distance rendering. Add a JVM or instrumentation view test that checks the distance text, arrow angle, and absence of any total-distance field; verify the focused test and `:app:assembleDebugAndroidTest`.
- [x] 2.3 Add `walk/WalkService.kt` as a location-type foreground service that owns `WalkSession` and `LocationSampler`, shows an ongoing notification (`定位中` or `剩余 N m`, tap opens `MainActivity`, `结束` action), and exposes a `Binder` with current state, a state listener, `reroll()`, and `stopWalk()`. Use `START_NOT_STICKY` and `stopWithTask="true"`; add `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, and `POST_NOTIFICATIONS` to the manifest (no `ACCESS_BACKGROUND_LOCATION`). Add `WalkServiceTest` with a fake location source: start → waiting plus notification, first fix → leg, reroll, stop → service and notification gone. Verify with `:app:testDebugUnitTest` and `:app:assembleDebugAndroidTest`.
- [x] 2.4 Rewire `ui/WalkSurface.kt` to bind to the service through a small `WalkController` interface instead of owning `WalkSession`/`LocationSampler`: render waiting and leg states, update the compass target, call `reroll()` on a long press anywhere, and start/stop only the compass on `start()`/`stop()`. Add `WalkSurfaceTest` with a fake controller covering waiting, first state, long-press reroll, and compass start/stop; verify with `:app:testDebugUnitTest` and the instrumentation test.
- [x] 2.5 Simplify `MainActivity`: precise permission → start and bind `WalkService` → `WalkSurface`; request `POST_NOTIFICATIONS` on Android 13+ without blocking on denial; remove preparation, start-placeholder, and debug data extras; stop only the compass and unbind on pause/stop, and re-bind on resume without starting a new leg; Back and task removal stop the service. Update `WalkScreenTest`/`LocationPermissionManifestTest` to match the new flow and the foreground-service permissions/service declaration; remove the obsolete `PreparationScreenTest`. Verify with `:app:testDebugUnitTest` and `:app:assembleDebugAndroidTest`.
- [x] 2.6 Verify the no-location path: with permission granted but no accepted fix, the service and screen report waiting (notification `定位中`) and do not show a target. Add an instrumentation or view test for this state; verify the focused test and `:app:assembleDebugAndroidTest`.

## 3. Remove the obsolete data and preparation layer

- [x] 3.1 Delete `app/src/main/java/com/randomwalk/app/data/`, `preparation/`, `walk/FakeWalk.kt`, and `debug/LocationDebugProbe.kt` (and their tests) now that the new walk path does not reference them. Verify with `rg` that no production or test source references `Street`, `Overpass`, `Tile`, `Preparation`, or `FakeWalk`, and that `./gradlew :app:testDebugUnitTest` passes.
- [ ] 3.2 Remove `INTERNET` from `AndroidManifest.xml`; remove the `org.json` and `sqlite-jdbc` test dependencies and the UnitTest-only `noJdk` compiler override from `app/build.gradle.kts`. Verify the merged manifest has only the location, foreground-service, and notification permissions (and no `ACCESS_BACKGROUND_LOCATION`) and `./gradlew :app:assembleDebug` passes.
- [ ] 3.3 Remove obsolete strings, ids, and `docs/street-data-schema.md`; check `rg` for `preparation_`, `start_placeholder`, `street`, `overpass`, and `ODbL`. Verify with `./gradlew :app:testDebugUnitTest` and `:app:assembleDebugAndroidTest`.
- [ ] 3.4 Inspect the built APK permissions with `aapt dump permissions app/build/outputs/apk/debug/app-debug.apk` (or the available APK analyzer) and confirm `android.permission.INTERNET` is absent. Record the command and result in the change notes.

## 4. Gesture, privacy, and documentation

- [ ] 4.1 Update `FirstRunHint`/`WalkHintOverlay` copy to explain that the arrow points to the target and a long press rerolls a blocked leg. Ensure the hint is one-time, dismisses automatically or on tap, and that a long press on the hint dismisses it and rerolls. Verify `WalkHintTest` and `FirstRunHintTest`.
- [ ] 4.2 Add a compact privacy link on the permission gate that opens a framework `AlertDialog` stating that location is processed on-device and is never uploaded. Add string resources and an instrumentation test that opens the dialog; verify the focused test and `:app:assembleDebugAndroidTest`.
- [ ] 4.3 Update `WalkScreenTest` for the new screen: arrow + remaining distance, waiting state, no map, no total distance, no return control, and no completion screen. Verify with `:app:assembleDebugAndroidTest` and the instrumentation run.
- [ ] 4.4 Add `docs/map-free-walk.md` documenting the random-leg behavior, 50–100 m range, arrival radius/dwell, long-press reroll, no-map limitation, privacy, and the device checklist. Verify the documented commands and links are accurate.

## 5. Integration and device verification

- [ ] 5.1 Run `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` and confirm all three pass.
- [ ] 5.2 On the Windows host device, run the checklist: permission grant/denial, notification permission grant/denial, 50–100 m leg, arrow alignment after rotating the phone, arrival advances, long press at a river/wall rerolls, airplane mode still guides, screen-off/background walking continues without resetting the leg, notification opens the app, Back/notification stop/swipe-away stop location and compass, and no total/map/return UI. Record the date, device, and result in `docs/map-free-walk.md`.
- [ ] 5.3 Re-run `openspec validate "random-walk-no-map"` and confirm it is valid; review the final diff for leftover data-layer code, `INTERNET`, or preparation strings.

## Workflow follow-up

- Archive `random-walk-no-map` after implementation, device verification, and review are complete.
- Archive the old `random-walk-app` change as superseded; do not mark its incomplete 4.x–8.x tasks as done.
- Verify the archived specs contain `random-walk-guidance` and do not accidentally sync the old `walk-guidance`/`street-data` capabilities.
