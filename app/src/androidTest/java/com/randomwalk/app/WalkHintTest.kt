package com.randomwalk.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkHintTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @Before
    fun clearHintPreference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("walk", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun hintShowsOnceAndLongPressDismissesIt() {
        val intent = Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MainActivity::class.java,
        ).putExtra(MainActivity.EXTRA_SKIP_DATA_PREPARATION, true)
        val first = ActivityScenario.launch<MainActivity>(intent)
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

        val second = ActivityScenario.launch<MainActivity>(intent)
        try {
            second.onActivity { activity ->
                assertNull("hint must not reappear", activity.findViewById<View>(R.id.walk_hint))
            }
        } finally {
            second.close()
        }
    }
}
