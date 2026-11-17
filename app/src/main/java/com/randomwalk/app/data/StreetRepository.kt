package com.randomwalk.app.data

import com.randomwalk.app.data.model.EdgeKey
import com.randomwalk.app.data.model.StreetEdge
import com.randomwalk.app.data.model.StreetGraph
import com.randomwalk.app.data.model.StreetNode

/**
 * Reads street data from ordered [sources]. Earlier sources (packs) take
 * precedence over later ones (the cache) for duplicate nodes and edges.
 */
class StreetRepository(
    private val sources: List<StreetDatabase>,
) : TileCoverage {

    override fun hasTile(tileId: Long): Boolean =
        sources.any { it.store.hasTile(tileId) }

    fun missingTiles(tileIds: Set<Long>): Set<Long> =
        tileIds.filterTo(HashSet()) { !hasTile(it) }

    fun loadGraph(tileIds: Set<Long>): StreetGraph {
        val nodes = LinkedHashMap<Long, StreetNode>()
        val edges = LinkedHashMap<EdgeKey, StreetEdge>()

        for (source in sources) {
            for (node in source.store.nodesForTiles(tileIds)) {
                nodes.putIfAbsent(node.id, node)
            }
            for (edge in source.store.edgesForTiles(tileIds)) {
                edges.putIfAbsent(edge.key, edge)
            }
        }

        val missingEndpoints = edges.values
            .flatMap { listOf(it.fromNodeId, it.toNodeId) }
            .filterTo(HashSet()) { !nodes.containsKey(it) }
        for (source in sources) {
            for (node in source.store.nodesByIds(missingEndpoints)) {
                nodes.putIfAbsent(node.id, node)
            }
        }

        return StreetGraph(nodes = nodes, edges = edges.values.toList())
    }
}
