package com.randomwalk.app.data.osm

import com.randomwalk.app.geo.LatLon

/** A single OSM way with the node order and full precision geometry returned by the API. */
data class OsmWay(
    val id: Long,
    val nodeIds: List<Long>,
    val geometry: List<LatLon>,
    val tags: Map<String, String>,
)

/** Ways that survived parsing; [skippedWays] counts elements dropped as malformed. */
data class OsmParseResult(
    val ways: List<OsmWay>,
    val skippedWays: Int,
)

/** Raised when the API response as a whole is not usable OSM JSON. */
class OsmParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Direction of travel along a way for a pedestrian. */
enum class EdgeDirection { FORWARD, BACKWARD }
