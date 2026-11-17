package com.randomwalk.app.data.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal POST transport over [HttpURLConnection].
 *
 * The caller owns the form encoding (the `data=` prefix and URL-encoding) and
 * passes an already-encoded body; this class writes it verbatim exactly once.
 */
class HttpUrlConnectionTransport : TileFetchTransport {

    @Volatile
    private var current: HttpURLConnection? = null

    override fun post(
        url: String,
        body: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "RandomWalk/0.1 (Android; OSM data preparation)")
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis

        current = connection
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            return HttpResponse(code, readBody(connection, code))
        } finally {
            current = null
            connection.disconnect()
        }
    }

    override fun cancel() {
        current?.disconnect()
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.use(::readCapped) ?: ""
    }

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_RESPONSE_BYTES) throw IOException("response too large")
            out.write(buffer, 0, read)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 8 * 1024
        const val MAX_RESPONSE_BYTES = 24 * 1024 * 1024
    }
}
