package com.randomwalk.app.data.osm

import com.randomwalk.app.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmParserTest {

    private val SAMPLE_JSON = """
{"elements":[
 {"type":"way","id":1,"nodes":[1,2],"geometry":[{"lat":0.0,"lon":0.0},{"lat":0.0,"lon":0.001}],"tags":{"highway":"footway"}},
 {"type":"way","id":2,"nodes":[2,3],"geometry":[{"lat":0.0,"lon":0.001},{"lat":0.0,"lon":0.002}],"tags":{"highway":"residential"}},
 {"type":"way","id":3,"nodes":[3,4],"geometry":[{"lat":0.0,"lon":0.002},{"lat":0.0,"lon":0.003}],"tags":{"highway":"motorway"}},
 {"type":"way","id":4,"nodes":[4,5],"geometry":[{"lat":0.0,"lon":0.003},{"lat":0.0,"lon":0.004}],"tags":{"highway":"footway","access":"private"}},
 {"type":"way","id":5,"nodes":[5,6],"geometry":[{"lat":0.0,"lon":0.004},{"lat":0.0,"lon":0.005}],"tags":{"highway":"pedestrian"}},
 {"type":"way","id":6,"nodes":[6,7],"geometry":[{"lat":0.0,"lon":0.005},{"lat":0.0,"lon":0.006}],"tags":{"highway":"living_street"}},
 {"type":"way","id":7,"nodes":[7,8],"geometry":[{"lat":0.0,"lon":0.006},{"lat":0.0,"lon":0.007}],"tags":{"railway":"rail"}},
 {"type":"way","id":8,"nodes":[8,9],"geometry":[{"lat":0.0,"lon":0.007},{"lat":0.0,"lon":0.008}],"tags":{"highway":"residential","foot":"no"}},
 {"type":"way","id":9,"nodes":[9,10],"geometry":[{"lat":0.0,"lon":0.008},{"lat":0.0,"lon":0.009}],"tags":{"highway":"steps"}},
 {"type":"way","id":10,"nodes":[10,11],"tags":{"highway":"footway"}},
 {"type":"way","id":11,"nodes":[11,12],"geometry":[{"lat":0.0,"lon":0.010}],"tags":{"highway":"footway"}}
]}
""".trimIndent()

    @Test
    fun parsesEveryValidWayAndCountsMalformedOnes() {
        val result = OsmParser.parse(SAMPLE_JSON)

        assertEquals((1L..9L).toSet(), result.ways.map { it.id }.toSet())
        assertEquals(2, result.skippedWays)
    }

    @Test
    fun parsesIdsNodeIdsGeometryAndTags() {
        val way = OsmParser.parse(SAMPLE_JSON).ways.first { it.id == 1L }

        assertEquals(listOf(1L, 2L), way.nodeIds)
        assertEquals(listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001)), way.geometry)
        assertEquals(mapOf("highway" to "footway"), way.tags)
    }

    @Test
    fun skipsWayWithMissingGeometryWithoutThrowing() {
        val result = OsmParser.parse(SAMPLE_JSON)

        assertTrue(10L !in result.ways.map { it.id })
    }

    @Test
    fun skipsWayWhoseGeometrySizeDiffersFromNodeCount() {
        val result = OsmParser.parse(SAMPLE_JSON)

        assertTrue(11L !in result.ways.map { it.id })
    }

    @Test
    fun skipsWayWithNoNodes() {
        val json = """{"elements":[{"type":"way","id":7,"nodes":[],"tags":{"highway":"footway"}}]}"""

        val result = OsmParser.parse(json)

        assertTrue(result.ways.isEmpty())
        assertEquals(1, result.skippedWays)
    }

    @Test
    fun ignoresNonWayElements() {
        val json = """
{"elements":[
 {"type":"node","id":42,"lat":0.0,"lon":0.0},
 {"type":"relation","id":43,"tags":{"type":"route"}},
 {"type":"way","id":1,"nodes":[1,2],"geometry":[{"lat":0.0,"lon":0.0},{"lat":0.0,"lon":0.001}],"tags":{"highway":"footway"}}
]}
""".trimIndent()

        val result = OsmParser.parse(json)

        assertEquals(listOf(1L), result.ways.map { it.id })
        assertEquals(0, result.skippedWays)
    }

    @Test
    fun filterKeepsOnlyWalkableWaysFromParseResult() {
        val walkable = OsmParser.parse(SAMPLE_JSON).ways
            .filter { WalkableWayFilter.isWalkable(it.tags) }
            .map { it.id }
            .toSet()

        assertEquals(setOf(1L, 2L, 5L, 6L, 9L), walkable)
    }

    @Test
    fun skipsWayWithMalformedIdWithoutThrowing() {
        val json = """{"elements":[{"type":"way","id":"abc","nodes":[1,2],"geometry":[{"lat":0.0,"lon":0.0},{"lat":0.0,"lon":0.001}],"tags":{"highway":"footway"}}]}"""

        val result = OsmParser.parse(json)

        assertTrue(result.ways.isEmpty())
        assertEquals(1, result.skippedWays)
    }

    @Test
    fun skipsWayWithOutOfRangeCoordinateWithoutThrowing() {
        val json = """{"elements":[{"type":"way","id":1,"nodes":[1,2],"geometry":[{"lat":100.0,"lon":0.0},{"lat":0.0,"lon":0.001}],"tags":{"highway":"footway"}}]}"""

        val result = OsmParser.parse(json)

        assertTrue(result.ways.isEmpty())
        assertEquals(1, result.skippedWays)
    }

    @Test
    fun throwsOsmParseExceptionForMalformedJson() {
        assertThrows(OsmParseException::class.java) { OsmParser.parse("{not json") }
    }

    @Test
    fun wrapsMissingElementsArrayAsOsmParseException() {
        assertThrows(OsmParseException::class.java) { OsmParser.parse("""{"foo":1}""") }
    }
}
