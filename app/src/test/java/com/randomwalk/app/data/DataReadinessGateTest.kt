package com.randomwalk.app.data

import com.randomwalk.app.data.db.JdbcSqlDatabase
import com.randomwalk.app.data.model.StreetEdge
import com.randomwalk.app.data.model.StreetGraph
import com.randomwalk.app.data.model.StreetNode
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.geo.TileGrid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataReadinessGateTest {

    private val databases = mutableListOf<StreetDatabase>()

    @After
    fun tearDown() {
        databases.forEach { it.close() }
    }

    private fun node(id: Long, point: LatLon): StreetNode =
        StreetNode(id, point, TileGrid.tileFor(point).id)

    private fun edge(
        wayId: Long,
        fromNodeId: Long,
        toNodeId: Long,
        geometry: List<LatLon>,
    ): StreetEdge = StreetEdge(wayId, fromNodeId, toNodeId, 100.0, geometry)

    private fun repositoryWith(graph: StreetGraph): StreetRepository {
        val database = StreetDatabase(JdbcSqlDatabase.inMemory())
        database.store.ensureSchema()
        databases.add(database)
        val tileIds = graph.nodes.values.map { it.tileId }.toSet()
        database.store.insertGraph(graph, tileIds, 1L)
        return StreetRepository(listOf(database))
    }

    private fun emptyRepository(): StreetRepository = StreetRepository(emptyList())

    @Test
    fun emptyNetworkReportsInsufficientCoverage() {
        val gate = DataReadinessGate(
            FakeTileEnsurer(TileEnsureResult.Covered(0)),
            emptyRepository(),
        )

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

    @Test
    fun coveredByPackStartsReadyWithoutNetwork() {
        val center = LatLon(0.0, 0.0)
        val graph = StreetGraph(
            nodes = mapOf(
                1L to node(1, center),
                2L to node(2, LatLon(0.0005, 0.0)),
            ),
            edges = listOf(edge(10, 1, 2, listOf(center, LatLon(0.0005, 0.0)))),
        )
        val fake = FakeTileEnsurer(TileEnsureResult.Covered(0))
        val gate = DataReadinessGate(fake, repositoryWith(graph))

        val result = gate.prepare(center)

        assertTrue(result is PreparationResult.Ready)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun readyGraphIsConnectedComponentAroundNearestNode() {
        val center = LatLon(0.0, 0.0)
        val mainComponent = StreetGraph(
            nodes = mapOf(
                1L to node(1, center),
                2L to node(2, LatLon(0.0005, 0.0)),
                3L to node(3, LatLon(0.0010, 0.0)),
            ),
            edges = listOf(
                edge(10, 1, 2, listOf(center, LatLon(0.0005, 0.0))),
                edge(10, 2, 3, listOf(LatLon(0.0005, 0.0), LatLon(0.0010, 0.0))),
            ),
        )
        val island = StreetGraph(
            nodes = mapOf(
                4L to node(4, LatLon(0.005, 0.005)),
                5L to node(5, LatLon(0.006, 0.005)),
            ),
            edges = listOf(
                edge(20, 4, 5, listOf(LatLon(0.005, 0.005), LatLon(0.006, 0.005))),
            ),
        )
        val database = StreetDatabase(JdbcSqlDatabase.inMemory())
        database.store.ensureSchema()
        databases.add(database)
        for (part in listOf(mainComponent, island)) {
            database.store.insertGraph(
                part,
                part.nodes.values.map { it.tileId }.toSet(),
                1L,
            )
        }
        val gate = DataReadinessGate(
            FakeTileEnsurer(TileEnsureResult.Covered(0)),
            StreetRepository(listOf(database)),
        )

        val result = gate.prepare(center)

        val ready = result as PreparationResult.Ready
        assertEquals(setOf(1L, 2L, 3L), ready.graph.nodes.keys)
        assertEquals(setOf(1L, 2L, 3L).size, ready.graph.nodes.size)
        assertEquals(2, ready.graph.edges.size)
    }

    @Test
    fun cancelReturnsCancelled() {
        val gate = DataReadinessGate(
            FakeTileEnsurer(TileEnsureResult.Cancelled),
            emptyRepository(),
        )

        assertEquals(
            PreparationResult.Cancelled,
            gate.prepare(LatLon(0.0, 0.0)),
        )
    }

    @Test
    fun forceRefreshIsForwarded() {
        val fake = FakeTileEnsurer(TileEnsureResult.Covered(0))
        val gate = DataReadinessGate(fake, emptyRepository())

        gate.prepare(LatLon(0.0, 0.0), forceRefresh = true)

        assertEquals(true, fake.lastForceRefresh)
    }

    @Test
    fun progressIsForwarded() {
        val fake = FakeTileEnsurer(TileEnsureResult.Covered(0)).apply {
            emitProgress = 3 to 9
        }
        val gate = DataReadinessGate(fake, emptyRepository())
        val reported = mutableListOf<Pair<Int, Int>>()

        gate.prepare(LatLon(0.0, 0.0), onProgress = { completed, total ->
            reported.add(completed to total)
        })

        assertEquals(listOf(3 to 9), reported)
    }

    @Test
    fun cancelDelegatesToEnsurer() {
        val fake = FakeTileEnsurer(TileEnsureResult.Cancelled)
        val gate = DataReadinessGate(fake, emptyRepository())

        gate.cancel()

        assertEquals(1, fake.cancelCalls)
    }

    private class FakeTileEnsurer(
        var result: TileEnsureResult,
    ) : TileEnsurer {
        var callCount = 0
        var lastForceRefresh: Boolean? = null
        var cancelCalls = 0
        var emitProgress: Pair<Int, Int>? = null

        override fun ensureTiles(
            center: LatLon,
            forceRefresh: Boolean,
            isCancelled: () -> Boolean,
            onProgress: (Int, Int) -> Unit,
        ): TileEnsureResult {
            callCount++
            lastForceRefresh = forceRefresh
            emitProgress?.let { onProgress(it.first, it.second) }
            return result
        }

        override fun cancel() {
            cancelCalls++
        }
    }
}
