package com.randomwalk.app.compass

interface WalkCompass {
    fun start()
    fun stop()
    fun updateTargetBearing(degrees: Float)
}
