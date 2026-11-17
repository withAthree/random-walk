package com.randomwalk.app.data.net

import com.randomwalk.app.data.PreparationFailure
import com.randomwalk.app.geo.TileBounds
import java.util.Locale

/** A raw HTTP response: the status code and the decoded body. */
data class HttpResponse(val code: Int, val body: String)

/** Transports a single POST request and can abort the in-flight one. */
interface TileFetchTransport {
    fun post(url: String, body: String, connectTimeoutMillis: Int, readTimeoutMillis: Int): HttpResponse
    fun cancel()
}

/** The outcome of fetching street data for a tile area. */
sealed interface OnlineFetchResult {
    data class Success(val json: String) : OnlineFetchResult
    data class Failure(val reason: PreparationFailure) : OnlineFetchResult
    data object Cancelled : OnlineFetchResult
}

/** Fetches street data for a tile area with a cancellation hook. */
interface TileFetcher {
    fun fetch(bounds: TileBounds, isCancelled: () -> Boolean = { false }): OnlineFetchResult
    fun cancel()
}

/** Builds the Overpass QL query for a bounding box. */
object OverpassQuery {
    fun forBounds(bounds: TileBounds): String = String.format(
        Locale.US,
        "[out:json][timeout:15];way[\"highway\"](%.7f,%.7f,%.7f,%.7f);out body geom;",
        bounds.south, bounds.west, bounds.north, bounds.east,
    )
}
