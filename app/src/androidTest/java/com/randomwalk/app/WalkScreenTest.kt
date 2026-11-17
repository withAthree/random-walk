package com.randomwalk.app

import android.Manifest
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.randomwalk.app.ui.WalkScreenView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkScreenTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @Test
    fun precisePermissionShowsFakeWalkScreen() {
        val intent = Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MainActivity::class.java,
        ).putExtra(MainActivity.EXTRA_SKIP_DATA_PREPARATION, true)
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            scenario.onActivity { activity ->
                val screen = activity.findViewById<WalkScreenView>(R.id.walk_screen)
                assertNotNull("walk screen not shown", screen)
                assertEquals(1000, screen.distanceMeters)
                screen.arrowAngleDegrees = -90f
                assertEquals(-90f, screen.arrowAngleDegrees, 0.01f)
            }
        } finally {
            scenario.close()
        }
    }
}
