package com.randomwalk.app

import android.Manifest
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.randomwalk.app.ui.WalkScreenView
import com.randomwalk.app.walk.WalkLocationSource
import com.randomwalk.app.walk.WalkService
import com.randomwalk.app.walk.WalkServiceDependencies
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkScreenTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val permissions: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) {
            GrantPermissionRule.grant(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            GrantPermissionRule.grant(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    @Before
    fun useNonEmittingLocationSource() {
        WalkServiceDependencies.locationSourceFactory = { _, _ ->
            object : WalkLocationSource {
                override fun start() = Unit
                override fun stop() = Unit
            }
        }
    }

    @After
    fun resetDependencies() {
        context.stopService(android.content.Intent(context, WalkService::class.java))
        WalkServiceDependencies.reset()
    }

    @Test
    fun precisePermissionShowsTheWaitingWalkScreen() {
        val scenario = ActivityScenario.launch<MainActivity>(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById(R.id.walk_surface))
                val screen = activity.findViewById<WalkScreenView>(R.id.walk_screen)
                assertNotNull("walk screen not shown", screen)
                assertEquals(
                    activity.getString(R.string.walk_waiting),
                    screen.distanceText,
                )
                assertNull(activity.findViewById(R.id.start_placeholder))
            }
        } finally {
            scenario.close()
        }
    }
}
