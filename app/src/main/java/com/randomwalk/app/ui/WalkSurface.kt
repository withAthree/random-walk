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

    private val firstRunHint = FirstRunHint.from(context)

    private var hintOverlay: WalkHintOverlay? = null

    private val removeHint = Runnable {
        hintOverlay?.let { removeView(it) }
        hintOverlay = null
    }

    init {
        id = R.id.walk_surface
        addView(
            screenView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        showFirstRunHintIfNeeded()
    }

    fun start() {
        compass.start()
    }

    fun stop() {
        compass.stop()
    }

    override fun onDetachedFromWindow() {
        hintOverlay?.removeCallbacks(removeHint)
        super.onDetachedFromWindow()
    }

    private fun showFirstRunHintIfNeeded() {
        if (!firstRunHint.shouldShow()) return
        firstRunHint.markShown()

        val overlay = WalkHintOverlay(context)
        overlay.setOnLongClickListener {
            removeHint.run()
            true
        }
        hintOverlay = overlay
        addView(
            overlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        overlay.postDelayed(removeHint, 3000L)
    }
}
