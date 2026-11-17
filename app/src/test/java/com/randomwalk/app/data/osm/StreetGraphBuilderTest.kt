package com.randomwalk.app.data.osm

import com.randomwalk.app.data.model.EdgeKey
import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetGraphBuilderTest {

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

    @Test
    fun createsBothDirectionsForOrdinaryWay() {
        val p1 = LatLon(0.0, 0.0)
        val p2 = GeoMath.offsetMeters(p1, eastMeters = 50.0, northMeters = 0.0)
        val way = OsmWay(1, listOf(1, 2), listOf(p1, p2), mapOf("highway" to "footway"))

        val graph = StreetGraphBuilder().build(listOf(way))

        assertEquals(
            setOf(EdgeKey(1, 1, 2), EdgeKey(1, 2, 1)),
            graph.edges.map { it.key }.toSet(),
        )
    }

    @Test
    fun respectsPedestrianOneway() {
        val p1 = LatLon(0.0, 0.0)
        val p2 = GeoMath.offsetMeters(p1, eastMeters = 50.0, northMeters = 0.0)
        val forward = OsmWay(1, listOf(1, 2), listOf(p1, p2), mapOf("highway" to "footway", "oneway:foot" to "yes"))
        val backward = OsmWay(2, listOf(3, 4), listOf(p1, p2), mapOf("highway" to "footway", "oneway:foot" to "-1"))

        val forwardGraph = StreetGraphBuilder().build(listOf(forward))
        val backwardGraph = StreetGraphBuilder().build(listOf(backward))

        assertEquals(setOf(EdgeKey(1, 1, 2)), forwardGraph.edges.map { it.key }.toSet())
        assertEquals(setOf(EdgeKey(2, 4, 3)), backwardGraph.edges.map { it.key }.toSet())
    }

    @Test
    fun vehicleOnewayDoesNotRestrictPedestrians() {
        val p1 = LatLon(0.0, 0.0)
        val p2 = GeoMath.offsetMeters(p1, eastMeters = 50.0, northMeters = 0.0)
        val way = OsmWay(1, listOf(1, 2), listOf(p1, p2), mapOf("highway" to "footway", "oneway" to "yes"))

        val graph = StreetGraphBuilder().build(listOf(way))

        assertEquals(
            setOf(EdgeKey(1, 1, 2), EdgeKey(1, 2, 1)),
            graph.edges.map { it.key }.toSet(),
        )
    }

    @Test
    fun keepsOriginalGeometryForProjection() {
        val p0 = LatLon(0.0, 0.0)
        val p1 = GeoMath.offsetMeters(p0, eastMeters = 40.0, northMeters = 0.0)
        val p2 = GeoMath.offsetMeters(p1, eastMeters = 0.0, northMeters = 40.0)
        val way = OsmWay(1, listOf(1, 2, 3), listOf(p0, p1, p2), mapOf("highway" to "footway"))

        val graph = StreetGraphBuilder().build(listOf(way))
        val edge = graph.edges.single { it.key == EdgeKey(1, 1, 3) }

        assertTrue(edge.geometry.size > 2)
        assertTrue(edge.geometry.all { it in listOf(p0, p1, p2) })
        assertTrue(edge.lengthMeters > GeoMath.haversineMeters(p0, p2))
    }

    @Test
    fun closedRingWithoutJunctionProducesNoSelfLoopEdges() {
        val p0 = LatLon(0.0, 0.0)
        val p1 = GeoMath.offsetMeters(p0, eastMeters = 50.0, northMeters = 0.0)
        val p2 = GeoMath.offsetMeters(p1, eastMeters = 0.0, northMeters = 50.0)
        val way = OsmWay(10, listOf(10, 11, 12, 10), listOf(p0, p1, p2, p0), mapOf("highway" to "footway"))

        val graph = StreetGraphBuilder().build(listOf(way))

        assertTrue(graph.edges.none { it.fromNodeId == it.toNodeId })
    }

    @Test
    fun edgeLengthMatchesGeometry() {
        val p1 = LatLon(0.0, 0.0)
        val p2 = GeoMath.offsetMeters(p1, eastMeters = 30.0, northMeters = 20.0)
        val p3 = GeoMath.offsetMeters(p2, eastMeters = 10.0, northMeters = 30.0)
        val way = OsmWay(1, listOf(1, 2, 3), listOf(p1, p2, p3), mapOf("highway" to "footway"))

        val graph = StreetGraphBuilder().build(listOf(way))

        for (edge in graph.edges) {
            val expected = edge.geometry.zipWithNext()
                .sumOf { (a, b) -> GeoMath.haversineMeters(a, b) }
            assertEquals(expected, edge.lengthMeters, 1e-6)
        }
    }

    @Test
    fun filtersNonWalkableWays() {
        val p1 = LatLon(0.0, 0.0)
        val p2 = GeoMath.offsetMeters(p1, eastMeters = 50.0, northMeters = 0.0)
        val way = OsmWay(1, listOf(1, 2), listOf(p1, p2), mapOf("highway" to "motorway"))

        val graph = StreetGraphBuilder().build(listOf(way))

        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.edges.isEmpty())
    }
}
