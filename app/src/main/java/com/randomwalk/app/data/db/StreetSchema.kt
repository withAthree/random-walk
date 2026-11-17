package com.randomwalk.app.data.db

/** Creates and validates the street-data schema (version 1). */
object StreetSchema {
    const val CURRENT_VERSION = 1

    private const val SCHEMA_VERSION_KEY = "schema_version"

    fun ensureSchema(db: SqlDatabase) {
        val hasMeta = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'meta'",
            map = { it.string("name") },
        ).isNotEmpty()

        if (!hasMeta) {
            db.transaction {
                createSchema(db)
                setVersion(db, CURRENT_VERSION)
            }
            return
        }

        val version = readVersion(db)
        when {
            version < CURRENT_VERSION -> migrate(db, version)
            version > CURRENT_VERSION -> throw IllegalStateException(
                "unsupported newer schema version $version (max $CURRENT_VERSION)",
            )
            else -> Unit
        }
    }

    fun migrate(db: SqlDatabase, fromVersion: Int) {
        when (fromVersion) {
            0 -> db.transaction {
                createSchema(db)
                setVersion(db, CURRENT_VERSION)
            }
            1 -> Unit
            else -> throw IllegalStateException("unsupported schema migration from version $fromVersion")
        }
    }

    /** Read-only check that the database is exactly at [CURRENT_VERSION]. */
    fun verifySchema(db: SqlDatabase) {
        val hasMeta = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'meta'",
            map = { it.string("name") },
        ).isNotEmpty()
        if (!hasMeta) {
            throw IllegalStateException("missing meta table (schema not initialized)")
        }
        val version = readVersion(db)
        if (version != CURRENT_VERSION) {
            throw IllegalStateException(
                "unsupported schema version $version (expected $CURRENT_VERSION)",
            )
        }
    }

    internal fun readVersion(db: SqlDatabase): Int {
        val values = db.query(
            "SELECT value FROM meta WHERE key = ?",
            listOf(SCHEMA_VERSION_KEY),
        ) { it.string("value") }
        if (values.isEmpty()) {
            throw IllegalStateException("meta table is missing the schema_version row")
        }
        return values.first().toInt()
    }

    private fun setVersion(db: SqlDatabase, version: Int) {
        db.exec(
            "INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)",
            listOf(SCHEMA_VERSION_KEY, version.toString()),
        )
    }

    private fun createSchema(db: SqlDatabase) {
        db.exec(
            """
            CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.exec(
            """
            CREATE TABLE IF NOT EXISTS nodes (
                id INTEGER PRIMARY KEY NOT NULL,
                lat_e7 INTEGER NOT NULL,
                lon_e7 INTEGER NOT NULL,
                tile_id INTEGER NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.exec("CREATE INDEX IF NOT EXISTS nodes_tile ON nodes(tile_id)")
        db.exec(
            """
            CREATE TABLE IF NOT EXISTS edges (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                way_id INTEGER NOT NULL,
                from_node_id INTEGER NOT NULL,
                to_node_id INTEGER NOT NULL,
                length_m REAL NOT NULL,
                geometry BLOB NOT NULL,
                UNIQUE(way_id, from_node_id, to_node_id)
            )
            """.trimIndent(),
        )
        db.exec("CREATE INDEX IF NOT EXISTS edges_from ON edges(from_node_id)")
        db.exec("CREATE INDEX IF NOT EXISTS edges_to ON edges(to_node_id)")
        db.exec(
            """
            CREATE TABLE IF NOT EXISTS edge_tiles (
                tile_id INTEGER NOT NULL,
                edge_id INTEGER NOT NULL,
                PRIMARY KEY (tile_id, edge_id)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.exec("CREATE INDEX IF NOT EXISTS edge_tiles_edge ON edge_tiles(edge_id)")
        db.exec(
            """
            CREATE TABLE IF NOT EXISTS tiles (
                tile_id INTEGER PRIMARY KEY NOT NULL,
                south_e7 INTEGER NOT NULL,
                west_e7 INTEGER NOT NULL,
                north_e7 INTEGER NOT NULL,
                east_e7 INTEGER NOT NULL,
                fetched_at INTEGER NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
    }
}
