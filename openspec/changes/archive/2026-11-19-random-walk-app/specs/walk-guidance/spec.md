# Spec Delta

## Purpose

Generates and guides a single random walk: it takes the user's distance intent, produces a route on the street network without a destination, points the way with one arrow, and ends the walk when the distance is fulfilled.

## ADDED Requirements

### Requirement: Start screen takes distance and return intent

The start screen SHALL collect exactly two things: a target distance entered on a numeric keypad (default 2000 m) and a toggle for whether to return to the start point.

#### Scenario: Start with defaults
- **WHEN** the user opens the app and presses start without editing anything
- **THEN** a walk of 2000 m is generated with return-to-start off

#### Scenario: Custom distance and return enabled
- **WHEN** the user enters 5000 and enables return-to-start, then starts
- **THEN** the walk aims at 5000 m of walking and ends back at the start point

### Requirement: Distance input is bounded

The system SHALL accept target distances between 200 m and 20000 m and SHALL refuse to start with a value outside that range.

#### Scenario: Out-of-range distance
- **WHEN** the user enters a distance below 200 m or above 20000 m
- **THEN** the start action is unavailable and the app indicates the accepted range

### Requirement: Walk screen contains only arrow and distance

During a walk the screen SHALL display only a direction arrow and a distance figure, with no map, no text instructions, no menu, and no controls other than ending the walk.

#### Scenario: Walk in progress
- **WHEN** guidance is active
- **THEN** the visible content is the arrow and the remaining distance, and the screen stays awake

### Requirement: Arrow is world-fixed, not screen-fixed

The arrow's on-screen rotation SHALL be the target bearing minus the device heading, so that the arrow points up when the device is physically facing the target and the user turns their body rather than the phone.

#### Scenario: Facing the target
- **WHEN** the device heading equals the target bearing
- **THEN** the arrow points up

#### Scenario: Device rotated away from the target
- **WHEN** the device heading differs from the target bearing by 90 degrees
- **THEN** the arrow points 90 degrees away, in the direction the user must turn

#### Scenario: Hold angle does not change the indication
- **WHEN** the user holds the device flat, at about 45 degrees, or upright, or switches hands, while facing the same physical direction
- **THEN** the arrow points the same way in every case, with no calibration step and no instruction to level the device

### Requirement: Arrow points along the path, not at the destination

The arrow SHALL indicate the bearing of a point on the current path about 25 m ahead of the user, so the indication stays meaningful when the user is close to their next turn.

#### Scenario: Standing at the start of a long straight segment
- **WHEN** the next 200 m is a single straight segment
- **THEN** the arrow indicates the bearing along that segment

#### Scenario: Approaching a turn
- **WHEN** the user is within 25 m of a junction where the route turns
- **THEN** the arrow begins to indicate the bearing of the new segment before the user reaches the junction

### Requirement: Arrow is smoothed and degrades gracefully

The arrow SHALL be smoothed against sensor noise, SHALL shorten its transitions (not freeze) when heading accuracy is poor, and SHALL keep responding to heading changes while the user stands still.

#### Scenario: Magnetic interference
- **WHEN** heading accuracy degrades while the user is walking
- **THEN** the arrow moves more slowly and steadily instead of jumping, and never freezes

#### Scenario: User stops to turn around
- **WHEN** the user stands still and rotates in place
- **THEN** the arrow follows the rotation so the user can align it upward

### Requirement: Direction is chosen only at junctions

The system SHALL commit to a direction when the user reaches a junction and SHALL NOT change the committed direction mid-segment.

#### Scenario: Mid-segment
- **WHEN** the user is halfway along a chosen segment
- **THEN** the indicated direction does not change until the next junction is reached

### Requirement: Segment lengths are controlled

Each segment between decisions SHALL be between 180 m and 400 m, with roughly one segment in ten extended to 600–900 m.

#### Scenario: Ordinary segment
- **WHEN** a new segment is generated
- **THEN** its length falls in 180–400 m, unless it is one of the long segments

#### Scenario: Long segment
- **WHEN** the generator selects a long segment
- **THEN** its length falls in 600–900 m

### Requirement: The route never reverses onto the segment just walked

The system SHALL exclude the candidate direction that would send the user back along the segment they arrived on, so a walk never retraces its own step within a segment.

#### Scenario: Dead end reached
- **WHEN** the only way onward is the segment just walked
- **THEN** the system treats the junction as a dead end and backtracks as a new decision rather than a U-turn indication

### Requirement: Near-straight continuations are treated as straight

The system SHALL treat a turn of less than 20 degrees as a straight continuation and SHALL merge it into the current segment instead of counting it as a decision.

#### Scenario: Slight bend in the road
- **WHEN** the road bends by 15 degrees at a junction
- **THEN** the segment continues without a new decision and the arrow keeps indicating the same overall direction

### Requirement: Turns are balanced and sometimes deferred

Among turn candidates, the system SHALL choose left and right with equal probability, and SHALL in about one case in five defer the choice to the next junction.

#### Scenario: Choice at a crossroads
- **WHEN** both left and right are available candidates
- **THEN** each is chosen with the same probability and the chosen turn is indicated at the junction

#### Scenario: Deferred choice
- **WHEN** the generator defers the choice
- **THEN** the user is sent straight on and the decision is made at the following junction

### Requirement: Route length tracks the requested distance

The system SHALL allocate the remaining distance budget across the remaining segments so that the produced route totals the requested distance within 60 m, and SHALL allow the final segment to end at the nearest suitable junction.

#### Scenario: Route reaches the budget
- **WHEN** the walk's accumulated distance approaches the target
- **THEN** the final segment is closed at the junction nearest to the remaining budget

### Requirement: Arrival is detected at junctions and at segment ends

The system SHALL consider the user arrived at a decision point when they are within 10 m of the junction, or when their projection along the current segment has reached at least 95 percent, and SHALL require that state to persist briefly before acting on it.

#### Scenario: User walks past the junction
- **WHEN** the user's position passes the junction without stopping
- **THEN** arrival is recognised and the next segment is indicated

#### Scenario: Position noise near a junction
- **WHEN** reported position flickers across the junction threshold for under a second
- **THEN** no new segment is generated

### Requirement: Return leg starts from the remaining budget

When return-to-start is enabled, the system SHALL switch to the return leg once the remaining budget is no greater than the direct distance home plus 100 m, and SHALL choose the return route randomly rather than retracing the outbound route.

#### Scenario: Return triggered
- **WHEN** the remaining budget drops to the direct distance home plus 100 m or less
- **THEN** the arrow indicates a direction that leads back toward the start

#### Scenario: Return route selection
- **WHEN** the return leg is generated
- **THEN** segments already walked outbound are excluded where alternatives exist

### Requirement: About page is reachable without clutter

The start screen SHALL provide a non-obvious entry to the about page (a long press on the title), so attribution and privacy notices exist without adding visible controls.

#### Scenario: Reaching the about page
- **WHEN** the user long-presses the title on the start screen
- **THEN** the about page opens

#### Scenario: Ordinary tap
- **WHEN** the user taps the title briefly
- **THEN** nothing opens and the start screen is unchanged

### Requirement: Walk ends with a completion state

When the route is finished, the system SHALL show a single completion animation with the message "恭喜你，已完成本次漫步！" and then end the walk.

#### Scenario: Distance fulfilled
- **WHEN** the last segment is complete
- **THEN** the completion message is shown once and the walk ends after it
