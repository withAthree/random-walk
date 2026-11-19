# Proposal

## Why

Walking should be a low-decision activity, but the current implementation depends on a street-network data layer and online Overpass access. That layer is expensive to deliver in China, fails within the 20-second preparation budget on the tested networks, and still cannot guarantee that a randomly chosen target is reachable. This change simplifies the product to its original promise: a single-purpose app that gives a random direction and a short distance, then lets the walker decide how to handle real-world obstacles.

## What Changes

- Replace street-network routing with map-free random legs: each leg has a uniformly random bearing and a random 50–100 m distance. The screen shows a world-fixed arrow to the target and the remaining straight-line distance.
- **BREAKING** Remove the street-data layer: OSM/Overpass fetching, the 2 km tile cache, offline packs, graph snapping, connected-component filtering, route generation, return-to-start, and total-distance/completion semantics.
- On arrival (within 10 m of the target for 1.5 s), immediately generate the next leg from the current position. There is no session total, no return-to-start, and no completion animation.
- If the walker cannot continue (river, wall, closure), a long press anywhere abandons the current target and generates a new leg from the current position, with no penalty. A one-time hint teaches the gesture.
- Reroll avoids recently abandoned directions: the app remembers up to 6 bearings it was told to abandon within 500 m of the current position and draws new bearings outside ±45° of them. The memory is invisible, adds no penalty or history record, and clears when the walk ends.
- Use only location and compass sensors. The app does not require network access, an account, a backend, a map, or a prebuilt pack. Remove the `INTERNET` permission and OSM/ODbL attribution.
- Keep the active walk running while the screen is off or the app is backgrounded with a location-type foreground service and an ongoing notification; Back, the notification stop action, or removing the app from recents stops it.
- Keep the existing app skeleton, compass loop, location sampling, and geo math as the baseline. Delete the now-unused data-layer and preparation UI code.
- Add a privacy statement: location is processed on-device and is never uploaded.
- The old `random-walk-app` change remains as history. This change supersedes only its street-data/route-generation intent. Completed foundation tasks 1.1–1.4, 2.1–2.4, 3.1, and 3.3 are reused; 3.2, 3.4, and 4.x–8.x are obsolete.

## Capabilities

### New Capabilities

- `random-walk-guidance`: map-free random-leg guidance: random bearing plus a 50–100 m distance, target arrow, remaining distance, arrival-to-next-leg, long-press reroll, and no network or map dependency.

### Modified Capabilities

None. The existing in-flight `walk-guidance` capability belongs to the superseded `random-walk-app` change and is not modified by this change.

## Impact

- **App code**: add random-leg and target generation plus the no-map walk flow; remove `data/` street-data/network modules, the preparation screen and controller, Overpass/OSM code, offline-pack handling, and their tests.
- **Permissions and dependencies**: remove `INTERNET`; keep Play Services Location; add foreground-service and notification permissions (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`) and no runtime dependencies; do not add `ACCESS_BACKGROUND_LOCATION`.
- **Data and licensing**: remove OSM/Overpass usage and ODbL attribution; no backend, bundled map data, or offline packs.
- **Tests**: add JVM tests for random ranges, target bearing and distance, arrival hysteresis, and reroll; update instrumentation tests for the arrow, distance, and hint; device checks include airplane mode and a blocked route.
- **Existing change**: leave `random-walk-app` in place and do not mark it complete; archive it as superseded after this change is implemented and verified.
