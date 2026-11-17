# Street Data Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make walkable OSM street data available on the device — cached in 2 km SQLite tiles, readable from prepackaged packs through the same read path, gated by a readiness state that reports ready / preparing / coverage failure / no network.

**Architecture:** A pure-Kotlin data core parses and filters Overpass JSON, simplifies and splits ways into a directed graph, plans tiles, enforces the online fetch budget, and computes connected components. Android adapters own SQLite, HTTP, assets, location, and the preparation screen. Every SQL statement goes through a small `SqlDatabase` interface; JVM tests run the real schema and queries on `sqlite-jdbc`, so no device is needed for data-logic tests. `StreetRepository` reads cache and packs through the same `StreetStore` queries, with packs taking precedence. `DataReadinessGate` composes tile coverage, online fetch, and connected-component filtering.

**Tech Stack:** Kotlin 2.0.21, Android SDK 35 (minSdk 26), platform `android.database.sqlite`, `HttpURLConnection`, Android `org.json`, JUnit 4, AndroidX Test. Test-only additions: `org.json:json:20240303`, `org.xerial:sqlite-jdbc:3.46.1.3`, and JDK `com.sun.net.httpserver` for stub mirrors. No Room, OkHttp, Retrofit, Gson, kotlinx-serialization, or other runtime dependency.

**Spec:** `openspec/changes/random-walk-app/design.md` (D8, D9, D13), `openspec/changes/random-walk-app/tasks.md` §4, `openspec/changes/random-walk-app/specs/street-data/spec.md`.

## Global Constraints

- Schema version is exactly **1**. `meta.schema_version = '1'`. Migration entry point is `StreetSchema.migrate(db, fromVersion)`; a database with a version newer than 1 must be rejected, not opened.
- All coordinates are WGS-84. Store latitude/longitude as int32 **1e-7 degrees** (`E7`). No GCJ-02, no map rendering.
- Tiles are the existing `TileGrid` tiles: **2000 m** on the ground, half-open north/east edges. Do not change `TileGrid` or `GeoMath` formulas. Readiness uses the **3×3 block** around the current tile (9 tiles, `TilePlanner.requiredTiles`).
- Node simplification keeps the first and last vertex and drops interior vertices closer than **30.0 m** to the last kept vertex. Every kept vertex must be an original vertex.
- Edges are **directed**. Store both directions for ordinary pedestrian ways. Respect `oneway:foot=yes/-1` and `foot:forward=no`/`foot:backward=no`; ignore vehicle `oneway=yes`. Skip any segment whose `fromNodeId == toNodeId` (isolated rings are not routable in this batch).
- Edge geometry is the simplified polyline, encoded big-endian as `int32 count` followed by `count × (int32 latE7, int32 lonE7)`. Decoded coordinate error is at most 5e-8° (about 5 mm).
- Way filter: require `highway`; exclude `motorway`, `motorway_link`, `trunk`, `trunk_link`, `construction`, `proposed`, `raceway`, `bus_guideway`, `escape`; exclude `access` in `private`/`no`; exclude `foot` in `private`/`no`; exclude any way carrying `railway`; exclude `area=yes`.
- Online preparation: Overpass query `[out:json][timeout:15];way["highway"](south,west,north,east);out body geom;`. Total budget **20_000 ms**, per-attempt timeout **8_000 ms**, at most **2 passes** over the mirror list, response cap **24 MiB**. Mirrors, in order: `https://overpass-api.de/api/interpreter`, `https://overpass.kumi.systems/api/interpreter`, `https://overpass.private.coffee/api/interpreter`.
- Failure categories are exactly `NO_NETWORK`, `TIMEOUT`, `SERVER_ERROR`, `INSUFFICIENT_COVERAGE`, plus `LOCATION_UNAVAILABLE` for the controller. `CANCELLED` is a result, not a failure.
- Nearest-node snap limit is **300.0 m** (`StreetGraph.MAX_SNAP_DISTANCE_METERS`). A point farther than that from every node is a coverage failure.
- Prepackaged packs live in `assets/packs/*.db` and are copied to `filesDir/packs/` only when the target is missing. User-supplied packs are read from `filesDir/packs/` and `getExternalFilesDir("packs")`. Pack sources are sorted by file name and come **before** the cache, so packs win duplicate edges. A pack with a missing or newer `schema_version` is skipped with a warning; it must not crash the app.
- The start page does not exist yet (task 6.1). This batch wires a temporary preparation screen plus a one-button placeholder between permission grant and the existing fake walk surface. Task 6.1 replaces the placeholder and calls `PreparationController.start()` when the real start page opens. Do not implement distance input or route generation here.
- Licensing/about page is task 6.4 and is out of scope for this plan.
- `MainActivity` honors `EXTRA_SKIP_DATA_PREPARATION` and `EXTRA_TEST_CENTER_LAT/LON` only when `ApplicationInfo.FLAG_DEBUGGABLE` is set. The skip extra keeps `WalkScreenTest` deterministic; the center extra makes the no-coverage device check reproducible.
- Builds use JDK 17: `JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2`. WSL has no Android device. JVM tests and APK/androidTest compilation run in WSL; device checks run on the Windows host with Android Studio Wi-Fi debugging.
- Mark tasks `[x]` in `openspec/changes/random-walk-app/tasks.md` only after the listed tests pass. Task 4.8 additionally requires the device checklist at the end of this plan.

## Review Focus

- The same way appears in two adjacent tile responses → the graph contains one edge, not two, and a second preparation makes zero network requests. Pinned by `sameEdgeInsertedTwiceKeepsOneRow` (Task 1) and `firstCallFetchesNineTilesAndSecondCallUsesCache` (Task 5).
- An edge crosses a tile boundary → it is queryable from both tiles, and both endpoint nodes load even when an endpoint's `tile_id` is outside the queried tile set. Pinned by `edgeCrossingTileBoundaryIsFoundFromBothTiles` (Task 1) and `repositoryLoadsEndpointNodesOutsideQueriedTiles` (Task 6).
- A mirror returns HTTP 200 with an HTML body, or the response is malformed/empty → preparation retries the next mirror, classifies the result, and never crashes. Pinned by `htmlResponseIsRetriedAndClassified` (Task 4), `malformedJsonReportsServerError` and `emptyResponseReportsInsufficientCoverage` (Task 5).
- Every mirror times out, connection-refuses, or the 20 s budget expires → preparation ends with `TIMEOUT`, `NO_NETWORK`, or `SERVER_ERROR`, never hangs. Pinned by `allMirrorsTimeoutReportsTimeout`, `allConnectionFailuresReportNoNetwork`, `totalBudgetIsRespected`, and `cancelDisconnectsInFlightRequest` (Task 4).
- The area has no walkable ways, or the nearest node is more than 300 m away → `INSUFFICIENT_COVERAGE` with retry, never `Ready` with an empty graph. Pinned by `emptyResponseReportsInsufficientCoverage` (Task 5), `nearestNodeReturnsNullBeyond300m` (Task 7), and `emptyNetworkReportsInsufficientCoverage` (Task 8).

---

### Task 1: SQLite schema, geometry codec, and store (4.1)

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/randomwalk/app/geo/E7.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/GeometryCodec.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/TilePlanner.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/TileCoverage.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/model/StreetGraph.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/db/SqlDatabase.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/db/AndroidSqlDatabase.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/db/StreetSchema.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/db/StreetStore.kt`
- Create: `app/src/test/java/com/randomwalk/app/data/db/JdbcSqlDatabase.kt`
- Create: `app/src/test/java/com/randomwalk/app/data/db/StreetStoreTest.kt`
- Create: `app/src/test/java/com/randomwalk/app/data/GeometryCodecTest.kt`
- Create: `app/src/test/java/com/randomwalk/app/data/TilePlannerTest.kt`
- Create: `docs/street-data-schema.md`

**Interfaces:**
- Produces: `E7.encode(degrees: Double): Int`, `E7.decode(value: Int): Double`
- Produces: `StreetNode(id: Long, point: LatLon, tileId: Long)`
- Produces: `EdgeKey(wayId: Long, fromNodeId: Long, toNodeId: Long)`
- Produces: `StreetEdge(wayId: Long, fromNodeId: Long, toNodeId: Long, lengthMeters: Double, geometry: List<LatLon>)`, with `val key: EdgeKey`
- Produces: `StreetGraph(nodes: Map<Long, StreetNode>, edges: List<StreetEdge>)`, with `fun outgoing(nodeId: Long): List<StreetEdge>`
- Produces: `TileCoverage.hasTile(tileId: Long): Boolean`
- Produces: `TilePlanner.requiredTiles(center: LatLon): Set<Long>`, `TilePlanner.boundsOf(tileIds: Set<Long>): TileBounds`, `TilePlanner.tilesCovering(points: List<LatLon>): Set<Long>`
- Produces: `GeometryCodec.encode(points: List<LatLon>): ByteArray`, `GeometryCodec.decode(blob: ByteArray): List<LatLon>`
- Produces: `SqlDatabase` and `SqlRow`
- Produces: `StreetSchema.CURRENT_VERSION = 1`, `StreetSchema.ensureSchema(db: SqlDatabase)`, `StreetSchema.migrate(db: SqlDatabase, fromVersion: Int)`
- Produces: `StreetStore(db: SqlDatabase)` with `ensureSchema()`, `schemaVersion()`, `verifySchema()`, `hasTile()`, `forgetTiles()`, `insertGraph()`, `nodesForTiles()`, `nodesByIds()`, `edgesForTiles()`, `edgesAtNode()`, `edgeByKey()`, `edgeCountForTiles()`; implements `TileCoverage`
- Consumes: `LatLon`, `GeoMath`, `TileGrid`, `TileBounds`, `Tile` from `com.randomwalk.app.geo`.

- [ ] **Step 1: Add test-only dependencies and write the failing store tests**

In `app/build.gradle.kts` add:

```kotlin
testImplementation("org.json:json:20240303")
testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
```

These are test-only; the APK keeps using the Android framework `org.json` and `android.database.sqlite`.

Create `JdbcSqlDatabase` in the test source set. It implements `SqlDatabase` over `java.sql.Connection`, uses `jdbc:sqlite::memory:`, binds `Long`/`Int`/`Double`/`String`/`ByteArray` with `PreparedStatement`, and implements `transaction` with `autoCommit = false`, `commit()`, `rollback()`.

Create `StreetStoreTest` with these tests (use `JdbcSqlDatabase.inMemory()` and close the database after each test):

```kotlin
@Test
fun insertedEdgeIsFoundByTileAndByKey() {
    val db = JdbcSqlDatabase.inMemory()
    val store = StreetStore(db)
    store.ensureSchema()
    val edge = StreetEdge(
        wayId = 7,
        fromNodeId = 1,
        toNodeId = 2,
        lengthMeters = 111.2,
        geometry = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001)),
    )
    val graph = StreetGraph(
        nodes = mapOf(
            1L to StreetNode(1, LatLon(0.0, 0.0), TileGrid.tileFor(LatLon(0.0, 0.0)).id),
            2L to StreetNode(2, LatLon(0.0, 0.001), TileGrid.tileFor(LatLon(0.0, 0.001)).id),
        ),
        edges = listOf(edge),
    )
    val tileId = TileGrid.tileFor(LatLon(0.0, 0.0)).id

    store.insertGraph(graph, setOf(tileId), 1_000L)

    assertEquals(listOf(edge.key), store.edgesForTiles(setOf(tileId)).map { it.key })
    assertEquals(edge.key, store.edgeByKey(edge.key)?.key)
    assertEquals(1, store.edgesAtNode(1).size)
    assertEquals(1, store.edgeCountForTiles(setOf(tileId)))
}
```

Also add:

- `schemaVersionIsOneAndReopenKeepsData`: `ensureSchema()`, insert, call `ensureSchema()` again, assert `schemaVersion() == 1` and the edge is still present.
- `newerSchemaVersionIsRejected`: create only `meta` with `schema_version = 2`, then `assertThrows(IllegalStateException::class.java) { StreetStore(db).ensureSchema() }`.
- `sameEdgeInsertedTwiceKeepsOneRow`: insert the same graph twice; assert one edge and one `edge_tiles` mapping.
- `edgeCrossingTileBoundaryIsFoundFromBothTiles`: edge from `LatLon(0.001, -0.0001)` to `LatLon(0.001, 0.0001)`; insert with the west tile only; assert `edgesForTiles(setOf(eastTile))` returns it.
- `forgetTilesMakesTileMissingAgain`: insert, `forgetTiles(setOf(tileId))`, assert `hasTile` false but `edgesForTiles(setOf(tileId))` still returns the edge.
- `nodesByIdsLoadsNodesOutsideQueriedTiles`: insert a node in the west tile, query `nodesByIds(setOf(nodeId))` after `nodesForTiles(setOf(eastTile))` returns empty.

Create `GeometryCodecTest` with `roundTripsNegativeCoordinates`, `roundTripsEmptyIsRejected`, and `truncatedBlobIsRejected`. Assert each decoded coordinate within `1e-7` of the input.

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.data.db.StreetStoreTest' \
  --tests 'com.randomwalk.app.data.GeometryCodecTest'
```

Expected: compilation fails with unresolved references `StreetStore`, `SqlDatabase`, `StreetGraph`, `E7`, or `GeometryCodec`.

- [ ] **Step 3: Implement the data model, schema, and store**

`SqlDatabase` and `SqlRow`:

```kotlin
interface SqlRow {
    fun long(column: String): Long
    fun int(column: String): Int
    fun double(column: String): Double
    fun string(column: String): String
    fun blob(column: String): ByteArray
}

interface SqlDatabase {
    fun exec(sql: String, args: List<Any> = emptyList())
    fun <T> query(sql: String, args: List<Any> = emptyList(), map: (SqlRow) -> T): List<T>
    fun <T> transaction(block: () -> T): T
    fun close()
}
```

`AndroidSqlDatabase` wraps `SQLiteDatabase`. Important: `rawQuery` takes only `Array<String>`, so convert numeric query arguments with `toString()`. `execSQL` takes `Array<Any?>` and accepts `ByteArray`. Provide `openWritable(context, name)` and `openReadOnly(file)` factories. `transaction` uses `beginTransaction` / `setTransactionSuccessful` / `endTransaction`.

`StreetSchema` DDL (one statement per `exec` call):

```sql
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS nodes (
    id INTEGER PRIMARY KEY NOT NULL,
    lat_e7 INTEGER NOT NULL,
    lon_e7 INTEGER NOT NULL,
    tile_id INTEGER NOT NULL
) WITHOUT ROWID;

CREATE INDEX IF NOT EXISTS nodes_tile ON nodes(tile_id);

CREATE TABLE IF NOT EXISTS edges (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    way_id INTEGER NOT NULL,
    from_node_id INTEGER NOT NULL,
    to_node_id INTEGER NOT NULL,
    length_m REAL NOT NULL,
    geometry BLOB NOT NULL,
    UNIQUE(way_id, from_node_id, to_node_id)
);

CREATE INDEX IF NOT EXISTS edges_from ON edges(from_node_id);
CREATE INDEX IF NOT EXISTS edges_to ON edges(to_node_id);

CREATE TABLE IF NOT EXISTS edge_tiles (
    tile_id INTEGER NOT NULL,
    edge_id INTEGER NOT NULL,
    PRIMARY KEY (tile_id, edge_id)
) WITHOUT ROWID;

CREATE INDEX IF NOT EXISTS edge_tiles_edge ON edge_tiles(edge_id);

CREATE TABLE IF NOT EXISTS tiles (
    tile_id INTEGER PRIMARY KEY NOT NULL,
    south_e7 INTEGER NOT NULL,
    west_e7 INTEGER NOT NULL,
    north_e7 INTEGER NOT NULL,
    east_e7 INTEGER NOT NULL,
    fetched_at INTEGER NOT NULL
) WITHOUT ROWID;
```

`ensureSchema` checks `sqlite_master` for `meta`. If absent, create all tables and insert `('schema_version', '1')` in one transaction. If present, read the version; call `migrate` when it is below `CURRENT_VERSION`, throw `IllegalStateException("unsupported newer schema version ...")` when it is above. A present `meta` table without a `schema_version` row is an error; do not guess a version. `migrate` for `fromVersion == 0` creates v1; `fromVersion == 1` is a no-op. `verifySchema` is read-only and requires exactly version 1.

`StreetStore.insertGraph(graph, tileIds, fetchedAtMillis)` in one transaction:

1. Upsert each node with `INSERT OR REPLACE INTO nodes(id, lat_e7, lon_e7, tile_id)`.
2. For each edge: `INSERT OR IGNORE INTO edges(way_id, from_node_id, to_node_id, length_m, geometry)`; then `SELECT id FROM edges WHERE way_id=? AND from_node_id=? AND to_node_id=?`; then `INSERT OR IGNORE INTO edge_tiles(tile_id, edge_id)` for every tile in `TilePlanner.tilesCovering(edge.geometry)`.
3. For each requested tile: `INSERT OR REPLACE INTO tiles(tile_id, south_e7, west_e7, north_e7, east_e7, fetched_at)` using `TileGrid.tileBounds(tileId)` and `E7.encode`.

Do not use `UPSERT ... RETURNING` or `STRICT`: API 26 ships SQLite 3.18 and does not support them.

Key queries:

```sql
-- edgesForTiles
SELECT DISTINCT e.id, e.way_id, e.from_node_id, e.to_node_id, e.length_m, e.geometry
FROM edges e JOIN edge_tiles et ON et.edge_id = e.id
WHERE et.tile_id IN (?, ?, ...);

-- edgesAtNode
SELECT id, way_id, from_node_id, to_node_id, length_m, geometry
FROM edges WHERE from_node_id = ? OR to_node_id = ? ORDER BY id;

-- edgeByKey
SELECT id, way_id, from_node_id, to_node_id, length_m, geometry
FROM edges WHERE way_id = ? AND from_node_id = ? AND to_node_id = ?;

-- nodesForTiles
SELECT id, lat_e7, lon_e7, tile_id FROM nodes WHERE tile_id IN (?, ...);

-- nodesByIds (chunk into groups of 500)
SELECT id, lat_e7, lon_e7, tile_id FROM nodes WHERE id IN (?, ...);
```

`edgesForTiles` maps each row to `StreetEdge(wayId, fromNodeId, toNodeId, lengthMeters, GeometryCodec.decode(blob))`. `nodesForTiles`/`nodesByIds` map `lat_e7`/`lon_e7` with `E7.decode`.

Implement `TilePlanner`:

- `requiredTiles(center)` = the tile of `center` plus the tiles of `GeoMath.offsetMeters(center, east, north)` for `east` and `north` in `{-2000.0, 0.0, 2000.0}`; return a `Set`. This yields 9 tiles for a point near a tile center.
- `boundsOf(tileIds)` = union of `TileGrid.tileBounds`; require a non-empty set.
- `tilesCovering(points)` = tile of every point plus samples along each consecutive pair at a step of at most `TILE_SIZE_METERS / 4` (500 m), so a long edge is indexed in every 2 km tile it crosses. Interpolate with linear lat/lon fractions and `GeoMath.normalizeLongitudeDegrees` on the longitude delta; include both endpoints and the final sample. Empty input returns an empty set.

Implement `GeometryCodec` with `ByteBuffer.order(ByteOrder.BIG_ENDIAN)`: `putInt(points.size)`, then for each point `putInt(E7.encode(lat))`, `putInt(E7.encode(lon))`. `encode` rejects an empty list with `IllegalArgumentException`. `decode` rejects `count <= 0` and validates `blob.size == 4 + 8 * count`, throwing `IllegalArgumentException` otherwise. `E7` uses `Math.round(degrees * 1e7).toInt()` and `value / 1e7`.

Implement `StreetGraph.outgoing(nodeId)` with a lazy map from `fromNodeId` to edges; return the list or `emptyList()`.

- [ ] **Step 4: Run the tests to verify they pass**

Run the same command as Step 2. Expected: PASS for `StreetStoreTest` and `GeometryCodecTest`. Run `TilePlannerTest` too after adding its assertions:

- `requiredTilesReturnsNineDistinctTilesNearTileCenter`
- `requiredTilesContainsCenterTile`
- `boundsOfContainsEveryTileCenter`
- `tilesCoveringEdgeAcrossBoundaryReturnsBothTiles`
- `tilesCoveringLongSegmentSamplesIntermediateTiles`: a 0.06° longitude segment at the equator returns the tiles of 0.01, 0.025, 0.04, and 0.055; endpoints alone plus a midpoint would miss the third tile.

- [ ] **Step 5: Document the schema for the offline pack script**

Create `docs/street-data-schema.md` with:

- The exact DDL above and `schema_version = 1`.
- The geometry BLOB format.
- The `TileGrid` tile-id formula: `latitudeIndex = floor(lat / LAT_STEP_DEGREES)`, `longitudeIndex = floor(normalizedLon / (LAT_STEP_DEGREES / cos(centerLat)))`, `tile_id = (latitudeIndex shl 32) or (longitudeIndex and 0xFFFFFFFF)`; `LAT_STEP_DEGREES = 2000 / 111194.93`.
- The requirement that a pack contains `meta`, `tiles`, `nodes`, `edges`, and `edge_tiles` rows, and that every edge has an `edge_tiles` row for every tile its geometry touches.
- A note that `edges` stores directed edges; both directions are separate rows.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/randomwalk/app/geo/E7.kt \
  app/src/main/java/com/randomwalk/app/data app/src/test/java/com/randomwalk/app/data \
  docs/street-data-schema.md
git commit -m "feat: add street-data schema, tile helpers, and SQLite store"
```

---

### Task 2: OSM response parsing and walkable way filtering (4.2)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/data/osm/OsmModels.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/osm/OsmParser.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/osm/WalkableWayFilter.kt`
- Create: `app/src/test/java/com/randomwalk/app/data/osm/OsmParserTest.kt`
- Create: `app/src/test/java/com/randomwalk/app/data/osm/WalkableWayFilterTest.kt`

**Interfaces:**
- Produces: `OsmWay(id: Long, nodeIds: List<Long>, geometry: List<LatLon>, tags: Map<String, String>)`
- Produces: `OsmParseResult(ways: List<OsmWay>, skippedWays: Int)`
- Produces: `OsmParseException(message: String, cause: Throwable? = null)`
- Produces: `OsmParser.parse(json: String): OsmParseResult`
- Produces: `EdgeDirection.FORWARD`, `EdgeDirection.BACKWARD`
- Produces: `WalkableWayFilter.isWalkable(tags: Map<String, String>): Boolean`
- Produces: `WalkableWayFilter.directions(tags: Map<String, String>): Set<EdgeDirection>`
- Consumes: `LatLon`.

- [ ] **Step 1: Write the failing parser and filter tests**

Use this fixture, with 9 valid ways and 2 malformed ways:

```kotlin
private val SAMPLE_JSON = """
{"elements":[
 {"type":"way","id":1,"nodes":[1,2],"geometry":[{"lat":0.0,"lon":0.0},{"lat":0.0,"lon":0.001}],"tags":{"highway":"footway"}},
 {"type":"way","id":2,"nodes":[2,3],"geometry":[{"lat":0.0,"lon":0.001},{"lat":0.0,"lon":0.002}],"tags":{"highway":"residential"}},
 {"type":"way","id":3,"nodes":[3,4],"geometry":[{"lat":0.0,"lon":0.002},{"lat":0.0,"lon":0.003}],"tags":{"highway":"motorway"}},
 {"type":"way","id":4,"nodes":[4,5],"geometry":[{"lat":0.0,"lon":0.003},{"lat":0.0,"lon":0.004}],"tags":{"highway":"footway","access":"private"}},
 {"type":"way","id":5,"nodes":[5,6],"geometry":[{"lat":0.0,"lon":0.004},{"lat":0.0,"lon":0.005}],"tags":{"highway":"pedestrian"}},
 {"type":"way","id":6,"nodes":[6,7],"geometry":[{"lat":0.0,"lon":0.005},{"lat":0.0,"lon":0.006}],"tags":{"highway":"living_street"}},
 {"type":"way","id":7,"nodes":[7,8],"geometry":[{"lat":0.0,"lon":0.006},{"lat":0.0,"lon":0.007}],"tags":{"railway":"rail"}},
 {"type":"way","id":8,"nodes":[8,9],"geometry":[{"lat":0.0,"lon":0.007},{"lat":0.0,"lon":0.008}],"tags":{"highway":"residential","foot":"no"}},
 {"type":"way","id":9,"nodes":[9,10],"geometry":[{"lat":0.0,"lon":0.008},{"lat":0.0,"lon":0.009}],"tags":{"highway":"steps"}},
 {"type":"way","id":10,"nodes":[10,11],"tags":{"highway":"footway"}},
 {"type":"way","id":11,"nodes":[11,12],"geometry":[{"lat":0.0,"lon":0.010}],"tags":{"highway":"footway"}}
]}
""".trimIndent()
```

Assertions:

- `parse(SAMPLE_JSON).ways.size == 9` and `skippedWays == 2`.
- Way 1 has `nodeIds == listOf(1L, 2L)` and `geometry == listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001))`.
- `parse(SAMPLE_JSON).ways.filter { WalkableWayFilter.isWalkable(it.tags) }.map { it.id }.toSet() == setOf(1L, 2L, 5L, 6L, 9L)`.
- `assertThrows(OsmParseException::class.java) { OsmParser.parse("{not json") }`.
- `skipsWayWithMissingGeometryWithoutThrowing` is implied by the fixture; keep it as a separate named assertion.

`WalkableWayFilterTest` loops over excluded and kept tag maps:

```kotlin
val excluded = listOf(
    mapOf("highway" to "motorway"),
    mapOf("highway" to "motorway_link"),
    mapOf("highway" to "trunk"),
    mapOf("highway" to "trunk_link"),
    mapOf("highway" to "construction"),
    mapOf("highway" to "raceway"),
    mapOf("highway" to "bus_guideway"),
    mapOf("highway" to "footway", "access" to "private"),
    mapOf("highway" to "footway", "access" to "no"),
    mapOf("highway" to "footway", "foot" to "no"),
    mapOf("highway" to "footway", "railway" to "rail"),
    mapOf("highway" to "pedestrian", "area" to "yes"),
    mapOf("building" to "yes"),
)
val kept = listOf(
    mapOf("highway" to "footway"),
    mapOf("highway" to "path"),
    mapOf("highway" to "pedestrian"),
    mapOf("highway" to "living_street"),
    mapOf("highway" to "residential"),
    mapOf("highway" to "service"),
    mapOf("highway" to "unclassified"),
    mapOf("highway" to "tertiary"),
    mapOf("highway" to "secondary"),
    mapOf("highway" to "primary"),
    mapOf("highway" to "cycleway"),
    mapOf("highway" to "steps"),
    mapOf("highway" to "track"),
    mapOf("highway" to "bridleway"),
    mapOf("highway" to "corridor"),
    mapOf("highway" to "platform"),
    mapOf("highway" to "footway", "access" to "destination"),
)
```

Directions tests:

- Default → `setOf(FORWARD, BACKWARD)`.
- `oneway:foot=yes` → `{FORWARD}`; `oneway:foot=-1` → `{BACKWARD}`; `oneway:foot=no` → both.
- `foot:forward=no` → `{BACKWARD}`; `foot:backward=no` → `{FORWARD}`.
- `oneway=yes` with no `oneway:foot` → both (vehicle oneway does not restrict pedestrians).

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.data.osm.OsmParserTest' \
  --tests 'com.randomwalk.app.data.osm.WalkableWayFilterTest'
```

Expected: compilation fails with unresolved references.

- [ ] **Step 3: Implement `OsmWay`, `OsmParseResult`, `OsmParser`, and `WalkableWayFilter`**

`OsmParser.parse`:

- Parse the top-level `JSONObject`; wrap `JSONException` in `OsmParseException`.
- Iterate `elements`. Ignore non-`way` elements.
- For each way, read `id`, `tags` (all keys as strings), `nodes` (long array), and `geometry` (`lat`/`lon` doubles).
- Skip and count a way when: `nodes` is empty, `geometry` is missing, `geometry.size != nodeIds.size`, or `geometry.size < 2`. Catch `JSONException`/`IllegalArgumentException` per way and count it as skipped.
- Do not filter here; `WalkableWayFilter` is applied by the graph builder and the tile cache.

`WalkableWayFilter.isWalkable` returns false when `highway` is missing or in the excluded set, when `access` or `foot` is `private`/`no`, when `railway` is present, or when `area == "yes"`. Otherwise true.

`directions`:

```kotlin
val onewayFoot = tags["oneway:foot"]
var forward = onewayFoot != "-1"          // yes/true/1/no/default allow forward
var backward = onewayFoot !in setOf("yes", "true", "1") // -1/no/default allow backward
if (tags["foot:forward"] == "no") forward = false
if (tags["foot:backward"] == "no") backward = false
return buildSet {
    if (forward) add(EdgeDirection.FORWARD)
    if (backward) add(EdgeDirection.BACKWARD)
}
```

Do not consult vehicle `oneway` for pedestrian access.

- [ ] **Step 4: Run the tests to verify they pass**

Run the same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/randomwalk/app/data/osm \
  app/src/test/java/com/randomwalk/app/data/osm
git commit -m "feat: parse OSM ways and filter walkable highways"
```

---

### Task 3: Node simplification and edge construction (4.3)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/data/osm/PolylineSimplifier.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/osm/StreetGraphBuilder.kt`
- Create: `app/src/test/java/com/randomwalk/app/data/osm/PolylineSimplifierTest.kt`
- Create: `app/src/test/java/com/randomwalk/app/data/osm/StreetGraphBuilderTest.kt`

**Interfaces:**
- Produces: `PolylineSimplifier(minSpacingMeters: Double = 30.0).simplify(points: List<LatLon>): List<LatLon>`
- Produces: `StreetGraphBuilder(simplifier: PolylineSimplifier = PolylineSimplifier()).build(ways: List<OsmWay>): StreetGraph`
- Consumes: `OsmWay`, `WalkableWayFilter`, `StreetGraph`, `StreetNode`, `StreetEdge`, `EdgeKey`, `GeoMath`, `TileGrid`.

- [ ] **Step 1: Write the failing simplification and builder tests**

`PolylineSimplifierTest`:

```kotlin
@Test
fun keepsEndpointsAndDropsVerticesCloserThan30m() {
    val points = (0..6).map {
        GeoMath.offsetMeters(LatLon(0.0, 0.0), eastMeters = it * 10.0, northMeters = 0.0)
    }
    val simplified = PolylineSimplifier(30.0).simplify(points)
    assertEquals(listOf(points[0], points[3], points[6]), simplified)
}

@Test
fun keepsLastPointEvenWhenCloserThan30m() {
    val points = (0..7).map {
        GeoMath.offsetMeters(LatLon(0.0, 0.0), eastMeters = it * 10.0, northMeters = 0.0)
    }
    val simplified = PolylineSimplifier(30.0).simplify(points)
    assertEquals(listOf(points[0], points[3], points[6], points[7]), simplified)
}
```

Also assert every simplified point is present in the input list, endpoints are unchanged, and `require(points.size >= 2)` rejects a one-point line.

`StreetGraphBuilderTest`:

```kotlin
@Test
fun splitsWaysAtSharedNodes() {
    val points = (0..4).map {
        GeoMath.offsetMeters(LatLon(0.0, 0.0), eastMeters = it * 111.0, northMeters = 0.0)
    }
    val wayA = OsmWay(1, listOf(1, 2, 3), points.subList(0, 3), mapOf("highway" to "footway"))
    val wayB = OsmWay(2, listOf(3, 4, 5), points.subList(2, 5), mapOf("highway" to "residential"))

    val graph = StreetGraphBuilder().build(listOf(wayA, wayB))

    assertEquals(setOf(1L, 3L, 5L), graph.nodes.keys)
    assertEquals(
        setOf(EdgeKey(1, 1, 3), EdgeKey(1, 3, 1), EdgeKey(2, 3, 5), EdgeKey(2, 5, 3)),
        graph.edges.map { it.key }.toSet(),
    )
}
```

Add:

- `createsBothDirectionsForOrdinaryWay`: one standalone two-point way → `EdgeKey(1,1,2)` and `EdgeKey(1,2,1)`.
- `respectsPedestrianOneway`: `oneway:foot=yes` → only forward; `-1` → only backward.
- `vehicleOnewayDoesNotRestrictPedestrians`: `oneway=yes` → both directions.
- `keepsOriginalGeometryForProjection`: a bent way whose simplified geometry has more than two points; assert every simplified geometry point is in the original list and the edge length is greater than the straight-line endpoint distance.
- `closedRingWithoutJunctionProducesNoSelfLoopEdges`: a closed way `[10, 11, 12, 10]`; assert `graph.edges.none { it.fromNodeId == it.toNodeId }`.
- `edgeLengthMatchesGeometry`: sum `GeoMath.haversineMeters` over consecutive geometry points and compare to `lengthMeters` within `1e-6`.
- `filtersNonWalkableWays`: a motorway way contributes no nodes or edges.

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.data.osm.PolylineSimplifierTest' \
  --tests 'com.randomwalk.app.data.osm.StreetGraphBuilderTest'
```

Expected: compilation fails with unresolved references.

- [ ] **Step 3: Implement `PolylineSimplifier` and `StreetGraphBuilder`**

`PolylineSimplifier.simplify`:

- Require at least two points and a positive `minSpacingMeters`.
- Keep `points.first()`.
- For each interior point, keep it when `GeoMath.haversineMeters(kept.last(), point) >= minSpacingMeters`.
- Always append `points.last()` unless it is already the last kept point (compare `LatLon` values).
- Return the kept list. Every output point is an input point.

`StreetGraphBuilder.build(ways)`:

1. Filter with `WalkableWayFilter.isWalkable`; also require `nodeIds.size == geometry.size` and `geometry.size >= 2`.
2. Count every node occurrence across filtered ways: `usage: Map<Long, Int>`.
3. For each way, compute sorted split indices: `0`, every interior index `i` where `usage[nodeIds[i]] >= 2`, and `lastIndex`. Splitting at a node used by two ways (or twice by the same way) is what turns a way into edges.
4. For each consecutive split pair `(start, end)`:
   - Skip if `end <= start`.
   - `from = nodeIds[start]`, `to = nodeIds[end]`; skip if `from == to` (isolated closed ring).
   - `simplified = simplifier.simplify(geometry.subList(start, end + 1))`.
   - `length = sum of haversine over simplified`; skip if `< 1.0 m`.
   - Add `StreetNode(from, simplified.first(), TileGrid.tileFor(simplified.first()).id)` and the `to` node, using `putIfAbsent` so the first coordinate wins.
   - For each direction in `WalkableWayFilter.directions(way.tags)`: add `StreetEdge(way.id, from, to, length, simplified)` for `FORWARD`, or `StreetEdge(way.id, to, from, length, simplified.reversed())` for `BACKWARD`.
5. Deduplicate directed edges by `EdgeKey` with a `LinkedHashMap`; keep the first. Parallel edges from different ways remain because their `wayId` differs.
6. Return `StreetGraph(nodes, edges)`.

Do not split closed rings at a synthetic midpoint. An isolated ring is skipped; a ring with a real junction splits at that junction into usable non-self-loop segments.

- [ ] **Step 4: Run the tests to verify they pass**

Run the same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/randomwalk/app/data/osm/PolylineSimplifier.kt \
  app/src/main/java/com/randomwalk/app/data/osm/StreetGraphBuilder.kt \
  app/src/test/java/com/randomwalk/app/data/osm/PolylineSimplifierTest.kt \
  app/src/test/java/com/randomwalk/app/data/osm/StreetGraphBuilderTest.kt
git commit -m "feat: simplify OSM nodes and build directed street edges"
```

---

### Task 4: Online preparation fetcher with mirrors, retries, and time bound (4.4)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/data/net/TileFetcher.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/net/HttpUrlConnectionTransport.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/net/OnlineOsmFetcher.kt`
- Create: `app/src/test/java/com/randomwalk/app/data/net/OnlineOsmFetcherTest.kt`

**Interfaces:**
- Produces: `HttpResponse(code: Int, body: String)`
- Produces: `TileFetchTransport.post(url: String, body: String, connectTimeoutMillis: Int, readTimeoutMillis: Int): HttpResponse` and `cancel()`
- Produces: `HttpUrlConnectionTransport()`
- Produces: `OnlineFetchResult.Success(json)`, `OnlineFetchResult.Failure(reason)`, `OnlineFetchResult.Cancelled`
- Produces: `TileFetcher.fetch(bounds: TileBounds, isCancelled: () -> Boolean = { false }): OnlineFetchResult` and `cancel()`
- Produces: `OnlineOsmFetcher(mirrors, transport, clock, totalBudgetMillis, attemptTimeoutMillis, maxPasses)` implementing `TileFetcher`
- Produces: `OverpassQuery.forBounds(bounds: TileBounds): String`
- Consumes: `TileBounds`, `PreparationFailure`.

- [ ] **Step 1: Write the failing fetcher tests with a local stub server**

Use `com.sun.net.httpserver.HttpServer` bound to `127.0.0.1` port 0. In `@After`, call `server.stop(0)`. Record request counts per context. Use a sample body that passes the plausibility check:

```kotlin
private val SAMPLE_JSON = """{"elements":[{"type":"way","id":1,"nodes":[1,2],"geometry":[{"lat":0.0,"lon":0.0},{"lat":0.0,"lon":0.001}],"tags":{"highway":"footway"}}]}"""
```

Tests:

- `queryFormatsBoundingBoxAndUsesGeomOutput`: query starts with `[out:json][timeout:15];way["highway"](`, contains `0.0000000,0.0000000`, and ends with `);out body geom;`.
- `requestBodyIsFormEncoded`: the stub server sees a body starting with `data=%5Bout%3Ajson%5D` and containing the bbox; no raw brackets or spaces.
- `retriesAlternateMirrorAfterServerError`: primary returns 500; secondary returns 200 + `SAMPLE_JSON`. Assert `Success`, primary count 1, secondary count 1.
- `allMirrorsServerError`: both return 500 with `maxPasses = 2`; assert `Failure(SERVER_ERROR)` and 4 total attempts.
- `allMirrorsTimeoutReportsTimeout`: handlers sleep 500 ms; `attemptTimeoutMillis = 50`, `totalBudgetMillis = 1_000`; assert `Failure(TIMEOUT)`.
- `allConnectionFailuresReportNoNetwork`: mirrors `http://127.0.0.1:1`; assert `Failure(NO_NETWORK)`.
- `htmlResponseIsRetriedAndClassified`: primary returns 200 with `<html>error</html>`; secondary returns JSON; assert `Success` and primary count 1.
- `cancellationStopsBeforeFirstRequest`: `isCancelled = { true }`; assert `Cancelled` and zero requests.
- `cancelDisconnectsInFlightRequest`: primary handler counts down a latch and sleeps 2 s; start `fetch` on a thread, wait for the latch, call `fetcher.cancel()`, join; assert `Cancelled` and elapsed `< 2_000 ms`.
- `totalBudgetIsRespected`: handler sleeps 10 s; `attemptTimeoutMillis = 10_000`, `totalBudgetMillis = 300`; assert `Failure(TIMEOUT)` and elapsed `< 2_000 ms`.

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.data.net.OnlineOsmFetcherTest'
```

Expected: compilation fails with unresolved references.

- [ ] **Step 3: Implement the query, transport, and fetcher**

`OverpassQuery.forBounds`:

```kotlin
fun forBounds(bounds: TileBounds): String = String.format(
    Locale.US,
    "[out:json][timeout:15];way[\"highway\"](%.7f,%.7f,%.7f,%.7f);out body geom;",
    bounds.south, bounds.west, bounds.north, bounds.east,
)
```

`HttpUrlConnectionTransport.post`:

- `URL(url).openConnection() as HttpURLConnection`.
- `requestMethod = "POST"`, `doOutput = true`, `setRequestProperty("Content-Type", "application/x-www-form-urlencoded")`, `setRequestProperty("Accept", "application/json")`, `setRequestProperty("User-Agent", "RandomWalk/0.1 (Android; OSM data preparation)")`.
- Set connect/read timeouts from the arguments.
- Store the connection in a `@Volatile var current`; clear it in `finally`.
- Write the already form-encoded `body` to the output stream. The fetcher owns the `data=` + URL-encoding step; do not encode twice.
- Read the response body with a byte cap of `24 * 1024 * 1024`; throw `IOException("response too large")` if exceeded.
- Return `HttpResponse(responseCode, body)`; on non-2xx read `errorStream` but still return the code/body.
- `cancel()` disconnects `current`.

`OnlineOsmFetcher.fetch`:

- Build the form body once: `data=` + `URLEncoder.encode(OverpassQuery.forBounds(bounds), "UTF-8")`.
- Track `started = clock()`, `sawTimeout`, `sawNetworkFailure`, `sawHttpFailure`.
- Loop `pass in 0 until maxPasses`, then each mirror in order:
  - If `isCancelled()` or internal `cancelled`, return `Cancelled`.
  - `remaining = totalBudgetMillis - (clock() - started)`; if `<= 0`, return `Failure(TIMEOUT)`.
  - `timeout = min(attemptTimeoutMillis, remaining.toInt())`.
  - Call `transport.post(mirror, body, timeout, timeout)`.
  - HTTP 200: if the body is plausible Overpass JSON (trimmed body starts with `{` and contains `"elements"`), return `Success(body)`; otherwise set `sawHttpFailure` and continue.
  - HTTP 408/429/5xx: set `sawHttpFailure`; continue.
  - Other 4xx: return `Failure(SERVER_ERROR)` immediately (the query is malformed, retrying will not help).
  - `SocketTimeoutException`: set `sawTimeout`; continue.
  - `UnknownHostException`, `ConnectException`, `NoRouteToHostException`, other `IOException`: set `sawNetworkFailure`; continue; if cancellation was requested, return `Cancelled`.
- After the loop: if `sawHttpFailure` return `SERVER_ERROR`; else if `sawTimeout` return `TIMEOUT`; else return `NO_NETWORK`.
- `cancel()` sets internal `cancelled = true` and calls `transport.cancel()`.

Default constructor values: `mirrors = DEFAULT_MIRRORS`, `transport = HttpUrlConnectionTransport()`, `clock = System::currentTimeMillis`, `totalBudgetMillis = 20_000`, `attemptTimeoutMillis = 8_000`, `maxPasses = 2`. Validate mirrors non-empty, budget positive, timeout positive, passes positive.

- [ ] **Step 4: Run the tests to verify they pass**

Run the same command as Step 2. Expected: PASS. If the in-flight cancellation test is flaky on CI, keep the assertion on `Cancelled` and widen the elapsed bound to 3 s; do not remove the test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/randomwalk/app/data/net \
  app/src/test/java/com/randomwalk/app/data/net
git commit -m "feat: add mirror-rotating Overpass fetcher with a 20s budget"
```

---

### Task 5: Tile planning, fetch, persist, and second-call cache hit (4.5)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/data/TileCache.kt`
- Create: `app/src/test/java/com/randomwalk/app/data/TileCacheTest.kt`

**Interfaces:**
- Produces: `TileEnsureResult.Covered(fetchedTiles: Int)`, `TileEnsureResult.Failed(reason)`, `TileEnsureResult.Cancelled`
- Produces: `TileEnsurer.ensureTiles(center: LatLon, forceRefresh: Boolean = false, isCancelled: () -> Boolean = { false }, onProgress: (Int, Int) -> Unit = { _, _ -> }): TileEnsureResult` and `cancel()`
- Produces: `TileCache(coverage: TileCoverage, store: StreetStore, fetcher: TileFetcher, builder: StreetGraphBuilder = StreetGraphBuilder(), clock: () -> Long = System::currentTimeMillis) : TileEnsurer`
- Consumes: `TilePlanner`, `StreetStore`, `TileFetcher`, `OsmParser`, `WalkableWayFilter`, `StreetGraphBuilder`, `PreparationFailure`.

- [ ] **Step 1: Write the failing tile-cache tests**

Use `FakeTileFetcher`:

```kotlin
class FakeTileFetcher(private val result: OnlineFetchResult) : TileFetcher {
    var calls = 0
    override fun fetch(bounds: TileBounds, isCancelled: () -> Boolean): OnlineFetchResult {
        calls++
        return result
    }
    override fun cancel() {}
}
```

Use an in-memory `StreetStore` as both `coverage` and the write store. The success fixture is a way inside the center tile around `(0, 0)`.

Tests:

- `firstCallFetchesNineTilesAndSecondCallUsesCache`: first `ensureTiles(LatLon(0.0, 0.0))` returns `Covered(9)` and `fetcher.calls == 1`; second call returns `Covered(0)` and `fetcher.calls` is still 1. Assert `onProgress` saw `(0, 9)` then `(9, 9)`.
- `alreadyStoredTileIsSkipped`: pre-insert one tile, then prepare; the fetch is still made for the other 8; assert the pre-inserted tile was not the only one marked and the result is `Covered(8)`.
- `successfulFetchPersistsNodesEdgesAndTiles`: after a successful call, `store.hasTile(centerTile)`, `store.edgesForTiles(setOf(centerTile))` is non-empty, and `store.nodesForTiles(...)` contains both endpoints.
- `emptyResponseReportsInsufficientCoverage`: fixture `{"elements":[]}` → `Failed(INSUFFICIENT_COVERAGE)`, `store.hasTile(centerTile)` is false, and no edges are stored.
- `malformedJsonReportsServerError`: fixture `"{"` → `Failed(SERVER_ERROR)` and no tiles stored.
- `cancelledFetchPersistsNothing`: fake returns `Cancelled`; assert `Cancelled` and `store.hasTile(centerTile)` is false.
- `forceRefreshRefetchesCachedTiles`: successful first call, then `forceRefresh = true`; `fetcher.calls == 2` and result `Covered(9)`. (`forgetTiles` only removes the cache tile rows; existing edges may remain and must not create duplicate edges.)
- `edgeCrossingTileBoundaryIsStoredForBothTiles`: fixture way crosses the `lon = 0` boundary; after prepare, `edgesForTiles(setOf(westTile))` and `edgesForTiles(setOf(eastTile))` both contain the edge.

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.data.TileCacheTest'
```

Expected: compilation fails with unresolved references.

- [ ] **Step 3: Implement `TileCache`**

`ensureTiles`:

1. `required = TilePlanner.requiredTiles(center)`.
2. If `forceRefresh`, call `store.forgetTiles(required)`; this removes only cache `tiles` rows, not edges or packs.
3. `missing = required.filterNot(coverage::hasTile).toSet()`.
4. If `missing` is empty, return `Covered(0)`.
5. `onProgress(0, missing.size)`.
6. `bounds = TilePlanner.boundsOf(missing)`.
7. `when (fetcher.fetch(bounds, isCancelled))`:
   - `Cancelled` → `Cancelled`.
   - `Failure` → `Failed(reason)`.
   - `Success` → continue.
8. If `isCancelled()` → `Cancelled`.
9. Parse with `OsmParser.parse(json)`, filter with `WalkableWayFilter.isWalkable`. If no walkable ways → `Failed(INSUFFICIENT_COVERAGE)`. Catch `OsmParseException` → `Failed(SERVER_ERROR)`.
10. `graph = builder.build(walkableWays)`. If `graph.edges.isEmpty()` → `Failed(INSUFFICIENT_COVERAGE)`.
11. If `isCancelled()` → `Cancelled`.
12. `store.insertGraph(graph, missing, clock())`.
13. `onProgress(missing.size, missing.size)`; return `Covered(missing.size)`.

`cancel()` delegates to `fetcher.cancel()`.

Do not mark a tile fetched when the response is empty or unparseable; a retry must be able to fetch it again. `forceRefresh` is the explicit retry path for a tile that was previously marked but is empty.

- [ ] **Step 4: Run the tests to verify they pass**

Run the same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/randomwalk/app/data/TileCache.kt \
  app/src/test/java/com/randomwalk/app/data/TileCacheTest.kt
git commit -m "feat: fetch and persist 3x3 tile blocks with cache hits"
```

---

### Task 6: Offline pack loading and one read path for cache and packs (4.6)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/data/pack/AssetSource.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/pack/PackInstaller.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/pack/PackFiles.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/StreetDatabase.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/StreetRepository.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/StreetDatabaseProvider.kt`
- Create: `app/src/test/java/com/randomwalk/app/data/pack/PackInstallerTest.kt`
- Create: `app/src/test/java/com/randomwalk/app/data/StreetRepositoryTest.kt`

**Interfaces:**
- Produces: `AssetSource.list(directory: String): List<String>`, `AssetSource.open(path: String): InputStream`
- Produces: `PackInstaller(assetSource: AssetSource, targetDir: File).installBundled(): List<File>`
- Produces: `PackFiles.list(dirs: List<File>): List<File>`
- Produces: `StreetDatabase(sql: SqlDatabase)` with `val store: StreetStore` and `close()`
- Produces: `StreetRepository(sources: List<StreetDatabase>) : TileCoverage` with `missingTiles(tileIds: Set<Long>): Set<Long>` and `loadGraph(tileIds: Set<Long>): StreetGraph`
- Produces: `StreetDatabaseProvider.open(context: Context): StreetDatabaseProvider`, with `val cacheStore: StreetStore`, `val repository: StreetRepository`, `close()`
- Consumes: `StreetStore`, `StreetSchema`, `TilePlanner`, `AndroidSqlDatabase`.

- [ ] **Step 1: Write the failing pack and repository tests**

`PackInstallerTest` uses a fake `AssetSource` backed by a temp directory. The fake counts `open` calls.

- `installerCopiesBundledPacksOnce`: asset dir contains `a.db` and `b.db`; first `installBundled()` copies both and returns two files; second call returns the same files with `open` count unchanged.
- `installerRecreatesMissingTarget`: delete `a.db`; call again; only `a.db` is opened and recreated.
- `installerIgnoresNonDbAssets`: `readme.txt` is not copied.
- `packFilesListsOnlyDbFilesFromBothDirectories`: two temp dirs with `.db`, `.txt`, and a subdirectory; assert only the `.db` files, sorted, deduplicated.

`StreetRepositoryTest` uses two `JdbcSqlDatabase.inMemory()` sources wrapped in `StreetDatabase`; build each with `StreetStore.ensureSchema()` and `insertGraph()`.

- `repositoryReadsEdgesFromPackAndCache`: pack has tile A, cache has tile B; `loadGraph(setOf(A, B))` contains both edges.
- `repositoryPrefersPackOnDuplicateEdge`: insert the same `EdgeKey` in pack and cache with different lengths; `sources = listOf(pack, cache)`; assert the pack length wins.
- `repositoryLoadsEndpointNodesOutsideQueriedTiles`: insert an edge crossing A/B in the pack, with both endpoint nodes assigned to tile A only; `loadGraph(setOf(B))` returns the edge and both nodes.
- `repositoryDeduplicatesNodesAcrossSources`: same node id in both sources; `loadGraph` has one node.
- `repositoryHasTileWhenPackCoversIt`: pack has tile A; `hasTile(A)` true, `missingTiles(setOf(A, B))` is `{B}`.
- `verifySchemaRejectsMissingOrNewerVersion`: empty DB and a DB with `schema_version = 2` both throw `IllegalStateException` from `verifySchema()`. (Can live in `StreetStoreTest` if preferred.)

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.data.pack.PackInstallerTest' \
  --tests 'com.randomwalk.app.data.StreetRepositoryTest'
```

Expected: compilation fails with unresolved references.

- [ ] **Step 3: Implement pack installation, source listing, and the repository**

`PackInstaller.installBundled`:

- List `assetSource.list("packs")`; keep names ending in `.db`.
- For each name, target = `File(targetDir, name)`. If `target.exists()`, skip (no asset read).
- Otherwise create `targetDir`, copy to `File(targetDir, "$name.tmp")` with a buffered stream, then `renameTo(target)`. If rename fails, delete the temp and throw `IOException`.
- Return the installed/existing files, sorted by name.

`AndroidAssetSource` wraps `AssetManager.list(directory).toList()` and `assets.open(path)`. Put it in the same file as `AssetSource` (it is Android-specific but small).

`PackFiles.list(dirs)`:

- Ignore null dirs; `getExternalFilesDir("packs")` can be null.
- For each dir, list files where `isFile && name.endsWith(".db")`.
- Deduplicate by `canonicalPath`; sort by file name, then by absolute path so ties across pack directories are deterministic.

`StreetDatabase` simply wraps `SqlDatabase` and constructs `StreetStore`. `close()` closes the SQL database.

`StreetRepository`:

- `hasTile(tileId)` = any source's `store.hasTile(tileId)`.
- `missingTiles(ids)` = ids where `!hasTile(id)`.
- `loadGraph(tileIds)`:
  - Iterate sources in order (packs first, cache last).
  - For each source, add `nodesForTiles(tileIds)` to a `LinkedHashMap<Long, StreetNode>` with `putIfAbsent`.
  - Add `edgesForTiles(tileIds)` to a `LinkedHashMap<EdgeKey, StreetEdge>` with `putIfAbsent`.
  - Collect endpoint ids absent from the node map; query each source's `nodesByIds` (chunked) and `putIfAbsent`.
  - Return `StreetGraph(nodes, edges.values.toList())`.

`StreetDatabaseProvider.open(context)`:

1. `packDir = File(context.filesDir, "packs")`.
2. `PackInstaller(AndroidAssetSource(context.assets), packDir).installBundled()`.
3. `packFiles = PackFiles.list(listOf(packDir, context.getExternalFilesDir("packs")))`.
4. For each pack file, try `StreetDatabase(AndroidSqlDatabase.openReadOnly(file))`, call `store.verifySchema()`; on any exception, close it, log with `Log.w("StreetDatabaseProvider", ...)`, and skip it.
5. Open the cache with `AndroidSqlDatabase.openWritable(context, "street_cache")`; call `store.ensureSchema()`.
6. `repository = StreetRepository(packDatabases + cacheDatabase)`; `cacheStore = cacheDatabase.store`.
7. Return a provider that closes every database exactly once.

The provider is the only class that knows about `Context` and `AssetManager`. `StreetRepository` stays pure.

- [ ] **Step 4: Run the tests to verify they pass**

Run the same command as Step 2. Expected: PASS. Also run `:app:assembleDebug` to verify the Android provider compiles.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/randomwalk/app/data/pack \
  app/src/main/java/com/randomwalk/app/data/StreetDatabase.kt \
  app/src/main/java/com/randomwalk/app/data/StreetRepository.kt \
  app/src/main/java/com/randomwalk/app/data/StreetDatabaseProvider.kt \
  app/src/test/java/com/randomwalk/app/data/pack \
  app/src/test/java/com/randomwalk/app/data/StreetRepositoryTest.kt
git commit -m "feat: read offline packs and tile cache through one repository"
```

---

### Task 7: Nearest-node snap and connected-component filtering (4.7)

**Files:**
- Modify: `app/src/main/java/com/randomwalk/app/data/model/StreetGraph.kt`
- Create: `app/src/test/java/com/randomwalk/app/data/StreetGraphTest.kt`

**Interfaces:**
- Produces: `StreetGraph.nearestNode(point: LatLon, maxDistanceMeters: Double = MAX_SNAP_DISTANCE_METERS): StreetNode?`
- Produces: `StreetGraph.connectedComponent(startNodeId: Long): StreetGraph`
- Produces: `StreetGraph.MAX_SNAP_DISTANCE_METERS = 300.0`
- Consumes: `GeoMath.haversineMeters`.

- [ ] **Step 1: Write the failing graph tests**

Build a complete graph with one main component and one island:

```kotlin
val main = listOf(
    streetNode(1, LatLon(0.0, 0.0)),
    streetNode(2, LatLon(0.0, 0.001)),
    streetNode(3, LatLon(0.0, 0.002)),
    streetNode(10, LatLon(1.0, 0.0)),
    streetNode(11, LatLon(1.0, 0.001)),
)
val edges = listOf(
    edge(1, 1, 2), edge(1, 2, 1),
    edge(1, 2, 3), edge(1, 3, 2),
    edge(2, 10, 11), edge(2, 11, 10),
)
val graph = StreetGraph(main.associateBy { it.id }, edges)
```

Tests:

- `outgoingReturnsOnlyFromNodeEdges`: `graph.outgoing(2)` has keys `2→1` and `2→3`, not `1→2` or `3→2`.
- `nearestNodePicksClosestWithinLimit`: a point 10 m from node 2 returns node 2.
- `nearestNodeReturnsNullBeyond300m`: a point 299 m from node 2 returns node 2; a point 301 m from every node returns null. Do not test exactly 300.0 m; floating-point haversine makes the boundary ambiguous.
- `connectedComponentExcludesDisconnectedIsland`: `connectedComponent(2)` has nodes `{1,2,3}` and 4 edges; nodes 10/11 and island edges are absent.
- `connectedComponentOfMissingStartIsEmpty`: `connectedComponent(999)` returns an empty graph.
- `connectedComponentKeepsStartNodeWithoutEdges`: a graph with one isolated node returns that node and no edges.

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.data.StreetGraphTest'
```

Expected: compilation fails because `nearestNode`/`connectedComponent` do not exist.

- [ ] **Step 3: Implement the graph methods**

`nearestNode`:

- Iterate `nodes.values`; compute `GeoMath.haversineMeters(point, node.point)`.
- Keep the smallest distance `<= maxDistanceMeters`; return null when none qualifies.
- Ties: keep the first in map iteration order; do not make tie order part of tests.

`connectedComponent(startNodeId)`:

- If `startNodeId` is not in `nodes`, return `StreetGraph(emptyMap(), emptyList())`.
- Build undirected adjacency from every edge: `from -> to` and `to -> from`.
- BFS from `startNodeId`; collect reachable node ids.
- Keep `nodes` entries whose id is reachable.
- Keep edges whose `fromNodeId` and `toNodeId` are both reachable.
- Return the new graph.

This is undirected connectivity even though edges are stored as directed pairs, because readiness only needs “can I reach this subgraph on foot”, and ordinary ways have both directions. A one-way pedestrian edge still contributes its endpoints to the same component.

- [ ] **Step 4: Run the tests to verify they pass**

Run the same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/randomwalk/app/data/model/StreetGraph.kt \
  app/src/test/java/com/randomwalk/app/data/StreetGraphTest.kt
git commit -m "feat: snap to nearest node and keep the connected street subgraph"
```

---

### Task 8: Readiness gate, preparation screen, and start-page handoff (4.8)

**Files:**
- Create: `app/src/main/java/com/randomwalk/app/data/PreparationResult.kt`
- Create: `app/src/main/java/com/randomwalk/app/data/DataReadinessGate.kt`
- Create: `app/src/main/java/com/randomwalk/app/preparation/PreparationController.kt`
- Create: `app/src/main/java/com/randomwalk/app/preparation/AndroidPreparationController.kt`
- Create: `app/src/main/java/com/randomwalk/app/ui/PreparationScreen.kt`
- Create: `app/src/main/java/com/randomwalk/app/ui/PreparationText.kt`
- Modify: `app/src/main/java/com/randomwalk/app/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values/ids.xml`
- Modify: `app/src/androidTest/java/com/randomwalk/app/WalkScreenTest.kt`
- Create: `app/src/androidTest/java/com/randomwalk/app/PreparationScreenTest.kt`
- Create: `app/src/test/java/com/randomwalk/app/data/DataReadinessGateTest.kt`
- Create: `app/src/test/java/com/randomwalk/app/ui/PreparationTextTest.kt`

**Interfaces:**
- Produces: `enum class PreparationFailure { NO_NETWORK, TIMEOUT, SERVER_ERROR, INSUFFICIENT_COVERAGE, LOCATION_UNAVAILABLE }`
- Produces: `sealed interface PreparationResult { data class Ready(val graph: StreetGraph); data class Failed(val reason: PreparationFailure); data object Cancelled }`
- Produces: `DataReadinessGate(tileEnsurer: TileEnsurer, repository: StreetRepository).prepare(center, forceRefresh, isCancelled, onProgress): PreparationResult` and `cancel()`
- Produces: `PreparationUiState.Preparing(completed, total)`, `Ready`, `Failed(reason)`, `Cancelled`
- Produces: `PreparationController.start(listener)`, `cancel()`, `retry()`, `close()`
- Produces: `PreparationScreen(context, controller, onReady, onCancelled)` with `start()`/`stop()`
- Produces: `AndroidPreparationController(context, fixedCenter: LatLon? = null)`
- Produces: `PreparationText.messageRes(reason: PreparationFailure): Int`
- Consumes: `TileEnsurer`, `TilePlanner`, `StreetRepository`, `StreetGraph`, `LocationSampler`, `StreetDatabaseProvider`, `OnlineOsmFetcher`.

- [ ] **Step 1: Write the failing gate and text tests**

`FakeTileEnsurer` records calls, `forceRefresh`, and returns a configured result.

`DataReadinessGateTest` uses a repository backed by `JdbcSqlDatabase`:

```kotlin
@Test
fun emptyNetworkReportsInsufficientCoverage() {
    val ensurer = FakeTileEnsurer(TileEnsureResult.Covered(0))
    val gate = DataReadinessGate(ensurer, emptyRepository())
    assertEquals(
        PreparationResult.Failed(PreparationFailure.INSUFFICIENT_COVERAGE),
        gate.prepare(LatLon(0.0, 0.0)),
    )
}

@Test
fun noNetworkReportsNoNetwork() {
    val gate = DataReadinessGate(
        FakeTileEnsurer(TileEnsureResult.Failed(PreparationFailure.NO_NETWORK)),
        emptyRepository(),
    )
    assertEquals(
        PreparationResult.Failed(PreparationFailure.NO_NETWORK),
        gate.prepare(LatLon(0.0, 0.0)),
    )
}
```

Also add:

- `coveredByPackStartsReadyWithoutNetwork`: fake returns `Covered(0)`; repository has a graph around the center; assert `Ready` and the fake was called (coverage check still runs, but no fetch is implied by `Covered(0)`).
- `readyGraphIsConnectedComponentAroundNearestNode`: repository graph has a main component and an island; assert the returned graph excludes the island.
- `cancelReturnsCancelled`: fake returns `Cancelled`; assert `Cancelled`.
- `forceRefreshIsForwarded`: `gate.prepare(center, forceRefresh = true)`; assert `fake.lastForceRefresh == true`.
- `progressIsForwarded`: fake calls `onProgress(3, 9)`; assert the gate's callback received `3, 9`.
- `repositoryWithNoTileCoverageStillChecksGraph`: fake `Covered(0)` but repository empty → `INSUFFICIENT_COVERAGE` (same as the first test; keep one).

`PreparationTextTest` asserts every enum maps to the expected string resource:

```kotlin
assertEquals(R.string.preparation_no_network, PreparationText.messageRes(PreparationFailure.NO_NETWORK))
assertEquals(R.string.preparation_timeout, PreparationText.messageRes(PreparationFailure.TIMEOUT))
assertEquals(R.string.preparation_server_error, PreparationText.messageRes(PreparationFailure.SERVER_ERROR))
assertEquals(R.string.preparation_insufficient_coverage, PreparationText.messageRes(PreparationFailure.INSUFFICIENT_COVERAGE))
assertEquals(R.string.preparation_location_unavailable, PreparationText.messageRes(PreparationFailure.LOCATION_UNAVAILABLE))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.data.DataReadinessGateTest' \
  --tests 'com.randomwalk.app.ui.PreparationTextTest'
```

Expected: compilation fails with unresolved references.

- [ ] **Step 3: Implement `DataReadinessGate` and the text mapping**

`DataReadinessGate.prepare`:

1. `when (tileEnsurer.ensureTiles(center, forceRefresh, isCancelled, onProgress))`:
   - `Cancelled` → `Cancelled`.
   - `Failed` → `Failed(reason)`.
   - `Covered` → continue.
2. If `isCancelled()` → `Cancelled`.
3. `required = TilePlanner.requiredTiles(center)`.
4. `graph = repository.loadGraph(required)`.
5. `start = graph.nearestNode(center) ?: return Failed(INSUFFICIENT_COVERAGE)`.
6. `component = graph.connectedComponent(start.id)`.
7. If `component.edges.isEmpty()` → `Failed(INSUFFICIENT_COVERAGE)`.
8. Else `Ready(component)`.

`cancel()` calls `tileEnsurer.cancel()`.

`PreparationText` is a small object mapping each failure enum to the string resource; no Android context required.

Add strings to `app/src/main/res/values/strings.xml`:

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

- [ ] **Step 4: Implement the preparation screen and controller**

`PreparationController` and `PreparationUiState`:

```kotlin
sealed interface PreparationUiState {
    data class Preparing(val completed: Int, val total: Int) : PreparationUiState
    data object Ready : PreparationUiState
    data class Failed(val reason: PreparationFailure) : PreparationUiState
    data object Cancelled : PreparationUiState
}

interface PreparationController {
    fun start(listener: (PreparationUiState) -> Unit)
    fun cancel()
    fun retry()
    fun close()
}
```

`PreparationScreen` is a vertical `LinearLayout` with a black background, white text, and centered children. Set `id = R.id.preparation_screen`.

- `TextView` status (`R.id.preparation_status`).
- Horizontal `ProgressBar` (`R.id.preparation_progress`), determinate when `total > 0`, indeterminate when `total == 0`.
- `Button` primary (`R.id.preparation_primary_action`): “取消” while preparing, “重试” after failure.
- `Button` secondary (`R.id.preparation_secondary_action`): “取消”, visible only after failure.
- `start()` calls `controller.start(::render)`.
- `stop()` calls `controller.cancel()` and clears the listener.
- `render(state)`: set `currentState = state` first so click handling knows which action is visible.
  - `Preparing`: status `preparation_preparing`; make progress visible; set `isIndeterminate` before `max`/`progress`; when `total == 0` use indeterminate and do not divide by zero; primary “取消”; secondary gone.
  - `Failed`: status `PreparationText.messageRes(reason)`; progress gone; primary “重试”; secondary “取消” visible.
  - `Ready`: call `onReady()` exactly once.
  - `Cancelled`: call `onCancelled()` exactly once.
- Guard `readyHandled` and `cancelledHandled` so repeated states do not fire callbacks twice.
- Primary click: if current state is `Failed` → `controller.retry()`; else → `controller.cancel()`.
- Secondary click: `controller.cancel()`.

Add IDs to `ids.xml`: `preparation_screen`, `preparation_status`, `preparation_progress`, `preparation_primary_action`, `preparation_secondary_action`, `start_placeholder`, `start_placeholder_action`.

`AndroidPreparationController`:

- Constructor: `context: Context`, `fixedCenter: LatLon? = null`, optional `mainHandler: Handler = Handler(Looper.getMainLooper())`, optional `workerExecutor: ExecutorService = Executors.newSingleThreadExecutor()`.
- `start(listener)`:
  - Increment the generation; if a worker is running, cancel it silently (no `Cancelled` callback); do not block the main thread.
  - Reset `cancelled = false`, `cancelledPosted = false`; set listener.
  - Post `Preparing(0, 0)`.
  - Submit the worker to the single-thread executor. A stale worker that is still finishing may delay the queued task, but only one worker runs at once; the queued task checks its generation and returns immediately if stale.
  - On the worker: capture the generation; if stale, return. Open `StreetDatabaseProvider` into a local `workerProvider`; create `TileCache(coverage = workerProvider.repository, store = workerProvider.cacheStore, fetcher = OnlineOsmFetcher())`; create `DataReadinessGate(tileCache, workerProvider.repository)`.
  - Obtain the center: if `fixedCenter != null`, use it; otherwise create a `LocationSampler` on the main thread with production config and a `CountDownLatch(1)`, store the first accepted `LatLon`, stop the sampler, and wait up to `30_000 ms`. If no fix, stop the sampler on the main thread and post `Failed(LOCATION_UNAVAILABLE)`.
  - Call `gate.prepare(center, forceRefresh, { cancelled }, { completed, total -> post(Preparing(completed, total)) })`.
  - Map `Ready` → `Ready`, `Failed` → `Failed`, `Cancelled` → `Cancelled`. On `Ready` with the current generation, assign `provider = workerProvider` and keep it open. On `Failed`/`Cancelled`, close `workerProvider` and leave `provider = null`. In `finally`, close `workerProvider` unless it was kept as the current ready provider.
- `cancel()`:
  - Increment the generation for worker posts; `cancelled = true`; stop the sampler; call `gate?.cancel()`; count down the location latch; post `Cancelled` once under the new generation.
- `retry()`:
  - Set `forceRefresh = true`, then call `start(listener)`. `start` increments the generation and cancels the current worker silently; the new task queues behind the old worker. Do not join or close the provider here; the failed/cancelled worker closes its own provider.
- `close()`:
  - Increment the generation, cancel silently, `workerExecutor.shutdown()`, close the current ready `provider`, clear listeners. Do not join; a running worker closes its own provider in `finally`.
- Worker state posts use the generation captured at `start`; `cancel`, `retry`, `close`, and a new `start` increment it, so old worker posts are dropped. `cancel` posts its explicit `Cancelled` under the new generation. Never run two workers at once (the single-thread executor enforces this).
- Keep the provider/repository open after `Ready` so route generation (5.x) can reuse it. `MainActivity.onDestroy` calls `close()`.

On a worker exception (storage, unexpected parse), log and post `Failed(SERVER_ERROR)`; do not crash the app.

- [ ] **Step 5: Wire `MainActivity` and update the instrumentation tests**

`MainActivity` additions:

```kotlin
companion object {
    const val EXTRA_SKIP_DATA_PREPARATION = "com.randomwalk.app.extra.SKIP_DATA_PREPARATION"
    const val EXTRA_TEST_CENTER_LAT = "com.randomwalk.app.extra.TEST_CENTER_LAT"
    const val EXTRA_TEST_CENTER_LON = "com.randomwalk.app.extra.TEST_CENTER_LON"
}
```

Behavior:

- In `updatePermissionGate`, when precise location is granted:
  - If `shouldSkipPreparation()` → `showWalkSurface()` and start the debug probe (existing test path).
  - Else if `walkSurface != null` → return; the walk is already ready.
  - Else if `preparationScreen != null || startPlaceholderShown` → return.
  - Else → `showPreparation()`.
  - When precise location is not granted: call `preparationScreen?.stop()`, set `preparationScreen = null` and `startPlaceholderShown = false`, call `preparationController?.close()`, set `preparationController = null`, stop the debug probe, stop and null the walk surface, and show the permission gate; do not leave a preparation worker or ready database open.
- `showPreparation()`: `walkSurface?.stop(); walkSurface = null`; `startPlaceholderShown = false`; stop the debug probe; create a new `AndroidPreparationController` only if the previous one is null (reuse a non-null open controller; `MainActivity` sets it to null whenever it closes it); create `PreparationScreen` with `onReady = ::onPreparationReady`, `onCancelled = ::onPreparationCancelled`; set `screen.id = R.id.preparation_screen`; `setContentView(screen)`; `screen.start()`.
- `onPreparationReady()`: `preparationScreen = null`; `startPlaceholderShown = false`; show the walk surface; start the debug probe.
- `onPreparationCancelled()`: `preparationScreen = null`; show a minimal start placeholder with `R.string.start_placeholder_title` and a button `R.string.start_placeholder_action` that calls `showPreparation()`; set `startPlaceholderShown = true`.
- `onPause`: stop the walk surface and debug probe only; leave preparation running so a short background trip does not lose the 20 s budget. `onDestroy`: `preparationScreen?.stop()`, then `preparationController?.close()`.
- `shouldSkipPreparation()` returns true only when debuggable and the extra is set.
- `readFixedCenter()` returns `LatLon` only when debuggable, both extras are present, and both doubles are finite and in range; otherwise null.

Update `WalkScreenTest` to launch with the skip extra:

```kotlin
val intent = Intent(
    InstrumentationRegistry.getInstrumentation().targetContext,
    MainActivity::class.java,
).putExtra(MainActivity.EXTRA_SKIP_DATA_PREPARATION, true)
val scenario = ActivityScenario.launch<MainActivity>(intent)
```

`PreparationScreenTest` adds the same `GrantPermissionRule` as `WalkScreenTest` and hosts the screen on `MainActivity` (launched with the skip extra) by calling `activity.setContentView(screen)` inside `scenario.onActivity`. Before replacing the content view, call `activity.findViewById<WalkSurface>(R.id.walk_surface)?.stop()` so the compass does not keep running. Use a `FakePreparationController` that stores the listener and emits states synchronously; perform `start()`, `emit()`, and click assertions inside `scenario.onActivity` so all view updates happen on the main thread. Tests:

- `showsProgressAndCancel`: emit `Preparing(3, 9)`; assert `progress.max == 9`, `progress.progress == 3`, primary text is `preparation_cancel`; click primary → `cancelCalls == 1`.
- `failureShowsMessageAndRetry`: emit `Failed(INSUFFICIENT_COVERAGE)`; assert status text equals `preparation_insufficient_coverage`, primary text is `preparation_retry`, secondary is visible; click primary → `retryCalls == 1`.
- `readyInvokesCallbackOnce`: emit `Ready` twice; `readyCalls == 1`.
- `cancelledInvokesCallbackOnce`: emit `Cancelled` twice; `cancelledCalls == 1`.
- `indeterminateWhenTotalIsZero`: emit `Preparing(0, 0)`; assert `progress.isIndeterminate`.

- [ ] **Step 6: Run the JVM tests and compile the instrumentation tests**

Run:

```bash
JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:testDebugUnitTest \
  --tests 'com.randomwalk.app.data.DataReadinessGateTest' \
  --tests 'com.randomwalk.app.ui.PreparationTextTest'

JAVA_HOME=/home/hkx/.local/lib/jdk-17.0.2 ./gradlew :app:assembleDebugAndroidTest
```

Expected: JVM tests PASS; androidTest compilation succeeds.

- [ ] **Step 7: Device checklist (Windows host)**

Use Android Studio Wi-Fi debugging; keep WSL for builds. Logcat filter: `adb logcat -s OnlineOsmFetcher TileCache DataReadinessGate AndroidPreparationController StreetDatabaseProvider`.

1. Clear app data, grant precise location outdoors with network. Start the app. Confirm the preparation screen shows the progress bar and “取消”, then the fake walk surface appears. Logcat shows a 3×3 required set and a successful fetch.
2. During preparation, tap “取消”. Confirm the placeholder appears, and “重新准备” starts preparation again.
3. Force-stop and relaunch. Confirm the walk surface appears immediately (cache hit, no fetch log).
4. Enable airplane mode, clear app data, relaunch. Confirm “需要网络连接才能准备此区域” with retry. Disable airplane mode, tap “重试”. Confirm readiness.
5. No-coverage check: force-stop, then start with a debug-only test center over open water:

```bash
adb shell am start -n com.randomwalk.app/.MainActivity \
  --ed com.randomwalk.app.extra.TEST_CENTER_LAT 0.0 \
  --ed com.randomwalk.app.extra.TEST_CENTER_LON 0.0
```

Expected: “该区域无可用步行数据” with retry and cancel. (The extras are ignored in non-debuggable builds.)
6. Confirm no preparation run exceeds the 20 s budget by more than one in-flight attempt timeout; cancellation returns within a few seconds.
7. A real prepackaged pack is not available until task 7.2; task 4.6's acceptance is the JVM pack test, and the pack-on-device check happens in 7.2.

After the checklist passes, mark `4.1`–`4.8` in `openspec/changes/random-walk-app/tasks.md` and commit:

```bash
git add openspec/changes/random-walk-app/tasks.md
git commit -m "chore: mark street-data tasks complete"
```

---

## Self-Review Notes

- **Spec coverage:** 4.1 schema/store, 4.2 parse/filter, 4.3 simplify/build, 4.4 online fetch, 4.5 tile cache/prefetch, 4.6 pack read path, 4.7 connected component, 4.8 readiness gate and UI. Street-data requirements: readiness gate (Task 8), walkable-only ways (Task 2), fixed tile caching (Tasks 1, 5), prepackaged precedence (Tasks 6, 8), time-bounded online preparation (Tasks 4, 5), coverage failure (Tasks 5, 8). ODbL/about attribution is task 6.4 and is explicitly out of scope here.
- **Step scan:** every task starts with a failing test, then implementation, then a passing run and a commit. Values that would otherwise be open (30 m spacing, 300 m snap, 3×3 tiles, 20 s/8 s/2 passes, 24 MiB cap) are fixed in Global Constraints.
- **Type consistency:** `StreetEdge.key`/`EdgeKey` is used by the store, repository, and builder; `TileCoverage` is implemented by `StreetStore` and `StreetRepository`; `TileEnsurer` is implemented by `TileCache` and consumed by `DataReadinessGate`; `PreparationFailure` is shared by the fetcher, cache, gate, and UI.
- **Lifecycle/concurrency:** preparation continues while the activity is merely paused; it is cancelled when precise permission is lost or the activity is destroyed. The controller uses one background executor, generation-tagged posts, and no main-thread joins; the ready provider stays open for 5.x.
- **Known limits accepted in this batch:** an isolated closed ring with no junction is skipped; a pack update with the same file name is not recopied (versioned pack filenames are the update mechanism); extending coverage beyond the initial 3×3 while walking is route-generation work (5.x) and will call `TileEnsurer` before the route reaches a tile edge.
