# Spec Delta

## Purpose

Determines whether a walk can start at the user's current location: it obtains the walkable street network for that area, keeps it on the device, and reports readiness or coverage failure before guidance begins.

## ADDED Requirements

### Requirement: Walk start is gated on data readiness

The system SHALL NOT begin guidance in an area until the walkable network for that area is available on the device.

#### Scenario: Area already covered by local data
- **WHEN** the user starts a walk in an area whose network is already stored on the device
- **THEN** guidance begins immediately, with no preparation wait and no network access

#### Scenario: Area not covered by local data
- **WHEN** the user starts a walk in an area with no stored network
- **THEN** the system shows a preparation state and begins guidance only after the network for that area is available

### Requirement: Only walkable ways are used

The system SHALL build guidance exclusively from ways that a pedestrian can legally walk, and SHALL exclude motorways, motorway links, railways, and access-restricted private ways.

#### Scenario: Route passes near a motorway
- **WHEN** the generated route reaches a junction that only continues onto a motorway
- **THEN** that continuation is not a candidate direction

#### Scenario: Route in a pedestrian area
- **WHEN** the area contains footpaths, pedestrian streets, and living streets
- **THEN** those ways are candidate directions with the same standing as ordinary roads

### Requirement: Area data is cached in fixed tiles

The system SHALL cache network data in overlapping tiles of about 2 km so that later walks in the same or adjacent area reuse stored data instead of fetching it again.

#### Scenario: Second walk in the same area
- **WHEN** the user starts a second walk within an area whose tiles were fetched earlier
- **THEN** no new fetch is made for the already-stored tiles

#### Scenario: Walk near a tile boundary
- **WHEN** a walk moves toward the edge of the stored area
- **THEN** adjacent tiles are already present or are fetched before the route reaches their edge, and guidance is not interrupted

### Requirement: Prepackaged area data takes precedence

The system SHALL use prepackaged on-device area data when present, and SHALL NOT attempt a network fetch for an area fully covered by prepackaged data.

#### Scenario: Prepackaged city available offline
- **WHEN** the user starts a walk inside a city that ships with prepackaged data and the device has no connectivity
- **THEN** the walk starts normally with no preparation wait

### Requirement: Online preparation is time-bounded

When an area must be fetched, the system SHALL complete preparation within a bounded time (target 20 seconds) and SHALL retry against alternate sources on failure.

#### Scenario: First source unavailable
- **WHEN** the primary data source fails or times out during preparation
- **THEN** the system retries the request against an alternate source before reporting failure

#### Scenario: Preparation exceeds the time bound
- **WHEN** every source has failed or the time bound is exceeded
- **THEN** the system stops preparation and reports failure

### Requirement: Coverage failure is reported, never silent

The system SHALL report an explicit, actionable state when an area has no usable walkable network, and SHALL offer retry and cancel.

#### Scenario: No walkable ways in the area
- **WHEN** the fetched data for the area contains no usable walkable ways
- **THEN** the system reports that this area is not covered and offers retry and cancel instead of starting a walk

#### Scenario: User cancels preparation
- **WHEN** the user cancels during preparation
- **THEN** the system returns to the start screen without starting a walk

#### Scenario: Insufficient connectivity
- **WHEN** preparation fails because the device has no connectivity
- **THEN** the reported failure states that a connection is required for this area

### Requirement: Data licensing is attributed in-app

The system SHALL include OpenStreetMap attribution and the Open Database License notice in an in-app about page, and SHALL state that location data is processed on the device only.

#### Scenario: User opens the about page
- **WHEN** the user opens the about page
- **THEN** the OpenStreetMap/ODbL attribution and the on-device processing statement are both visible
