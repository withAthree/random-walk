package com.randomwalk.app.data.db

import com.randomwalk.app.data.model.StreetEdge
import com.randomwalk.app.data.model.StreetGraph
import com.randomwalk.app.data.model.StreetNode
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.geo.TileGrid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetStoreTest {

    private var db: JdbcSqlDatabase? = null

    @After
    fun tearDown() {
        db?.close()
    }

    private fun inMemoryStore(): StreetStore {
        val database = JdbcSqlDatabase.inMemory()
        db = database
        return StreetStore(database)
    }

    @Test
    fun insertedEdgeIsFoundByTileAndByKey() {
        val store = inMemoryStore()
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

    @Test
    fun schemaVersionIsOneAndReopenKeepsData() {
        val store = inMemoryStore()
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

        store.ensureSchema()

        assertEquals(1, store.schemaVersion())
        assertEquals(listOf(edge.key), store.edgesForTiles(setOf(tileId)).map { it.key })
    }

    @Test
    fun newerSchemaVersionIsRejected() {
        val database = JdbcSqlDatabase.inMemory()
        db = database
        database.exec(
            "CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL) WITHOUT ROWID",
        )
        database.exec("INSERT INTO meta(key, value) VALUES ('schema_version', '2')")

        assertThrows(IllegalStateException::class.java) {
            StreetStore(database).ensureSchema()
        }
    }

    @Test
    fun sameEdgeInsertedTwiceKeepsOneRow() {
        val store = inMemoryStore()
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
        store.insertGraph(graph, setOf(tileId), 2_000L)

        assertEquals(1, store.edgesForTiles(setOf(tileId)).size)
        assertEquals(1, store.edgeCountForTiles(setOf(tileId)))
        val edgeTileRows = db!!.query(
            "SELECT COUNT(*) AS count FROM edge_tiles",
            map = { it.int("count") },
        ).first()
        assertEquals(1, edgeTileRows)
    }

    @Test
    fun edgeCrossingTileBoundaryIsFoundFromBothTiles() {
        val store = inMemoryStore()
        store.ensureSchema()
        val from = LatLon(0.001, -0.0001)
        val to = LatLon(0.001, 0.0001)
        val westTile = TileGrid.tileFor(from).id
        val eastTile = TileGrid.tileFor(to).id
        assertTrue(westTile != eastTile)

        val edge = StreetEdge(
            wayId = 9,
            fromNodeId = 10,
            toNodeId = 11,
            lengthMeters = 22.2,
            geometry = listOf(from, to),
        )
        val graph = StreetGraph(
            nodes = mapOf(
                10L to StreetNode(10, from, westTile),
                11L to StreetNode(11, to, eastTile),
            ),
            edges = listOf(edge),
        )

        store.insertGraph(graph, setOf(westTile), 1_000L)

        assertEquals(listOf(edge.key), store.edgesForTiles(setOf(eastTile)).map { it.key })
    }

    @Test
    fun forgetTilesMakesTileMissingAgain() {
        val store = inMemoryStore()
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

        store.forgetTiles(setOf(tileId))

        assertFalse(store.hasTile(tileId))
        assertEquals(listOf(edge.key), store.edgesForTiles(setOf(tileId)).map { it.key })
    }

    @Test
    fun nodesByIdsLoadsNodesOutsideQueriedTiles() {
        val store = inMemoryStore()
        store.ensureSchema()
        val westPoint = LatLon(0.001, -0.0001)
        val eastPoint = LatLon(0.001, 0.0001)
        val westTile = TileGrid.tileFor(westPoint).id
        val eastTile = TileGrid.tileFor(eastPoint).id
        val node = StreetNode(42, westPoint, westTile)
        val graph = StreetGraph(nodes = mapOf(node.id to node), edges = emptyList())

        store.insertGraph(graph, setOf(westTile), 1_000L)

        assertTrue(store.nodesForTiles(setOf(eastTile)).isEmpty())
        assertEquals(listOf(node.id), store.nodesByIds(setOf(node.id)).map { it.id })
    }
}
