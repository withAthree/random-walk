package com.randomwalk.app.ui

import android.content.Context
import android.widget.FrameLayout
import com.randomwalk.app.R
import com.randomwalk.app.compass.CompassController
import com.randomwalk.app.compass.WalkCompass
import com.randomwalk.app.walk.WalkState

class WalkSurface(
    context: Context,
    compassFactory: (Context, (Float) -> Unit) -> WalkCompass = { ctx, onAngle ->
        CompassController(ctx, 0f, onAngle)
    },
) : FrameLayout(context) {

    private val screenView = WalkScreenView(context)
    private val compass = compassFactory(context) { angle ->
        screenView.arrowAngleDegrees = angle
    }
    private val firstRunHint = FirstRunHint.from(context)
    private var hintOverlay: WalkHintOverlay? = null
    private var controller: WalkController? = null
    var onWalkEnded: (() -> Unit)? = null

    private val removeHint = Runnable {
        hintOverlay?.let { removeView(it) }
        hintOverlay = null
    }

    private val stateListener: (WalkState?) -> Unit = { state ->
        if (state == null) {
            onWalkEnded?.invoke()
        } else {
            render(state)
        }
    }

    init {
        id = R.id.walk_surface
        addView(
            screenView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        isLongClickable = true
        setOnLongClickListener {
            controller?.reroll()
            true
        }
        showFirstRunHintIfNeeded()
    }

    fun bind(controller: WalkController) {
        if (this.controller === controller) return
        unbind()
        this.controller = controller
        controller.addListener(stateListener)
        render(controller.currentState())
    }

    fun unbind() {
        controller?.removeListener(stateListener)
        controller = null
    }

    fun start() {
        compass.start()
    }

    fun stop() {
        compass.stop()
    }

    fun release() {
        stop()
        unbind()
        hintOverlay?.removeCallbacks(removeHint)
        hintOverlay = null
    }

    private fun render(state: WalkState) {
        screenView.waitingForLocation = state.waitingForLocation
        if (state.waitingForLocation) return
        screenView.distanceMeters = state.remainingMeters ?: 0
        state.targetBearingDegrees?.let(compass::updateTargetBearing)
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
            controller?.reroll()
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
