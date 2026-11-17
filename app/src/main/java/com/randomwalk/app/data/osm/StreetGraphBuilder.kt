package com.randomwalk.app.data.osm

import com.randomwalk.app.data.model.EdgeKey
import com.randomwalk.app.data.model.StreetEdge
import com.randomwalk.app.data.model.StreetGraph
import com.randomwalk.app.data.model.StreetNode
import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.TileGrid

/** Splits walkable OSM ways at shared nodes and builds simplified, directed [StreetEdge]s. */
class StreetGraphBuilder(
    private val simplifier: PolylineSimplifier = PolylineSimplifier(),
) {

    fun build(ways: List<OsmWay>): StreetGraph {
        val walkable = ways.filter { way ->
            WalkableWayFilter.isWalkable(way.tags) &&
                way.nodeIds.size == way.geometry.size &&
                way.geometry.size >= 2
        }

        val usage = mutableMapOf<Long, Int>()
        for (way in walkable) {
            for (nodeId in way.nodeIds) {
                usage[nodeId] = (usage[nodeId] ?: 0) + 1
            }
        }

        val nodes = LinkedHashMap<Long, StreetNode>()
        val edges = LinkedHashMap<EdgeKey, StreetEdge>()

        for (way in walkable) {
            val splitIndices = splitIndices(way, usage)
            for (pair in 0 until splitIndices.size - 1) {
                val start = splitIndices[pair]
                val end = splitIndices[pair + 1]
                if (end <= start) continue

                val from = way.nodeIds[start]
                val to = way.nodeIds[end]
                if (from == to) continue

                val simplified = simplifier.simplify(way.geometry.subList(start, end + 1))
                val length = simplified.zipWithNext()
                    .sumOf { (a, b) -> GeoMath.haversineMeters(a, b) }
                if (length < 1.0) continue

                nodes.putIfAbsent(
                    from,
                    StreetNode(from, simplified.first(), TileGrid.tileFor(simplified.first()).id),
                )
                nodes.putIfAbsent(
                    to,
                    StreetNode(to, simplified.last(), TileGrid.tileFor(simplified.last()).id),
                )

                for (direction in WalkableWayFilter.directions(way.tags)) {
                    val edge = when (direction) {
                        EdgeDirection.FORWARD -> StreetEdge(way.id, from, to, length, simplified)
                        EdgeDirection.BACKWARD -> StreetEdge(way.id, to, from, length, simplified.reversed())
                    }
                    edges.putIfAbsent(edge.key, edge)
                }
            }
        }

        return StreetGraph(nodes, edges.values.toList())
    }

    private fun splitIndices(way: OsmWay, usage: Map<Long, Int>): List<Int> {
        val indices = mutableListOf(0)
        for (index in 1 until way.nodeIds.lastIndex) {
            if ((usage[way.nodeIds[index]] ?: 0) >= 2) {
                indices.add(index)
            }
        }
        indices.add(way.nodeIds.lastIndex)
        return indices
    }
}
