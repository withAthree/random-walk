package com.randomwalk.app

import android.Manifest
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImmersiveModeTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @Test
    fun walkWindowKeepsScreenOn() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertTrue(
                    "FLAG_KEEP_SCREEN_ON missing from the walk window",
                    (flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0,
                )
            }
        } finally {
            scenario.close()
        }
    }
}
