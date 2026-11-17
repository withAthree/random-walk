package com.randomwalk.app.data.model

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon

data class StreetNode(
    val id: Long,
    val point: LatLon,
    val tileId: Long,
)

data class EdgeKey(
    val wayId: Long,
    val fromNodeId: Long,
    val toNodeId: Long,
)

data class StreetEdge(
    val wayId: Long,
    val fromNodeId: Long,
    val toNodeId: Long,
    val lengthMeters: Double,
    val geometry: List<LatLon>,
) {
    val key: EdgeKey get() = EdgeKey(wayId, fromNodeId, toNodeId)
}

data class StreetGraph(
    val nodes: Map<Long, StreetNode>,
    val edges: List<StreetEdge>,
) {
    private val outgoingByNode: Map<Long, List<StreetEdge>> by lazy {
        edges.groupBy { it.fromNodeId }
    }

    fun outgoing(nodeId: Long): List<StreetEdge> = outgoingByNode[nodeId] ?: emptyList()

    fun nearestNode(
        point: LatLon,
        maxDistanceMeters: Double = MAX_SNAP_DISTANCE_METERS,
    ): StreetNode? {
        var nearest: StreetNode? = null
        var nearestDistance = Double.POSITIVE_INFINITY
        for (node in nodes.values) {
            val distance = GeoMath.haversineMeters(point, node.point)
            if (distance <= maxDistanceMeters && distance < nearestDistance) {
                nearest = node
                nearestDistance = distance
            }
        }
        return nearest
    }

    fun connectedComponent(startNodeId: Long): StreetGraph {
        if (startNodeId !in nodes) {
            return StreetGraph(emptyMap(), emptyList())
        }

        val adjacency = mutableMapOf<Long, MutableList<Long>>()
        for (edge in edges) {
            adjacency.getOrPut(edge.fromNodeId) { mutableListOf() }.add(edge.toNodeId)
            adjacency.getOrPut(edge.toNodeId) { mutableListOf() }.add(edge.fromNodeId)
        }

        val reachable = mutableSetOf<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(startNodeId)
        reachable.add(startNodeId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (neighbor in adjacency[current].orEmpty()) {
                if (reachable.add(neighbor)) {
                    queue.add(neighbor)
                }
            }
        }

        val componentNodes = nodes.filterKeys { it in reachable }
        val componentEdges = edges.filter {
            it.fromNodeId in reachable && it.toNodeId in reachable
        }
        return StreetGraph(componentNodes, componentEdges)
    }

    companion object {
        const val MAX_SNAP_DISTANCE_METERS = 300.0
    }
}
