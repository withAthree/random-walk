# Street Data Schema

The offline street graph is stored in a single SQLite database, one row per
node, directed edge, and tile, indexed so that edges and nodes can be loaded by
tile for range queries. The schema version is `1`.

## Schema version

`meta` holds key/value rows. The schema version is stored as:

```text
key = 'schema_version', value = '1'
```

`StreetSchema` treats a missing `schema_version` row as an error and does not
guess a version.

## DDL

One statement per execution (schema version `1`):

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

The schema targets SQLite 3.18 (Android API 26): it does not use `UPSERT`,
`RETURNING`, or `STRICT`.

## Geometry BLOB format

The `edges.geometry` column stores the polyline as a big-endian byte sequence:

```text
int32 count
count × (int32 lat_e7, int32 lon_e7)
```

- `count` is the number of `LatLon` points (always `>= 1`).
- `lat_e7` / `lon_e7` are the latitude/longitude in 1e-7 degree units
  (`E7.encode(degrees) = Math.round(degrees * 1e7).toInt()`).
- Total size is `4 + 8 * count` bytes.

`GeometryCodec.encode` rejects an empty list; `decode` rejects a non-positive
count or a size that does not match the count.

## Tile id formula

Tiles are 2000 m on a side, encoded as a single 64-bit integer.

```text
LAT_STEP_DEGREES       = 2000 / 111194.93          # ~0.017987
latitudeIndex          = floor(lat / LAT_STEP_DEGREES)
longitudeStepDegrees   = LAT_STEP_DEGREES / cos(centerLat)   # centerLat clamped to ±85°
longitudeIndex         = floor(normalizeLongitude(lon) / longitudeStepDegrees)
tile_id                = (latitudeIndex << 32) | (longitudeIndex & 0xFFFFFFFF)
```

`normalizeLongitude` wraps to `[-180, 180)`. The `tile_id` used in the schema is
the same value produced by `TileGrid.tileIdAt(lat, lon)`.

## Pack contents

An offline pack must contain rows for all five tables:

- `meta` — at least the `schema_version` row.
- `tiles` — one row per tile the pack claims to cover (`south_e7`/`west_e7`/
  `north_e7`/`east_e7` from `TileGrid.tileBounds`, plus `fetched_at`).
- `nodes` — every OSM node referenced by a packed edge.
- `edges` — every directed edge.
- `edge_tiles` — one row for every tile an edge's geometry touches.

Every `edges` row must have an `edge_tiles` row for every tile its geometry
touches (as computed by `TilePlanner.tilesCovering`), so tile-scoped edge
queries find every crossing edge.

## Directed edges

`edges` stores directed edges. A two-way street is stored as two separate rows:
one for the forward direction and one for the backward direction. Each row's
`from_node_id` and `to_node_id` name the direction, and the `UNIQUE(way_id,
from_node_id, to_node_id)` constraint deduplicates repeated inserts of the same
direction.
