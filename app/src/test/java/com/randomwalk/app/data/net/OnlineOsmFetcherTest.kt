package com.randomwalk.app.data.net

import com.randomwalk.app.data.PreparationFailure
import com.randomwalk.app.geo.TileBounds
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OnlineOsmFetcherTest {

    private val servers = mutableListOf<HttpServer>()

    private val SAMPLE_JSON =
        """{"elements":[{"type":"way","id":1,"nodes":[1,2],"geometry":[{"lat":0.0,"lon":0.0},{"lat":0.0,"lon":0.001}],"tags":{"highway":"footway"}}]}"""

    @After
    fun tearDown() {
        servers.forEach { it.stop(0) }
        servers.clear()
    }

    private fun startServer(handler: HttpHandler): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", handler)
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/"
    }

    private fun readBody(exchange: HttpExchange): String =
        exchange.requestBody.readBytes().toString(Charsets.UTF_8)

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        exchange.requestBody.readBytes()
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun fetcher(
        mirrors: List<String>,
        clock: () -> Long = { 0L },
        totalBudgetMillis: Int = 20_000,
        attemptTimeoutMillis: Int = 8_000,
        maxPasses: Int = 2,
    ): OnlineOsmFetcher = OnlineOsmFetcher(
        mirrors = mirrors,
        transport = HttpUrlConnectionTransport(),
        clock = clock,
        totalBudgetMillis = totalBudgetMillis,
        attemptTimeoutMillis = attemptTimeoutMillis,
        maxPasses = maxPasses,
    )

    private fun bounds() = TileBounds(south = 0.0, west = 0.0, north = 0.0, east = 0.0)

    @Test
    fun queryFormatsBoundingBoxAndUsesGeomOutput() {
        val query = OverpassQuery.forBounds(bounds())

        assertTrue(query.startsWith("""[out:json][timeout:15];way["highway"]("""))
        assertTrue(query.contains("0.0000000,0.0000000"))
        assertTrue(query.endsWith(");out body geom;"))
    }

    @Test
    fun requestBodyIsFormEncoded() {
        val recorded = AtomicReference<String>()
        val url = startServer { exchange ->
            recorded.set(readBody(exchange))
            respond(exchange, 200, SAMPLE_JSON)
        }

        val result = fetcher(listOf(url), maxPasses = 1).fetch(bounds())

        assertTrue(result is OnlineFetchResult.Success)
        val body = recorded.get()
        assertTrue(body.startsWith("data=%5Bout%3Ajson%5D"))
        assertTrue(body.contains("0.0000000%2C0.0000000"))
        assertFalse(body.contains("["))
        assertFalse(body.contains("]"))
        assertFalse(body.contains(" "))
    }

    @Test
    fun retriesAlternateMirrorAfterServerError() {
        val primaryCount = AtomicInteger()
        val secondaryCount = AtomicInteger()
        val primary = startServer { exchange ->
            primaryCount.incrementAndGet()
            respond(exchange, 500, "server error")
        }
        val secondary = startServer { exchange ->
            secondaryCount.incrementAndGet()
            respond(exchange, 200, SAMPLE_JSON)
        }

        val result = fetcher(listOf(primary, secondary), maxPasses = 1).fetch(bounds())

        assertEquals(OnlineFetchResult.Success(SAMPLE_JSON), result)
        assertEquals(1, primaryCount.get())
        assertEquals(1, secondaryCount.get())
    }

    @Test
    fun allMirrorsServerError() {
        val primaryCount = AtomicInteger()
        val secondaryCount = AtomicInteger()
        val primary = startServer { exchange ->
            primaryCount.incrementAndGet()
            respond(exchange, 500, "server error")
        }
        val secondary = startServer { exchange ->
            secondaryCount.incrementAndGet()
            respond(exchange, 500, "server error")
        }

        val result = fetcher(listOf(primary, secondary), maxPasses = 2).fetch(bounds())

        assertEquals(OnlineFetchResult.Failure(PreparationFailure.SERVER_ERROR), result)
        assertEquals(4, primaryCount.get() + secondaryCount.get())
    }

    @Test
    fun allMirrorsTimeoutReportsTimeout() {
        val primary = startServer { exchange ->
            Thread.sleep(500)
            respond(exchange, 200, SAMPLE_JSON)
        }
        val secondary = startServer { exchange ->
            Thread.sleep(500)
            respond(exchange, 200, SAMPLE_JSON)
        }

        val result = fetcher(
            mirrors = listOf(primary, secondary),
            clock = System::currentTimeMillis,
            totalBudgetMillis = 1_000,
            attemptTimeoutMillis = 50,
            maxPasses = 2,
        ).fetch(bounds())

        assertEquals(OnlineFetchResult.Failure(PreparationFailure.TIMEOUT), result)
    }

    @Test
    fun allConnectionFailuresReportNoNetwork() {
        val result = fetcher(listOf("http://127.0.0.1:1"), maxPasses = 1).fetch(bounds())

        assertEquals(OnlineFetchResult.Failure(PreparationFailure.NO_NETWORK), result)
    }

    @Test
    fun htmlResponseIsRetriedAndClassified() {
        val primaryCount = AtomicInteger()
        val primary = startServer { exchange ->
            primaryCount.incrementAndGet()
            respond(exchange, 200, "<html>error</html>")
        }
        val secondary = startServer { exchange ->
            respond(exchange, 200, SAMPLE_JSON)
        }

        val result = fetcher(listOf(primary, secondary), maxPasses = 1).fetch(bounds())

        assertEquals(OnlineFetchResult.Success(SAMPLE_JSON), result)
        assertEquals(1, primaryCount.get())
    }

    @Test
    fun cancellationStopsBeforeFirstRequest() {
        val count = AtomicInteger()
        val url = startServer { exchange ->
            count.incrementAndGet()
            respond(exchange, 200, SAMPLE_JSON)
        }

        val result = fetcher(listOf(url), maxPasses = 1).fetch(bounds()) { true }

        assertEquals(OnlineFetchResult.Cancelled, result)
        assertEquals(0, count.get())
    }

    @Test
    fun cancelDisconnectsInFlightRequest() {
        val latch = CountDownLatch(1)
        val url = startServer { exchange ->
            latch.countDown()
            Thread.sleep(2_000)
            respond(exchange, 200, SAMPLE_JSON)
        }
        val fetcher = fetcher(listOf(url), maxPasses = 1)
        val result = AtomicReference<OnlineFetchResult>()
        val start = System.nanoTime()

        val thread = Thread { result.set(fetcher.fetch(bounds())) }
        thread.start()
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        fetcher.cancel()
        thread.join(5_000)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(OnlineFetchResult.Cancelled, result.get())
        assertTrue("elapsed ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun totalBudgetIsRespected() {
        val url = startServer { exchange ->
            Thread.sleep(10_000)
            respond(exchange, 200, SAMPLE_JSON)
        }

        val start = System.nanoTime()
        val result = fetcher(
            mirrors = listOf(url),
            clock = System::currentTimeMillis,
            totalBudgetMillis = 300,
            attemptTimeoutMillis = 10_000,
            maxPasses = 1,
        ).fetch(bounds())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(OnlineFetchResult.Failure(PreparationFailure.TIMEOUT), result)
        assertTrue("elapsed ${elapsedMs}ms", elapsedMs < 2_000)
    }
}
