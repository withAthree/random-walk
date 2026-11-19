package com.randomwalk.app

import android.Manifest
import android.content.Context
import android.os.Build
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.randomwalk.app.walk.WalkLocationSource
import com.randomwalk.app.walk.WalkService
import com.randomwalk.app.walk.WalkServiceDependencies
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkHintTest {

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
    fun prepare() {
        context.getSharedPreferences("walk", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
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
    fun hintShowsOnceAndLongPressDismissesIt() {
        val first = ActivityScenario.launch<MainActivity>(MainActivity::class.java)
        try {
            first.onActivity { activity ->
                val hint = activity.findViewById<View>(R.id.walk_hint)
                assertNotNull("first-run hint missing", hint)
                hint.performLongClick()
                assertNull(activity.findViewById<View>(R.id.walk_hint))
            }
        } finally {
            first.close()
        }

        val second = ActivityScenario.launch<MainActivity>(MainActivity::class.java)
        try {
            second.onActivity { activity ->
                assertNull("hint must not reappear", activity.findViewById<View>(R.id.walk_hint))
            }
        } finally {
            second.close()
        }
    }
}
