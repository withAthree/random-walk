package com.randomwalk.app.data

import com.randomwalk.app.data.db.JdbcSqlDatabase
import com.randomwalk.app.data.db.StreetStore
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

class StreetRepositoryTest {

    private val sources = mutableListOf<StreetDatabase>()

    @After
    fun tearDown() {
        sources.forEach { it.close() }
    }

    private fun newSource(): StreetDatabase {
        val database = JdbcSqlDatabase.inMemory()
        val source = StreetDatabase(database)
        source.store.ensureSchema()
        sources.add(source)
        return source
    }

    private fun node(id: Long, point: LatLon): StreetNode =
        StreetNode(id, point, TileGrid.tileFor(point).id)

    private fun edge(
        wayId: Long,
        fromNodeId: Long,
        toNodeId: Long,
        geometry: List<LatLon>,
        lengthMeters: Double = 100.0,
    ): StreetEdge = StreetEdge(wayId, fromNodeId, toNodeId, lengthMeters, geometry)

    private fun graph(nodes: Map<Long, StreetNode>, edges: List<StreetEdge>): StreetGraph =
        StreetGraph(nodes = nodes, edges = edges)

    @Test
    fun repositoryReadsEdgesFromPackAndCache() {
        val pack = newSource()
        val cache = newSource()

        val westPoint = LatLon(0.001, -0.0001)
        val eastPoint = LatLon(0.001, 0.0001)
        val tileA = TileGrid.tileFor(westPoint).id
        val tileB = TileGrid.tileFor(eastPoint).id
        assertTrue(tileA != tileB)

        val packEdge = edge(1, 10, 11, listOf(westPoint, LatLon(0.001, -0.00005)))
        val cacheEdge = edge(2, 20, 21, listOf(eastPoint, LatLon(0.001, 0.00005)))
        pack.store.insertGraph(
            graph(
                mapOf(10L to node(10, westPoint), 11L to node(11, LatLon(0.001, -0.00005))),
                listOf(packEdge),
            ),
            setOf(tileA),
            1L,
        )
        cache.store.insertGraph(
            graph(
                mapOf(20L to node(20, eastPoint), 21L to node(21, LatLon(0.001, 0.00005))),
                listOf(cacheEdge),
            ),
            setOf(tileB),
            1L,
        )

        val result = StreetRepository(listOf(pack, cache)).loadGraph(setOf(tileA, tileB))

        assertEquals(setOf(packEdge.key, cacheEdge.key), result.edges.map { it.key }.toSet())
    }

    @Test
    fun repositoryPrefersPackOnDuplicateEdge() {
        val pack = newSource()
        val cache = newSource()

        val point = LatLon(0.001, -0.0001)
        val tile = TileGrid.tileFor(point).id
        val geometry = listOf(point, LatLon(0.001, -0.00005))
        val nodes = mapOf(1L to node(1, point), 2L to node(2, geometry.last()))
        pack.store.insertGraph(
            graph(nodes, listOf(edge(5, 1, 2, geometry, lengthMeters = 111.0))),
            setOf(tile),
            1L,
        )
        cache.store.insertGraph(
            graph(nodes, listOf(edge(5, 1, 2, geometry, lengthMeters = 999.0))),
            setOf(tile),
            1L,
        )

        val result = StreetRepository(listOf(pack, cache)).loadGraph(setOf(tile))

        assertEquals(1, result.edges.size)
        assertEquals(111.0, result.edges.single().lengthMeters, 0.0)
    }

    @Test
    fun repositoryLoadsEndpointNodesOutsideQueriedTiles() {
        val pack = newSource()

        val westPoint = LatLon(0.001, -0.0001)
        val eastPoint = LatLon(0.001, 0.0001)
        val tileA = TileGrid.tileFor(westPoint).id
        val tileB = TileGrid.tileFor(eastPoint).id
        assertTrue(tileA != tileB)

        val crossingEdge = edge(9, 10, 11, listOf(westPoint, eastPoint))
        pack.store.insertGraph(
            graph(
                mapOf(
                    10L to StreetNode(10, westPoint, tileA),
                    11L to StreetNode(11, eastPoint, tileA),
                ),
                listOf(crossingEdge),
            ),
            setOf(tileA),
            1L,
        )

        val result = StreetRepository(listOf(pack)).loadGraph(setOf(tileB))

        assertEquals(listOf(crossingEdge.key), result.edges.map { it.key })
        assertEquals(setOf(10L, 11L), result.nodes.keys)
    }

    @Test
    fun repositoryDeduplicatesNodesAcrossSources() {
        val pack = newSource()
        val cache = newSource()

        val point = LatLon(0.001, -0.0001)
        val tile = TileGrid.tileFor(point).id
        val duplicate = node(7, point)
        pack.store.insertGraph(graph(mapOf(7L to duplicate), emptyList()), setOf(tile), 1L)
        cache.store.insertGraph(graph(mapOf(7L to duplicate), emptyList()), setOf(tile), 1L)

        val result = StreetRepository(listOf(pack, cache)).loadGraph(setOf(tile))

        assertEquals(1, result.nodes.size)
        assertEquals(duplicate, result.nodes[7L])
    }

    @Test
    fun repositoryHasTileWhenPackCoversIt() {
        val pack = newSource()
        val cache = newSource()

        val westPoint = LatLon(0.001, -0.0001)
        val eastPoint = LatLon(0.001, 0.0001)
        val tileA = TileGrid.tileFor(westPoint).id
        val tileB = TileGrid.tileFor(eastPoint).id
        pack.store.insertGraph(graph(emptyMap(), emptyList()), setOf(tileA), 1L)

        val repository = StreetRepository(listOf(pack, cache))

        assertTrue(repository.hasTile(tileA))
        assertFalse(repository.hasTile(tileB))
        assertEquals(setOf(tileB), repository.missingTiles(setOf(tileA, tileB)))
    }

    @Test
    fun verifySchemaRejectsMissingOrNewerVersion() {
        val empty = JdbcSqlDatabase.inMemory()
        assertThrows(IllegalStateException::class.java) {
            StreetStore(empty).verifySchema()
        }
        empty.close()

        val newer = JdbcSqlDatabase.inMemory()
        newer.exec("CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL) WITHOUT ROWID")
        newer.exec("INSERT INTO meta(key, value) VALUES ('schema_version', '2')")
        assertThrows(IllegalStateException::class.java) {
            StreetStore(newer).verifySchema()
        }
        newer.close()
    }
}
