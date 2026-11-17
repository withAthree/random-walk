package com.randomwalk.app.data

import com.randomwalk.app.data.model.EdgeKey
import com.randomwalk.app.data.model.StreetEdge
import com.randomwalk.app.data.model.StreetGraph
import com.randomwalk.app.data.model.StreetNode
import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetGraphTest {

    private val main = listOf(
        streetNode(1, LatLon(0.0, 0.0)),
        streetNode(2, LatLon(0.0, 0.001)),
        streetNode(3, LatLon(0.0, 0.002)),
        streetNode(10, LatLon(1.0, 0.0)),
        streetNode(11, LatLon(1.0, 0.001)),
    )
    private val edges = listOf(
        edge(1, 1, 2), edge(1, 2, 1),
        edge(1, 2, 3), edge(1, 3, 2),
        edge(2, 10, 11), edge(2, 11, 10),
    )
    private val graph = StreetGraph(main.associateBy { it.id }, edges)

    @Test
    fun outgoingReturnsOnlyFromNodeEdges() {
        val outgoing = graph.outgoing(2).map { it.key }.toSet()

        assertEquals(
            setOf(EdgeKey(1, 2, 1), EdgeKey(1, 2, 3)),
            outgoing,
        )
    }

    @Test
    fun nearestNodePicksClosestWithinLimit() {
        val node2 = graph.nodes.getValue(2)
        val point = GeoMath.offsetMeters(node2.point, eastMeters = 10.0, northMeters = 0.0)

        assertEquals(node2, graph.nearestNode(point))
    }

    @Test
    fun nearestNodeReturnsNullBeyond300m() {
        val node2 = graph.nodes.getValue(2)
        val within = GeoMath.offsetMeters(node2.point, eastMeters = 0.0, northMeters = 299.0)
        val beyond = GeoMath.offsetMeters(node2.point, eastMeters = 0.0, northMeters = 301.0)

        assertEquals(node2, graph.nearestNode(within))
        assertNull(graph.nearestNode(beyond))
    }

    @Test
    fun connectedComponentExcludesDisconnectedIsland() {
        val component = graph.connectedComponent(2)

        assertEquals(setOf(1L, 2L, 3L), component.nodes.keys)
        assertEquals(4, component.edges.size)
        assertTrue(component.nodes.keys.none { it == 10L || it == 11L })
        assertTrue(component.edges.none { it.wayId == 2L })
    }

    @Test
    fun connectedComponentOfMissingStartIsEmpty() {
        val component = graph.connectedComponent(999)

        assertTrue(component.nodes.isEmpty())
        assertTrue(component.edges.isEmpty())
    }

    @Test
    fun connectedComponentKeepsStartNodeWithoutEdges() {
        val isolated = StreetGraph(
            mapOf(7L to streetNode(7, LatLon(0.0, 0.0))),
            emptyList(),
        )

        val component = isolated.connectedComponent(7)

        assertEquals(setOf(7L), component.nodes.keys)
        assertTrue(component.edges.isEmpty())
    }
}

private fun streetNode(id: Long, point: LatLon) = StreetNode(id, point, tileId = 0L)

private fun edge(wayId: Long, fromNodeId: Long, toNodeId: Long) = StreetEdge(
    wayId = wayId,
    fromNodeId = fromNodeId,
    toNodeId = toNodeId,
    lengthMeters = 0.0,
    geometry = emptyList(),
)
