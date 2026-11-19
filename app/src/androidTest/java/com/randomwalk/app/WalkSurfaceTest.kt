package com.randomwalk.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.randomwalk.app.compass.WalkCompass
import com.randomwalk.app.geo.GeoMath
import com.randomwalk.app.geo.LatLon
import com.randomwalk.app.ui.FirstRunHint
import com.randomwalk.app.ui.WalkController
import com.randomwalk.app.ui.WalkScreenView
import com.randomwalk.app.ui.WalkSurface
import com.randomwalk.app.walk.WalkLeg
import com.randomwalk.app.walk.WalkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkSurfaceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class FakeCompass : WalkCompass {
        var starts = 0
        var stops = 0
        val bearings = mutableListOf<Float>()

        override fun start() { starts++ }
        override fun stop() { stops++ }
        override fun updateTargetBearing(degrees: Float) { bearings += degrees }
    }

    private class FakeController : WalkController {
        var state = WalkState()
        val listeners = mutableListOf<(WalkState?) -> Unit>()
        var rerolls = 0
        var stops = 0

        override fun currentState(): WalkState = state

        override fun addListener(listener: (WalkState?) -> Unit) {
            listeners += listener
            listener(state)
        }

        override fun removeListener(listener: (WalkState?) -> Unit) {
            listeners -= listener
        }

        override fun reroll() { rerolls++ }

        override fun stopWalk() { stops++ }

        fun emit(next: WalkState?) {
            state = next ?: WalkState()
            listeners.forEach { it(next) }
        }
    }

    @Before
    fun markHintShown() {
        FirstRunHint.from(context).markShown()
    }

    @Test
    fun waitingStateShowsTheWaitingLabelAndNoTarget() {
        val compass = FakeCompass()
        val surface = WalkSurface(context) { _, _ -> compass }
        val controller = FakeController()

        surface.bind(controller)

        val screen = surface.findViewById<WalkScreenView>(R.id.walk_screen)
        assertTrue(screen.waitingForLocation)
        assertEquals(context.getString(R.string.walk_waiting), screen.distanceText)
        assertTrue(compass.bearings.isEmpty())
    }

    @Test
    fun legStateRendersDistanceAndUpdatesTheCompass() {
        val compass = FakeCompass()
        val surface = WalkSurface(context) { _, _ -> compass }
        val controller = FakeController()
        surface.bind(controller)

        controller.emit(
            WalkState(
                leg = WalkLeg(
                    target = GeoMath.offsetMeters(LatLon(48.8566, 2.3522), 0.0, 80.0),
                    bearingDegrees = 0f,
                    distanceMeters = 80,
                ),
                remainingMeters = 80,
                targetBearingDegrees = 90f,
            ),
        )

        val screen = surface.findViewById<WalkScreenView>(R.id.walk_screen)
        assertFalse(screen.waitingForLocation)
        assertEquals("80 m", screen.distanceText)
        assertEquals(listOf(90f), compass.bearings)
    }

    @Test
    fun longPressRerollsAndStartStopControlsOnlyTheCompass() {
        val compass = FakeCompass()
        val surface = WalkSurface(context) { _, _ -> compass }
        val controller = FakeController()
        surface.bind(controller)

        surface.start()
        surface.performLongClick()
        surface.stop()

        assertEquals(1, compass.starts)
        assertEquals(1, compass.stops)
        assertEquals(1, controller.rerolls)
    }

    @Test
    fun rerollBeforeFixIsANoOp() {
        val compass = FakeCompass()
        val surface = WalkSurface(context) { _, _ -> compass }
        val controller = FakeController()
        surface.bind(controller)

        surface.performLongClick()

        assertEquals(1, controller.rerolls)
        assertEquals(context.getString(R.string.walk_waiting), surface.distanceTextForTest())
    }

    private fun WalkSurface.distanceTextForTest(): String =
        findViewById<WalkScreenView>(R.id.walk_screen).distanceText
}
