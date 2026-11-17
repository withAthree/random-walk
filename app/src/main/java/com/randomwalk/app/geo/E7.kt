package com.randomwalk.app.geo

/** Encodes a geographic coordinate as an integer of 1e-7 degree units. */
object E7 {
    private const val SCALE = 1e7

    fun encode(degrees: Double): Int = Math.round(degrees * SCALE).toInt()

    fun decode(value: Int): Double = value / SCALE
}
