# Design

## Context

See `proposal.md` for motivation and `specs/random-walk-guidance/spec.md` for the behavior contract. The existing app already has the parts this design reuses: a single `Activity`, a Canvas-based walk screen, a rotation-vector compass controller, a fused-location sampler with an accuracy filter, and basic geo math. The walker may pocket the phone and check the arrow only occasionally, so an active walk must keep running while the screen is off or the app is backgrounded. The street-data implementation added a large `data/` subtree, preparation UI/controller, Overpass networking, SQLite, test-only SQLite/JSON dependencies, and the `INTERNET` permission. The new product does not need any of that.

The app is a pre-release single-APK product: no backend, no account, no map, no bundled data, and no on-device database. The only inputs are the current location and the device heading. The walker is the obstacle sensor; the app must not imply that a target is reachable.

## Goals / Non-Goals

**Goals:**

- Generate one random leg at a time with a 50–100 m target and a world-fixed arrow.
- Keep the complete guidance loop local, testable, and independent of Android where possible.
- Reuse the existing permission gate, location sampler, compass math, Canvas screen, immersive mode, and first-run hint.
- Remove the street-data layer and all network/data dependencies without leaving dead code or permissions.
- Make the long-press reroll and the “no map, no guarantee” limitation explicit in the UI.
- Keep the current leg and location updates running while the screen is off or the app is backgrounded, and return to the same leg when the app comes back.

**Non-Goals:**

- No obstacle detection, routing, road graph, map, satellite imagery, or terrain data.
- No automatic “unreachable” detection; the walker decides when to reroll.
- No total distance, return-to-start, completion screen, history, accounts, sync, or backend.
- No persistence across process death: if the system kills the process, the walk ends and the next launch starts a new walk.
- No background location outside an active foreground-service walk.
- No changes to the old `random-walk-app` change or its task checkboxes.
- No navigation safety guarantee; the app does not evaluate whether a direction is safe.

## Decisions

### D1: Target-based guidance, not path-based guidance

Each leg is defined by a target point at a random bearing and distance from the current position. The UI points at the target and shows the straight-line remaining distance. It does not compute a route, follow a polyline, or project the walker onto a path.

**Why:** This is the smallest behavior that satisfies “random direction + distance” without map data. It also makes detours and blocked paths a user decision rather than a routing failure.

**Alternatives considered:** Keep the street graph and route to a random target (rejected: brings back the data layer); use a random relative turn instead of an absolute target (rejected: does not satisfy the fixed-distance target behavior).

### D2: One pure leg generator with an injected random source

Add `walk/WalkLeg.kt` with the target, bearing, and distance, and `walk/RandomLegGenerator.kt` with `next(from: LatLon, excludedBearings: List<Float> = emptyList()): WalkLeg`. The generator draws:

- bearing uniformly from the directions that are more than 45° away from every excluded bearing, within `[0, 360)`; with an empty exclusion list this is uniform over `[0, 360)`;
- distance as an integer from 50 through 100 meters inclusive;
- target via the existing `GeoMath.offsetMeters` with east/north components derived from the bearing.

If the excluded bearings cover every direction, the generator drops the first (oldest) excluded bearing and draws again, so a reroll always produces a leg. The 45° exclusion, the leg range, and the arrival constants live in one place so product tuning does not touch UI code.

The random source is injected so JVM tests can use a seeded generator.

**Why:** A pure generator is deterministic under test, has no Android dependency, and keeps randomness out of the view.

**Alternatives considered:** Generate inside `WalkSurface` (harder to test, mixes concerns); draw a continuous distance (integer meters is easier to display and test and is sufficient at 50–100 m).

### D3: A small session object owns the current leg and arrival state

Add `walk/WalkSession.kt` to hold the current `WalkLeg`, the last accepted location, the remaining distance, and the target bearing. `WalkSession` accepts location samples and exposes:

- the current leg and remaining meters;
- the target bearing for the compass;
- `reroll()` for the long-press action;
- an update callback for the view.

`WalkSession` starts a leg only when a usable location is available. If no fix exists, it reports a waiting state instead of inventing a target. `reroll()` uses the latest accepted location; if there is none, it is a no-op.

**Why:** The session is the single place that guarantees a new leg always starts from the current position, and it keeps arrival, reroll, and display updates consistent.

**Alternatives considered:** Put state in `WalkSurface` (harder to unit test and lost when the activity is destroyed); keep it in a `ViewModel` (does not provide background location); a foreground service is required for background walking (D9).

### D4: Arrival uses continuous radius dwell at location-sample resolution

Add `walk/ArrivalTracker.kt`. On each accepted location sample, it computes the distance to the target. It starts a dwell timestamp when the walker first enters the 10 m radius and clears it when the walker leaves. It reports arrival when the walker remains inside for at least 1.5 seconds, using the sample timestamps. The tracker resets on every new leg and on reroll.

**Why:** The spec requires continuous dwell, not a single sample inside the radius. Using sample timestamps avoids a separate timer and makes the behavior testable with synthetic samples. At the production sampling interval the arrival may be recognized on the first sample after 1.5 s, which is acceptable.

**Alternatives considered:** Advance immediately on entering the radius (false positives from GPS jitter); run a separate wall-clock timer in the view (duplicate state, harder tests).

### D5: Long press rerolls; there is no automatic no-progress rule

A long press anywhere on the walk surface calls `WalkSession.reroll()`. The old target is discarded, the arrival tracker resets, and a new leg starts from the latest accepted location. There is no penalty, no user-visible history, and no confirmation.

**Why:** The user explicitly chooses “this is blocked”; automatic stagnation detection would produce false positives at traffic lights, crossings, and rest stops, and would add unexplained target changes.

**Alternatives considered:** A visible “换一个” button (adds a control to a deliberately minimal screen); automatic reroll after N seconds without progress (rejected for false positives and surprise).

### D5a: Reroll avoids recently abandoned directions

`WalkSession` owns a small reroll memory: at most 6 `(bearing, position)` entries, appended only by `reroll()` and evicted oldest-first. An entry is active only while the latest accepted location is within 500 m of its position; active bearings are passed to `RandomLegGenerator.next` as `excludedBearings`, so both reroll legs and arrival legs avoid them. The memory is cleared when the session stops. It is internal state only: nothing is displayed, counted, or persisted.

**Why:** Without it, a river bank, a wall, or a walled compound can be suggested again on the very next press, and the one-arrow screen has no way to explain why. A 500 m radius keeps protection where the obstacle still is without permanently banning a direction across the whole walk.

**Alternatives considered:** Uniform reroll (rejected: “press until lucky” at the water's edge); a memory that expires only by elapsed time (rejected: walking along a river would outrun it while the river remains).

### D6: Reuse the existing location and compass pipeline

`WalkService` owns `LocationSampler` and `WalkSession`; `WalkSurface` owns the compass and renders the state the service publishes. `CompassController` gains a method to update the target bearing when the leg or the walker moves; its heading smoothing and arrow-angle calculation stay unchanged. `LocationSampler` keeps its accuracy filter and production sampling configuration. The first accepted sample enables guidance; rejected or missing samples leave the screen in a “定位中” state. When the activity is not visible, the compass stops but the sampler and session keep running; on return, the compass restarts and applies the current target bearing.

**Why:** These components are already tested and avoid new dependencies. The location pipeline must run in the service so it survives the activity; the compass only matters while the screen is visible, so keeping it in the activity saves battery.

**Alternatives considered:** A new sensor/location abstraction (unnecessary); lower-level `LocationManager` (loses the existing fused-provider behavior and tests); keep both in `WalkSurface` (loses the walk when the activity is backgrounded or destroyed).

### D7: Remove the data layer, preparation flow, and network permission

Delete the `data/` subtree (SQLite, Overpass, OSM parsing, packs, tiles, graph), the preparation controller/screen, the old `walk/FakeWalk.kt`, and the tests that exist only for them. Remove the `INTERNET` permission, the `org.json`/`sqlite-jdbc` test dependencies, and the UnitTest-only `noJdk` compiler override that existed only for the local HTTP stub. Simplify `MainActivity` to: permission gate → `WalkSurface`; no preparation, no start placeholder, no debug data extras. Remove `LocationDebugProbe` if it is no longer referenced.

**Why:** Leaving the old code would keep a second, contradictory product path and an unnecessary network permission. A clean removal is safer than a feature flag because the old path cannot work without the deleted data.

**Alternatives considered:** Keep the data layer behind a debug flag (unnecessary code, permission, and test surface); keep it for a future “map mode” (not in scope).

### D8: UI changes are minimal and gesture-led

`WalkScreenView` keeps the arrow and distance; it gains a waiting state and a compact privacy link on the permission gate. `WalkSurface` binds to the service, forwards reroll, and renders state updates. `FirstRunHint`/`WalkHintOverlay` copy changes to explain “arrow points to the target” and “long press to reroll.” The first-run hint dismisses automatically or on tap; if the user long-presses the hint, it dismisses and the reroll is performed. The privacy statement is a small framework `AlertDialog` available from the permission screen, so the walk screen remains only arrow + distance. While a walk is active, an ongoing notification shows `定位中` or `剩余 N m`; tapping it opens the activity and its `结束` action stops the walk.

**Why:** This preserves the deliberately minimal interface and avoids adding navigation chrome. The hint is the only discoverability mechanism for the long-press gesture, and the notification is the only background UI, so the walk screen itself stays one-purpose.

**Alternatives considered:** A visible reroll button (clutters the one-purpose screen); a settings screen (overkill); a map-like debug overlay (contradicts the product).

### D9: A foreground walk service owns the active walk

`WalkService` owns `WalkSession` and `LocationSampler`; the activity owns only the compass and display. The service is started with `startForegroundService` from the visible permission gate, declares `android:foregroundServiceType="location"`, runs `START_NOT_STICKY`, and sets `stopWithTask="true"`. A `Binder` exposes the current state, a state listener, `reroll()`, and `stopWalk()`. While a walk is active the service shows an ongoing low-importance notification: `定位中` when waiting or `剩余 N m` when a leg is active; tapping it opens `MainActivity`; an `结束` action stops the walk. `MainActivity` starts and binds the service, unbinds and stops the compass when it is not visible, and re-binds on return without starting a new leg. Back and the notification's `结束` action stop the walk; removing the task from recents stops it through `stopWithTask`. The compass is UI-only and stops while the activity is not visible; location and the session continue.

Add `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, and `POST_NOTIFICATIONS` to the manifest. Request notification permission on Android 13+; denying it does not block walking, but the system may hide the notification. Do not request `ACCESS_BACKGROUND_LOCATION`: the service is started while the activity is visible and its location type makes it a foreground service, so background location access is not needed for this flow. If the service is ever started while the app is in the background, the system may reject it; this change only starts it from the permission gate.

**Why:** A foreground service is the only reliable way to keep fused-location updates and the current leg alive when the screen turns off or the user switches apps. Keeping the compass in the activity avoids running a game-rate sensor for a screen nobody is looking at, and the notification gives the user a visible, always-available way to stop a background walk.

**Alternatives considered:** Keep the activity alive with a wake lock or `FLAG_KEEP_SCREEN_ON` (screen stays on, battery cost, still not background); remove the `onPause` stop only (the system can throttle or kill the app); a bound-only service (dies with the activity); persist the leg and stop sensors in the background (the walker can pass the target while the screen is off, so arrival and the next leg would be missed).

## Risks / Trade-offs

- **Target is unreachable (river, wall, closure)** → The app never promises reachability; the first-run hint and long press give the walker an immediate reroll. This is the core trade-off of removing map data.
- **GPS drift or poor accuracy makes distance/arrival jump** → Keep the existing accuracy filter; use a 10 m arrival radius and 1.5 s dwell; show “定位中” until a usable fix; do not fake a target. The user can reroll if the target is clearly wrong.
- **Compass missing or inaccurate** → Keep the existing `TYPE_ROTATION_VECTOR` path and smoothing; if the sensor is unavailable, show a clear heading-unavailable message rather than a misleading arrow. The user can still use the distance display.
- **Long press is not discoverable** → One-time hint on the first walk; hint remains short and dismissible. If it is missed, the gesture can be repeated without penalty.
- **50–100 m legs feel repetitive or too short** → The range is centralized and can be tuned later without changing the session/arrival design. It is intentionally short to reduce the chance of an unreachable target.
- **Battery use from location + game-rate compass** → Location continues in a foreground service at the existing 5 s/5 m sampling rate; the compass runs only while the activity is visible. The ongoing notification makes the background location use visible and stoppable.
- **Notification permission denied on Android 13+** → The foreground service still runs and location continues, but the system may hide the notification; the walker can still stop from the app. The permission is requested after precise location and denial does not block walking.
- **Process death or OEM battery saver** → `START_NOT_STICKY` and no persistence: if the process is killed, the walk ends; the next launch starts a new walk. A foreground service reduces but does not eliminate this risk; the device checklist covers screen-off and background cases.
- **Android 12+ foreground-service launch restrictions** → Start the service only from the visible permission gate; do not auto-start it from the background.
- **Old data files on a device from the previous build** → No released users and no migration requirement; uninstall/reinstall for the pre-release APK. A future release can remove stale app data if needed.
- **Unsafe walking decisions** → The app is a direction suggester, not a safety or navigation system; the walker remains responsible for traffic, terrain, and access restrictions.

## Migration Plan

1. Remove the data/preparation UI and network stack: delete `data/`, `preparation/`, `debug/LocationDebugProbe` if unused, their tests, `INTERNET`, the SQLite/JSON test dependencies, and the UnitTest `noJdk` override.
2. Add the pure walk model: `WalkLeg`, `RandomLegGenerator`, `ArrivalTracker`, `WalkSession`, plus JVM tests for ranges, target geometry, dwell/reset, reroll, and remaining distance.
3. Add `WalkService` (location foreground service, notification, binder) and adapt `WalkSurface`/`WalkScreenView`/`CompassController`/`MainActivity`: service-owned location and session, target-bearing updates, waiting state, long press, first-run hint, and privacy link.
4. Update strings, manifest (foreground-service and notification permissions), instrumentation tests, and the device checklist (airplane mode, 50–100 m leg, river/wall reroll, compass rotation, screen-off/background walking, notification, Back/swipe stop, no total).
5. Leave `random-walk-app` untouched; after this change is verified, archive it as superseded. Rollback is a normal revert of the implementation commits; the old change and its history remain available.

## Open Questions

None. The distance range, arrival radius/dwell, gesture, background-service lifecycle, and notification behavior are fixed for this change; they can be tuned later without changing the architecture.
