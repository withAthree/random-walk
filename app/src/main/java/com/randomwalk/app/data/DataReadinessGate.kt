package com.randomwalk.app.data

import com.randomwalk.app.geo.LatLon

/**
 * Prepares a walkable street graph around [center]: ensures the tiles are
 * present, then loads the connected component around the nearest node.
 */
class DataReadinessGate(
    private val tileEnsurer: TileEnsurer,
    private val repository: StreetRepository,
) {
    fun prepare(
        center: LatLon,
        forceRefresh: Boolean = false,
        isCancelled: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): PreparationResult {
        when (val ensured = tileEnsurer.ensureTiles(center, forceRefresh, isCancelled, onProgress)) {
            is TileEnsureResult.Cancelled -> return PreparationResult.Cancelled
            is TileEnsureResult.Failed -> return PreparationResult.Failed(ensured.reason)
            is TileEnsureResult.Covered -> Unit
        }

        if (isCancelled()) return PreparationResult.Cancelled

        val graph = repository.loadGraph(TilePlanner.requiredTiles(center))
        val start = graph.nearestNode(center)
            ?: return PreparationResult.Failed(PreparationFailure.INSUFFICIENT_COVERAGE)

        val component = graph.connectedComponent(start.id)
        if (component.edges.isEmpty()) {
            return PreparationResult.Failed(PreparationFailure.INSUFFICIENT_COVERAGE)
        }
        return PreparationResult.Ready(component)
    }

    fun cancel() {
        tileEnsurer.cancel()
    }
}
