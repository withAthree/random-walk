package com.randomwalk.app.ui

import android.content.Context
import android.widget.FrameLayout
import com.randomwalk.app.R
import com.randomwalk.app.compass.CompassController
import com.randomwalk.app.walk.FakeWalk

class WalkSurface(context: Context) : FrameLayout(context) {

    private val screenView = WalkScreenView(context).apply {
        distanceMeters = FakeWalk.REMAINING_METERS
    }

    private val compass = CompassController(
        context = context,
        targetBearingDegrees = FakeWalk.TARGET_BEARING_DEGREES,
    ) { angle ->
        screenView.arrowAngleDegrees = angle
    }

    init {
        id = R.id.walk_surface
        addView(
            screenView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun start() {
        compass.start()
    }

    fun stop() {
        compass.stop()
    }
}
