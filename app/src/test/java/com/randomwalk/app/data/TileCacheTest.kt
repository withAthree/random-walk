package com.randomwalk.app.data

import com.randomwalk.app.data.db.JdbcSqlDatabase
import com.randomwalk.app.data.db.StreetStore
import com.randomwalk.app.data.model.StreetGraph
import com.randomwalk.app.data.net.OnlineFetchResult
import com.randomwalk.app.data.net.TileFetcher
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.geo.TileBounds
import com.randomwalk.app.geo.TileGrid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTileFetcher(private val result: OnlineFetchResult) : TileFetcher {
    var calls = 0
    override fun fetch(bounds: TileBounds, isCancelled: () -> Boolean): OnlineFetchResult {
        calls++
        return result
    }

    override fun cancel() {}
}

class TileCacheTest {

    private var db: JdbcSqlDatabase? = null

    // A point in the middle of the tile containing (0, 0). A corner point like (0, 0)
    // collapses the 3x3 block because metric offsets land on angular tile boundaries.
    private val center = TileGrid.tileFor(LatLon(0.0, 0.0)).center
    private val centerTile = TileGrid.tileFor(center).id

    private val SUCCESS_JSON =
        """{"elements":[{"type":"way","id":1,"nodes":[1,2],"geometry":[{"lat":0.009,"lon":0.009},{"lat":0.009,"lon":0.010}],"tags":{"highway":"footway"}}]}"""

    private val CROSSING_JSON =
        """{"elements":[{"type":"way","id":9,"nodes":[10,11],"geometry":[{"lat":0.001,"lon":-0.0001},{"lat":0.001,"lon":0.0001}],"tags":{"highway":"footway"}}]}"""

    @After
    fun tearDown() {
        db?.close()
    }

    private fun inMemoryStore(): StreetStore {
        val database = JdbcSqlDatabase.inMemory()
        db = database
        val store = StreetStore(database)
        store.ensureSchema()
        return store
    }

    @Test
    fun firstCallFetchesNineTilesAndSecondCallUsesCache() {
        val store = inMemoryStore()
        val fetcher = FakeTileFetcher(OnlineFetchResult.Success(SUCCESS_JSON))
        val cache = TileCache(store, store, fetcher)
        val progress = mutableListOf<Pair<Int, Int>>()

        val first = cache.ensureTiles(
            center,
            onProgress = { done, total -> progress += done to total },
        )
        val second = cache.ensureTiles(
            center,
            onProgress = { done, total -> progress += done to total },
        )

        assertEquals(TileEnsureResult.Covered(9), first)
        assertEquals(TileEnsureResult.Covered(0), second)
        assertEquals(1, fetcher.calls)
        assertEquals(listOf(0 to 9, 9 to 9), progress)
    }

    @Test
    fun alreadyStoredTileIsSkipped() {
        val store = inMemoryStore()
        val fetcher = FakeTileFetcher(OnlineFetchResult.Success(SUCCESS_JSON))
        val cache = TileCache(store, store, fetcher)
        store.insertGraph(StreetGraph(emptyMap(), emptyList()), setOf(centerTile), 1_000L)

        val result = cache.ensureTiles(center)

        assertEquals(TileEnsureResult.Covered(8), result)
        assertEquals(1, fetcher.calls)
        assertTrue(store.hasTile(centerTile))
        assertEquals(9, TilePlanner.requiredTiles(center).count { store.hasTile(it) })
    }

    @Test
    fun successfulFetchPersistsNodesEdgesAndTiles() {
        val store = inMemoryStore()
        val fetcher = FakeTileFetcher(OnlineFetchResult.Success(SUCCESS_JSON))
        val cache = TileCache(store, store, fetcher)

        cache.ensureTiles(center)

        assertTrue(store.hasTile(centerTile))
        assertTrue(store.edgesForTiles(setOf(centerTile)).isNotEmpty())
        assertEquals(
            setOf(1L, 2L),
            store.nodesForTiles(setOf(centerTile)).map { it.id }.toSet(),
        )
    }

    @Test
    fun emptyResponseReportsInsufficientCoverage() {
        val store = inMemoryStore()
        val fetcher = FakeTileFetcher(OnlineFetchResult.Success("""{"elements":[]}"""))
        val cache = TileCache(store, store, fetcher)

        val result = cache.ensureTiles(center)

        assertEquals(
            TileEnsureResult.Failed(PreparationFailure.INSUFFICIENT_COVERAGE),
            result,
        )
        assertFalse(store.hasTile(centerTile))
        assertTrue(store.edgesForTiles(setOf(centerTile)).isEmpty())
    }

    @Test
    fun malformedJsonReportsServerError() {
        val store = inMemoryStore()
        val fetcher = FakeTileFetcher(OnlineFetchResult.Success("""{"""))
        val cache = TileCache(store, store, fetcher)

        val result = cache.ensureTiles(center)

        assertEquals(
            TileEnsureResult.Failed(PreparationFailure.SERVER_ERROR),
            result,
        )
        assertFalse(store.hasTile(centerTile))
    }

    @Test
    fun cancelledFetchPersistsNothing() {
        val store = inMemoryStore()
        val fetcher = FakeTileFetcher(OnlineFetchResult.Cancelled)
        val cache = TileCache(store, store, fetcher)

        val result = cache.ensureTiles(center)

        assertEquals(TileEnsureResult.Cancelled, result)
        assertFalse(store.hasTile(centerTile))
    }

    @Test
    fun forceRefreshRefetchesCachedTiles() {
        val store = inMemoryStore()
        val fetcher = FakeTileFetcher(OnlineFetchResult.Success(SUCCESS_JSON))
        val cache = TileCache(store, store, fetcher)

        assertEquals(TileEnsureResult.Covered(9), cache.ensureTiles(center))
        val refreshed = cache.ensureTiles(center, forceRefresh = true)

        assertEquals(TileEnsureResult.Covered(9), refreshed)
        assertEquals(2, fetcher.calls)
        assertEquals(2, store.edgesForTiles(setOf(centerTile)).size)
    }

    @Test
    fun edgeCrossingTileBoundaryIsStoredForBothTiles() {
        val store = inMemoryStore()
        val fetcher = FakeTileFetcher(OnlineFetchResult.Success(CROSSING_JSON))
        val cache = TileCache(store, store, fetcher)
        val westTile = TileGrid.tileFor(LatLon(0.001, -0.0001)).id
        val eastTile = TileGrid.tileFor(LatLon(0.001, 0.0001)).id

        cache.ensureTiles(center)

        assertTrue(store.edgesForTiles(setOf(westTile)).isNotEmpty())
        assertTrue(store.edgesForTiles(setOf(eastTile)).isNotEmpty())
    }
}
