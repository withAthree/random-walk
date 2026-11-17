package com.randomwalk.app.data.osm

import com.randomwalk.app.geo.LatLon
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Turns an OSM API response into [OsmWay]s; filtering happens later in [WalkableWayFilter]. */
object OsmParser {

    fun parse(json: String): OsmParseResult {
        val elements = try {
            JSONObject(json).getJSONArray("elements")
        } catch (e: JSONException) {
            throw OsmParseException("malformed OSM response", e)
        }

        val ways = ArrayList<OsmWay>(elements.length())
        var skippedWays = 0
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            if (element.optString("type") != "way") continue
            try {
                val way = parseWay(element)
                if (way == null) skippedWays++ else ways.add(way)
            } catch (e: JSONException) {
                skippedWays++
            } catch (e: IllegalArgumentException) {
                skippedWays++
            }
        }
        return OsmParseResult(ways, skippedWays)
    }

    /** Returns null when the way lacks usable geometry. */
    private fun parseWay(element: JSONObject): OsmWay? {
        val nodeIds = element.getJSONArray("nodes").toLongList()
        if (nodeIds.isEmpty()) return null

        val geometryJson = element.optJSONArray("geometry") ?: return null
        val geometry = geometryJson.toLatLonList()
        if (geometry.size != nodeIds.size || geometry.size < 2) return null

        return OsmWay(
            id = element.getLong("id"),
            nodeIds = nodeIds,
            geometry = geometry,
            tags = element.optJSONObject("tags").toStringMap(),
        )
    }

    private fun JSONArray.toLongList(): List<Long> = List(length()) { getLong(it) }

    private fun JSONArray.toLatLonList(): List<LatLon> = List(length()) { index ->
        val point = getJSONObject(index)
        LatLon(point.getDouble("lat"), point.getDouble("lon"))
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val tags = HashMap<String, String>(length())
        for (key in keys()) {
            tags[key] = getString(key)
        }
        return tags
    }
}
