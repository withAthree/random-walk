# Spec Delta

## Purpose

Provide single-purpose, map-free walking guidance: the app chooses a random direction and a short distance, points to the target, and lets the walker reroll when an obstacle blocks the way.

## ADDED Requirements

### Requirement: Single-purpose leg view

While a leg is active, the app SHALL display only a direction arrow and the remaining distance to the current target, apart from the one-time first-run hint and system permission UI. It MUST NOT display a map, route, total distance, return-to-start control, or completion screen. The screen SHALL remain awake while the walk view is in the foreground.

#### Scenario: Active leg view
- **WHEN** a leg is active
- **THEN** the screen shows the arrow and remaining distance and no map or total-distance UI

#### Scenario: No completion state
- **WHEN** the walker reaches a target
- **THEN** the app starts the next leg without showing a completion screen

### Requirement: Background walking

While a walk is active, the app SHALL keep the current leg, reroll memory, and location updates running when the screen turns off or the app is backgrounded, and SHALL return to the same leg when the walker brings the app back. It SHALL run a location-type foreground service with an ongoing notification while a walk is active.

#### Scenario: Screen off or app backgrounded
- **WHEN** the walker turns off the screen or switches to another app during a walk
- **THEN** location updates and the current leg continue

#### Scenario: Returning to the app
- **WHEN** the walker returns to the app
- **THEN** the same leg, reroll memory, and current remaining distance are shown

#### Scenario: Ongoing notification
- **WHEN** a walk is active
- **THEN** an ongoing notification reports waiting for location or the remaining distance and opens the app when tapped

### Requirement: Random direction and distance

For every leg, the app SHALL choose a bearing from 0° up to but not including 360° and a distance between 50 and 100 meters inclusive, then define the target at that bearing and distance from the walker's position when the leg starts. The bearing SHALL be drawn uniformly from the directions that the reroll memory does not exclude, so a draw with no active memory is uniform over the full range.

#### Scenario: Generated leg is within the configured range
- **WHEN** a leg starts
- **THEN** its bearing is at least 0° and less than 360°, and its distance is between 50 and 100 meters inclusive

#### Scenario: Each leg starts from the current position
- **WHEN** a new leg starts after arrival or reroll
- **THEN** its target is measured from the walker's position at that moment, with a newly chosen bearing and distance

### Requirement: World-fixed target arrow

The app SHALL point the arrow toward the target in world space, adjusted for the device heading, so that rotating the device changes the on-screen angle while the target direction remains fixed.

#### Scenario: Arrow aligns with the target
- **WHEN** the device points toward the target
- **THEN** the arrow points up

#### Scenario: Rotating the device
- **WHEN** the device rotates while the walker and target stay still
- **THEN** the arrow changes by the same angle and continues to indicate the fixed target direction

#### Scenario: Holding the device still
- **WHEN** the device is held still at a fixed heading
- **THEN** the arrow remains at a stable angle

### Requirement: Remaining distance

The app SHALL display the straight-line distance from the current location to the target in meters and update it as the walker moves. The value MAY increase when the walker moves away or detours. The app MUST NOT display a session total.

#### Scenario: Walking toward the target
- **WHEN** the walker moves closer to the target
- **THEN** the displayed remaining distance decreases

#### Scenario: Detour or wrong direction
- **WHEN** the walker moves away from the target
- **THEN** the displayed remaining distance increases

#### Scenario: No session total
- **WHEN** a leg is active
- **THEN** the screen does not show accumulated distance for the walk

### Requirement: Arrival starts the next leg

The app SHALL treat a leg as arrived when the walker is within 10 meters of the target continuously for 1.5 seconds, then immediately start the next leg. A visit shorter than 1.5 seconds MUST NOT advance the leg.

#### Scenario: Reaching the target
- **WHEN** the walker stays within 10 meters of the target for 1.5 seconds
- **THEN** the current leg ends and a new leg starts from the current position

#### Scenario: Brief pass through the arrival radius
- **WHEN** the walker enters the 10-meter radius and leaves before 1.5 seconds
- **THEN** the current leg continues and no new leg starts

### Requirement: Blocked target reroll

During an active leg, a long press anywhere on the walk view SHALL abandon the current target and immediately start a new leg from the walker's current position. The app MUST NOT require reaching the abandoned target and MUST NOT add a penalty, total, or user-visible history entry.

#### Scenario: Obstacle blocks the way
- **WHEN** the walker cannot continue toward the current target and long-presses the walk view
- **THEN** the current target is abandoned and a new leg starts from the current position

#### Scenario: Reroll does not penalize
- **WHEN** a leg is abandoned by long press
- **THEN** no penalty, session total, or user-visible history is created

#### Scenario: Repeated obstacles
- **WHEN** the walker long-presses again during the replacement leg
- **THEN** that leg is also abandoned and another new leg starts from the current position

### Requirement: Reroll memory

The app SHALL remember each abandoned bearing with the position where it was abandoned: at most 6 entries, oldest dropped first, cleared when the walk ends. The memory MUST NOT be shown, counted, or persisted.

#### Scenario: Memory is invisible and ends with the walk
- **WHEN** the walker abandons legs during a walk and then leaves the walk
- **THEN** nothing was shown, counted, or persisted, and the next walk starts with no memory

### Requirement: Memory-guided bearing

While an entry is within 500 meters of the walker, every new bearing, from a reroll or from an arrival, SHALL be drawn uniformly outside ±45° of it. If the active entries exclude every direction, the app SHALL drop the oldest entry and draw again rather than refuse the reroll.

#### Scenario: Reroll avoids the abandoned direction
- **WHEN** the walker long-presses because a river blocks the current target and then rerolls again from the same area
- **THEN** each new bearing is more than 45° away from the bearings abandoned there

#### Scenario: Reroll memory expires with distance
- **WHEN** the walker has moved more than 500 meters from where a direction was abandoned
- **THEN** that direction can be drawn again

#### Scenario: Every direction excluded
- **WHEN** the remembered bearings leave no direction to draw
- **THEN** the app forgets the oldest one and still produces a new leg

### Requirement: First-run gesture hint

On the first walk after installation, the app SHALL show a one-time hint explaining that the arrow points to the target and that a long press rerolls a blocked leg. The hint SHALL dismiss automatically or on tap and MUST NOT appear on later walks.

#### Scenario: First walk
- **WHEN** the walker starts the first walk after installation
- **THEN** the hint explains the arrow and the long-press reroll gesture

#### Scenario: Later walks
- **WHEN** the walker starts a subsequent walk
- **THEN** the hint is not shown again

### Requirement: Offline and private operation

The app SHALL provide guidance without network access, a map, street data, a prebuilt data pack, an account, or a backend. It MUST process location on the device and MUST NOT transmit location data. A privacy statement SHALL be available in the app.

#### Scenario: Airplane mode
- **WHEN** the device has no network connection
- **THEN** the app still generates legs and guides the walker

#### Scenario: No map data
- **WHEN** the app has no map, street data, or prebuilt pack installed
- **THEN** guidance still works

#### Scenario: Privacy statement
- **WHEN** the walker opens the app's privacy information
- **THEN** the app states that location is processed on-device and is not uploaded

### Requirement: Location permission and exit

The app SHALL require precise location access before starting a walk. If access is denied or no location fix is available, it SHALL explain the problem and MUST NOT show guidance. Pressing Back during a walk, stopping from the ongoing notification, or removing the app from the recent-tasks list SHALL stop guidance and stop location and sensor updates. The app SHALL request notification permission on Android 13 and later; if the walker denies it, guidance SHALL still start, but the system may hide the ongoing notification.

#### Scenario: Permission denied
- **WHEN** precise location permission is denied
- **THEN** the app explains why guidance cannot start and does not show the walk view

#### Scenario: No location fix
- **WHEN** permission is granted but no usable location fix is available
- **THEN** the app reports that it is waiting for location and does not show a misleading target

#### Scenario: Leaving the walk
- **WHEN** the walker presses Back during a walk
- **THEN** guidance stops and location and sensor updates stop

#### Scenario: Removing the app from recents
- **WHEN** the walker removes the app from the recent-tasks list during a walk
- **THEN** guidance stops and location and sensor updates stop

#### Scenario: Stopping from the notification
- **WHEN** the walker taps the notification's stop action during a walk
- **THEN** guidance stops and location and sensor updates stop

#### Scenario: Notification permission denied
- **WHEN** the walker denies notification permission on Android 13 or later
- **THEN** guidance still starts and location updates continue
