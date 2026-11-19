package com.randomwalk.app.walk

import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

interface LegGenerator {
    fun next(from: LatLon, excludedBearings: List<Float> = emptyList()): WalkLeg
}

class RandomLegGenerator(
    private val random: Random = Random.Default,
) : LegGenerator {
    override fun next(from: LatLon, excludedBearings: List<Float>): WalkLeg {
        val bearingDegrees = drawBearing(excludedBearings)
        val distanceMeters = random.nextInt(MIN_DISTANCE_METERS, MAX_DISTANCE_METERS + 1)
        val radians = Math.toRadians(bearingDegrees)
        val target = GeoMath.offsetMeters(
            from,
            eastMeters = sin(radians) * distanceMeters,
            northMeters = cos(radians) * distanceMeters,
        )
        val bearingFloat = bearingDegrees.toFloat()
        val reportedBearing = if (bearingFloat >= 360f) 0f else bearingFloat
        return WalkLeg(target, reportedBearing, distanceMeters)
    }

    private fun drawBearing(excludedBearings: List<Float>): Double {
        val working = excludedBearings.toMutableList()
        while (true) {
            val arcs = allowedArcs(working)
            val totalLength = arcs.sumOf { it.end - it.start }
            if (totalLength > MIN_ARC_LENGTH_DEGREES) {
                var offset = random.nextDouble() * totalLength
                for (arc in arcs) {
                    val length = arc.end - arc.start
                    if (offset < length) {
                        return GeoMath.normalizeBearingDegrees(arc.start + offset)
                    }
                    offset -= length
                }
                return GeoMath.normalizeBearingDegrees(arcs.last().end)
            }
            if (working.isEmpty()) return random.nextDouble() * 360.0
            working.removeAt(0)
        }
    }

    private fun allowedArcs(excludedBearings: List<Float>): List<Arc> {
        if (excludedBearings.isEmpty()) return listOf(Arc(0.0, 360.0))

        val blocked = mutableListOf<Arc>()
        for (excluded in excludedBearings) {
            val center = GeoMath.normalizeBearingDegrees(excluded.toDouble())
            val halfWidth = EXCLUSION_HALF_WIDTH_DEGREES.toDouble()
            val start = center - halfWidth
            val end = center + halfWidth
            when {
                start < 0.0 -> {
                    blocked += Arc(start + 360.0, 360.0)
                    blocked += Arc(0.0, end)
                }
                end >= 360.0 -> {
                    blocked += Arc(start, 360.0)
                    blocked += Arc(0.0, end - 360.0)
                }
                else -> blocked += Arc(start, end)
            }
        }
        blocked.sortBy { it.start }

        val merged = mutableListOf<Arc>()
        for (arc in blocked) {
            val last = merged.lastOrNull()
            if (last != null && arc.start <= last.end + ARC_EPSILON_DEGREES) {
                merged[merged.lastIndex] = Arc(last.start, maxOf(last.end, arc.end))
            } else {
                merged += arc
            }
        }

        val allowed = mutableListOf<Arc>()
        var cursor = 0.0
        for (arc in merged) {
            if (arc.start > cursor + ARC_EPSILON_DEGREES) {
                allowed += Arc(cursor, arc.start)
            }
            cursor = maxOf(cursor, arc.end)
        }
        if (cursor < 360.0 - ARC_EPSILON_DEGREES) {
            allowed += Arc(cursor, 360.0)
        }
        return allowed
    }

    private data class Arc(val start: Double, val end: Double)

    companion object {
        const val MIN_DISTANCE_METERS = 50
        const val MAX_DISTANCE_METERS = 100
        const val EXCLUSION_HALF_WIDTH_DEGREES = 45f

        private const val MIN_ARC_LENGTH_DEGREES = 1e-6
        private const val ARC_EPSILON_DEGREES = 1e-9
    }
}
