package com.randomwalk.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.randomwalk.app.ui.WalkScreenView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkScreenViewTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun waitingStateShowsTheLocalizedLabelAndNoDistance() {
        val view = WalkScreenView(context)

        assertTrue(view.waitingForLocation)
        assertEquals(context.getString(R.string.walk_waiting), view.distanceText)
        assertFalse(view.distanceText.any(Char::isDigit))
    }

    @Test
    fun legStateShowsRemainingMetersAndArrowAngle() {
        val view = WalkScreenView(context)
        view.waitingForLocation = false
        view.distanceMeters = 87
        view.arrowAngleDegrees = -90f

        assertEquals("87 m", view.distanceText)
        assertEquals(-90f, view.arrowAngleDegrees, 0.01f)
    }

    @Test
    fun viewHasNoTotalDistanceOrMapSurface() {
        val declaredNames = WalkScreenView::class.java.declaredMethods.map { it.name.lowercase() } +
            WalkScreenView::class.java.declaredFields.map { it.name.lowercase() }

        assertFalse(declaredNames.any { it.contains("total") })
        assertFalse(declaredNames.any { it.contains("map") })
    }
}
