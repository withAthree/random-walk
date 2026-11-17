package com.randomwalk.app.data

import com.randomwalk.app.data.db.StreetStore
import com.randomwalk.app.data.net.OnlineFetchResult
import com.randomwalk.app.data.net.TileFetcher
import com.randomwalk.app.data.osm.OsmParseException
import com.randomwalk.app.data.osm.OsmParser
import com.randomwalk.app.data.osm.StreetGraphBuilder
import com.randomwalk.app.data.osm.WalkableWayFilter
import com.randomwalk.app.geo.LatLon

/** The outcome of making street data available for a walk area. */
sealed interface TileEnsureResult {
    /** [fetchedTiles] tile rows were written in this call; 0 means the cache already covered everything. */
    data class Covered(val fetchedTiles: Int) : TileEnsureResult

    data class Failed(val reason: PreparationFailure) : TileEnsureResult

    data object Cancelled : TileEnsureResult
}

/** Ensures the street data for a walk area is present in [TileCoverage]. */
interface TileEnsurer {
    fun ensureTiles(
        center: LatLon,
        forceRefresh: Boolean = false,
        isCancelled: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): TileEnsureResult

    fun cancel()
}

/**
 * Fetches the 3x3 tile block around [center] and persists its street graph.
 *
 * Already-covered tiles are skipped, so a repeat call is a cache hit. Tiles are only
 * marked after a response yielded a usable graph; an empty or malformed response leaves
 * them unmarked so they can be fetched again ([forceRefresh] is the explicit retry path
 * for a tile that was previously marked but is actually empty).
 */
class TileCache(
    private val coverage: TileCoverage,
    private val store: StreetStore,
    private val fetcher: TileFetcher,
    private val builder: StreetGraphBuilder = StreetGraphBuilder(),
    private val clock: () -> Long = System::currentTimeMillis,
) : TileEnsurer {

    override fun ensureTiles(
        center: LatLon,
        forceRefresh: Boolean,
        isCancelled: () -> Boolean,
        onProgress: (Int, Int) -> Unit,
    ): TileEnsureResult {
        val required = TilePlanner.requiredTiles(center)
        if (forceRefresh) {
            store.forgetTiles(required)
        }

        val missing = required.filterNot(coverage::hasTile).toSet()
        if (missing.isEmpty()) {
            return TileEnsureResult.Covered(0)
        }

        onProgress(0, missing.size)

        val json = when (val result = fetcher.fetch(TilePlanner.boundsOf(missing), isCancelled)) {
            is OnlineFetchResult.Cancelled -> return TileEnsureResult.Cancelled
            is OnlineFetchResult.Failure -> return TileEnsureResult.Failed(result.reason)
            is OnlineFetchResult.Success -> result.json
        }

        if (isCancelled()) return TileEnsureResult.Cancelled

        val walkableWays = try {
            OsmParser.parse(json).ways.filter { WalkableWayFilter.isWalkable(it.tags) }
        } catch (e: OsmParseException) {
            return TileEnsureResult.Failed(PreparationFailure.SERVER_ERROR)
        }

        if (walkableWays.isEmpty()) {
            return TileEnsureResult.Failed(PreparationFailure.INSUFFICIENT_COVERAGE)
        }

        val graph = builder.build(walkableWays)
        if (graph.edges.isEmpty()) {
            return TileEnsureResult.Failed(PreparationFailure.INSUFFICIENT_COVERAGE)
        }

        if (isCancelled()) return TileEnsureResult.Cancelled

        store.insertGraph(graph, missing, clock())

        onProgress(missing.size, missing.size)
        return TileEnsureResult.Covered(missing.size)
    }

    override fun cancel() {
        fetcher.cancel()
    }
}
