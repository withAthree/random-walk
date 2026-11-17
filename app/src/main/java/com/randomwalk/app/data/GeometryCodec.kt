package com.randomwalk.app.data

import com.randomwalk.app.geo.E7
import com.randomwalk.app.geo.LatLon
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Big-endian codec for a [LatLon] polyline: count, then lat/lon pairs in E7. */
object GeometryCodec {
    private const val BYTES_PER_POINT = 8
    private const val HEADER_BYTES = 4

    fun encode(points: List<LatLon>): ByteArray {
        require(points.isNotEmpty()) { "cannot encode an empty geometry" }
        val buffer = ByteBuffer.allocate(HEADER_BYTES + BYTES_PER_POINT * points.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(points.size)
        for (point in points) {
            buffer.putInt(E7.encode(point.latitude))
            buffer.putInt(E7.encode(point.longitude))
        }
        return buffer.array()
    }

    fun decode(blob: ByteArray): List<LatLon> {
        require(blob.size >= HEADER_BYTES) { "geometry blob is truncated" }
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.int
        require(count > 0) { "invalid point count: $count" }
        require(blob.size == HEADER_BYTES + BYTES_PER_POINT * count) {
            "geometry blob size ${blob.size} does not match $count points"
        }
        val points = ArrayList<LatLon>(count)
        repeat(count) {
            points.add(LatLon(E7.decode(buffer.int), E7.decode(buffer.int)))
        }
        return points
    }
}
