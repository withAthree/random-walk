package com.randomwalk.app.data

import com.randomwalk.app.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeometryCodecTest {

    @Test
    fun roundTripsNegativeCoordinates() {
        val points = listOf(
            LatLon(-33.8688, 151.2093),
            LatLon(-33.8691, 151.2095),
        )

        val decoded = GeometryCodec.decode(GeometryCodec.encode(points))

        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (input, output) ->
            assertEquals(input.latitude, output.latitude, 1e-7)
            assertEquals(input.longitude, output.longitude, 1e-7)
        }
    }

    @Test
    fun roundTripsEmptyIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            GeometryCodec.encode(emptyList())
        }
    }

    @Test
    fun truncatedBlobIsRejected() {
        val encoded = GeometryCodec.encode(listOf(LatLon(1.0, 2.0), LatLon(3.0, 4.0)))
        val truncated = encoded.copyOf(encoded.size - 1)

        assertThrows(IllegalArgumentException::class.java) {
            GeometryCodec.decode(truncated)
        }
    }

    @Test
    fun zeroCountBlobIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            GeometryCodec.decode(ByteArray(4))
        }
    }
}
