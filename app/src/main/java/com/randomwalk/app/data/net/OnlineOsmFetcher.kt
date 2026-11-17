package com.randomwalk.app.data.net

import com.randomwalk.app.data.PreparationFailure
import com.randomwalk.app.geo.TileBounds
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder

/**
 * Fetches Overpass street data, rotating mirrors across passes within a fixed
 * total time budget.
 */
class OnlineOsmFetcher(
    private val mirrors: List<String>,
    private val transport: TileFetchTransport,
    private val clock: () -> Long,
    private val totalBudgetMillis: Int,
    private val attemptTimeoutMillis: Int,
    private val maxPasses: Int,
) : TileFetcher {

    constructor() : this(
        mirrors = DEFAULT_MIRRORS,
        transport = HttpUrlConnectionTransport(),
        clock = System::currentTimeMillis,
        totalBudgetMillis = 20_000,
        attemptTimeoutMillis = 8_000,
        maxPasses = 2,
    )

    init {
        require(mirrors.isNotEmpty()) { "mirrors must not be empty" }
        require(totalBudgetMillis > 0) { "totalBudgetMillis must be positive" }
        require(attemptTimeoutMillis > 0) { "attemptTimeoutMillis must be positive" }
        require(maxPasses > 0) { "maxPasses must be positive" }
    }

    @Volatile
    private var cancelled = false

    override fun fetch(bounds: TileBounds, isCancelled: () -> Boolean): OnlineFetchResult {
        val body = "data=" + URLEncoder.encode(OverpassQuery.forBounds(bounds), "UTF-8")
        val started = clock()
        var sawTimeout = false
        var sawNetworkFailure = false
        var sawHttpFailure = false

        for (pass in 0 until maxPasses) {
            for (mirror in mirrors) {
                if (cancelled || isCancelled()) return OnlineFetchResult.Cancelled

                val remaining = totalBudgetMillis - (clock() - started)
                if (remaining <= 0) return OnlineFetchResult.Failure(PreparationFailure.TIMEOUT)
                val timeout = minOf(attemptTimeoutMillis, remaining.toInt())

                try {
                    val response = transport.post(mirror, body, timeout, timeout)
                    when {
                        response.code == 200 -> {
                            val trimmed = response.body.trim()
                            if (trimmed.startsWith("{") && trimmed.contains("\"elements\"")) {
                                return OnlineFetchResult.Success(response.body)
                            }
                            sawHttpFailure = true
                        }
                        response.code == 408 || response.code == 429 || response.code >= 500 -> {
                            sawHttpFailure = true
                        }
                        response.code in 400..499 -> {
                            return OnlineFetchResult.Failure(PreparationFailure.SERVER_ERROR)
                        }
                        else -> {
                            sawHttpFailure = true
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    sawTimeout = true
                } catch (e: IOException) {
                    sawNetworkFailure = true
                    if (cancelled || isCancelled()) return OnlineFetchResult.Cancelled
                }
            }
        }

        return when {
            sawHttpFailure -> OnlineFetchResult.Failure(PreparationFailure.SERVER_ERROR)
            sawTimeout -> OnlineFetchResult.Failure(PreparationFailure.TIMEOUT)
            else -> OnlineFetchResult.Failure(PreparationFailure.NO_NETWORK)
        }
    }

    override fun cancel() {
        cancelled = true
        transport.cancel()
    }

    companion object {
        val DEFAULT_MIRRORS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )
    }
}
