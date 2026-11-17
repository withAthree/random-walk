package com.randomwalk.app.data.osm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkableWayFilterTest {

    private val excluded = listOf(
        mapOf("highway" to "motorway"),
        mapOf("highway" to "motorway_link"),
        mapOf("highway" to "trunk"),
        mapOf("highway" to "trunk_link"),
        mapOf("highway" to "construction"),
        mapOf("highway" to "raceway"),
        mapOf("highway" to "bus_guideway"),
        mapOf("highway" to "footway", "access" to "private"),
        mapOf("highway" to "footway", "access" to "no"),
        mapOf("highway" to "footway", "foot" to "no"),
        mapOf("highway" to "footway", "railway" to "rail"),
        mapOf("highway" to "pedestrian", "area" to "yes"),
        mapOf("building" to "yes"),
    )

    private val kept = listOf(
        mapOf("highway" to "footway"),
        mapOf("highway" to "path"),
        mapOf("highway" to "pedestrian"),
        mapOf("highway" to "living_street"),
        mapOf("highway" to "residential"),
        mapOf("highway" to "service"),
        mapOf("highway" to "unclassified"),
        mapOf("highway" to "tertiary"),
        mapOf("highway" to "secondary"),
        mapOf("highway" to "primary"),
        mapOf("highway" to "cycleway"),
        mapOf("highway" to "steps"),
        mapOf("highway" to "track"),
        mapOf("highway" to "bridleway"),
        mapOf("highway" to "corridor"),
        mapOf("highway" to "platform"),
        mapOf("highway" to "footway", "access" to "destination"),
    )

    private val footway = mapOf("highway" to "footway")

    @Test
    fun rejectsExcludedTags() {
        for (tags in excluded) {
            assertFalse("expected $tags to be rejected", WalkableWayFilter.isWalkable(tags))
        }
    }

    @Test
    fun acceptsWalkableTags() {
        for (tags in kept) {
            assertTrue("expected $tags to be walkable", WalkableWayFilter.isWalkable(tags))
        }
    }

    @Test
    fun defaultsToBothDirections() {
        assertEquals(BOTH, WalkableWayFilter.directions(footway))
    }

    @Test
    fun onewayFootYesTrueOrOneIsForwardOnly() {
        for (value in listOf("yes", "true", "1")) {
            assertEquals(
                "oneway:foot=$value",
                setOf(EdgeDirection.FORWARD),
                WalkableWayFilter.directions(footway + ("oneway:foot" to value)),
            )
        }
    }

    @Test
    fun onewayFootMinusOneIsBackwardOnly() {
        assertEquals(
            setOf(EdgeDirection.BACKWARD),
            WalkableWayFilter.directions(footway + ("oneway:foot" to "-1")),
        )
    }

    @Test
    fun onewayFootNoAllowsBothDirections() {
        assertEquals(BOTH, WalkableWayFilter.directions(footway + ("oneway:foot" to "no")))
    }

    @Test
    fun footForwardNoRemovesForward() {
        assertEquals(
            setOf(EdgeDirection.BACKWARD),
            WalkableWayFilter.directions(footway + ("foot:forward" to "no")),
        )
    }

    @Test
    fun footBackwardNoRemovesBackward() {
        assertEquals(
            setOf(EdgeDirection.FORWARD),
            WalkableWayFilter.directions(footway + ("foot:backward" to "no")),
        )
    }

    @Test
    fun vehicleOnewayDoesNotRestrictPedestrians() {
        assertEquals(BOTH, WalkableWayFilter.directions(footway + ("oneway" to "yes")))
    }

    private companion object {
        val BOTH = setOf(EdgeDirection.FORWARD, EdgeDirection.BACKWARD)
    }
}
