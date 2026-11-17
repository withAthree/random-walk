package com.randomwalk.app.data.model

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
}
