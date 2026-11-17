package com.randomwalk.app.data.osm

/** Decides which OSM ways a pedestrian may walk and in which directions. */
object WalkableWayFilter {

    private val EXCLUDED_HIGHWAYS = setOf(
        "motorway",
        "motorway_link",
        "trunk",
        "trunk_link",
        "construction",
        "proposed",
        "raceway",
        "bus_guideway",
        "escape",
    )

    private val RESTRICTED_ACCESS = setOf("private", "no")

    fun isWalkable(tags: Map<String, String>): Boolean {
        val highway = tags["highway"] ?: return false
        return highway !in EXCLUDED_HIGHWAYS &&
            tags["access"] !in RESTRICTED_ACCESS &&
            tags["foot"] !in RESTRICTED_ACCESS &&
            "railway" !in tags &&
            tags["area"] != "yes"
    }

    fun directions(tags: Map<String, String>): Set<EdgeDirection> {
        val onewayFoot = tags["oneway:foot"]
        var forward = onewayFoot != "-1"
        var backward = onewayFoot !in setOf("yes", "true", "1")
        if (tags["foot:forward"] == "no") forward = false
        if (tags["foot:backward"] == "no") backward = false
        return buildSet {
            if (forward) add(EdgeDirection.FORWARD)
            if (backward) add(EdgeDirection.BACKWARD)
        }
    }
}
