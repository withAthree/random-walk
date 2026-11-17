package com.randomwalk.app.data.db

import com.randomwalk.app.data.GeometryCodec
import com.randomwalk.app.data.TileCoverage
import com.randomwalk.app.data.TilePlanner
import com.randomwalk.app.data.model.EdgeKey
import com.randomwalk.app.data.model.StreetEdge
import com.randomwalk.app.data.model.StreetGraph
import com.randomwalk.app.data.model.StreetNode
import com.randomwalk.app.geo.E7
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.geo.TileGrid

/** Stores the street graph in SQLite, indexed by tile for range queries. */
class StreetStore(private val db: SqlDatabase) : TileCoverage {

    fun ensureSchema() = StreetSchema.ensureSchema(db)

    fun schemaVersion(): Int = StreetSchema.readVersion(db)

    fun verifySchema() = StreetSchema.verifySchema(db)

    override fun hasTile(tileId: Long): Boolean =
        db.query(
            "SELECT 1 AS present FROM tiles WHERE tile_id = ?",
            listOf(tileId),
        ) { it.int("present") }.isNotEmpty()

    fun forgetTiles(tileIds: Set<Long>) {
        if (tileIds.isEmpty()) return
        db.transaction {
            for (tileId in tileIds) {
                db.exec("DELETE FROM tiles WHERE tile_id = ?", listOf(tileId))
            }
        }
    }

    fun insertGraph(graph: StreetGraph, tileIds: Set<Long>, fetchedAtMillis: Long) {
        db.transaction {
            for (node in graph.nodes.values) {
                db.exec(
                    "INSERT OR REPLACE INTO nodes(id, lat_e7, lon_e7, tile_id) VALUES (?, ?, ?, ?)",
                    listOf(
                        node.id,
                        E7.encode(node.point.latitude),
                        E7.encode(node.point.longitude),
                        node.tileId,
                    ),
                )
            }
            for (edge in graph.edges) {
                db.exec(
                    "INSERT OR IGNORE INTO edges(way_id, from_node_id, to_node_id, length_m, geometry) VALUES (?, ?, ?, ?, ?)",
                    listOf(
                        edge.wayId,
                        edge.fromNodeId,
                        edge.toNodeId,
                        edge.lengthMeters,
                        GeometryCodec.encode(edge.geometry),
                    ),
                )
                val edgeId = db.query(
                    "SELECT id FROM edges WHERE way_id = ? AND from_node_id = ? AND to_node_id = ?",
                    listOf(edge.wayId, edge.fromNodeId, edge.toNodeId),
                ) { it.long("id") }.first()
                for (tileId in TilePlanner.tilesCovering(edge.geometry)) {
                    db.exec(
                        "INSERT OR IGNORE INTO edge_tiles(tile_id, edge_id) VALUES (?, ?)",
                        listOf(tileId, edgeId),
                    )
                }
            }
            for (tileId in tileIds) {
                val bounds = TileGrid.tileBounds(tileId)
                db.exec(
                    "INSERT OR REPLACE INTO tiles(tile_id, south_e7, west_e7, north_e7, east_e7, fetched_at) VALUES (?, ?, ?, ?, ?, ?)",
                    listOf(
                        tileId,
                        E7.encode(bounds.south),
                        E7.encode(bounds.west),
                        E7.encode(bounds.north),
                        E7.encode(bounds.east),
                        fetchedAtMillis,
                    ),
                )
            }
        }
    }

    fun nodesForTiles(tileIds: Set<Long>): List<StreetNode> {
        if (tileIds.isEmpty()) return emptyList()
        return db.query(
            "SELECT id, lat_e7, lon_e7, tile_id FROM nodes WHERE tile_id IN (${placeholders(tileIds.size)})",
            tileIds.toList(),
        ) { it.toNode() }
    }

    fun nodesByIds(nodeIds: Set<Long>): List<StreetNode> {
        if (nodeIds.isEmpty()) return emptyList()
        return nodeIds.toList().chunked(NODE_ID_CHUNK_SIZE).flatMap { chunk ->
            db.query(
                "SELECT id, lat_e7, lon_e7, tile_id FROM nodes WHERE id IN (${placeholders(chunk.size)})",
                chunk,
            ) { it.toNode() }
        }
    }

    fun edgesForTiles(tileIds: Set<Long>): List<StreetEdge> {
        if (tileIds.isEmpty()) return emptyList()
        return db.query(
            """
            SELECT DISTINCT e.id, e.way_id, e.from_node_id, e.to_node_id, e.length_m, e.geometry
            FROM edges e
            JOIN edge_tiles et ON et.edge_id = e.id
            WHERE et.tile_id IN (${placeholders(tileIds.size)})
            ORDER BY e.id
            """.trimIndent(),
            tileIds.toList(),
        ) { it.toEdge() }
    }

    fun edgesAtNode(nodeId: Long): List<StreetEdge> = db.query(
        "SELECT id, way_id, from_node_id, to_node_id, length_m, geometry FROM edges WHERE from_node_id = ? OR to_node_id = ? ORDER BY id",
        listOf(nodeId, nodeId),
    ) { it.toEdge() }

    fun edgeByKey(key: EdgeKey): StreetEdge? = db.query(
        "SELECT id, way_id, from_node_id, to_node_id, length_m, geometry FROM edges WHERE way_id = ? AND from_node_id = ? AND to_node_id = ?",
        listOf(key.wayId, key.fromNodeId, key.toNodeId),
    ) { it.toEdge() }.firstOrNull()

    fun edgeCountForTiles(tileIds: Set<Long>): Int {
        if (tileIds.isEmpty()) return 0
        return db.query(
            """
            SELECT COUNT(DISTINCT e.id) AS count
            FROM edges e
            JOIN edge_tiles et ON et.edge_id = e.id
            WHERE et.tile_id IN (${placeholders(tileIds.size)})
            """.trimIndent(),
            tileIds.toList(),
        ) { it.int("count") }.first()
    }

    private fun SqlRow.toNode(): StreetNode = StreetNode(
        id = long("id"),
        point = LatLon(E7.decode(int("lat_e7")), E7.decode(int("lon_e7"))),
        tileId = long("tile_id"),
    )

    private fun SqlRow.toEdge(): StreetEdge = StreetEdge(
        wayId = long("way_id"),
        fromNodeId = long("from_node_id"),
        toNodeId = long("to_node_id"),
        lengthMeters = double("length_m"),
        geometry = GeometryCodec.decode(blob("geometry")),
    )

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")

    private companion object {
        const val NODE_ID_CHUNK_SIZE = 500
    }
}
