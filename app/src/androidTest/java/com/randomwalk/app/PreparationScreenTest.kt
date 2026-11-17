package com.randomwalk.app

import android.Manifest
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.randomwalk.app.data.PreparationFailure
import com.randomwalk.app.preparation.PreparationController
import com.randomwalk.app.preparation.PreparationUiState
import com.randomwalk.app.ui.PreparationScreen
import com.randomwalk.app.ui.WalkSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreparationScreenTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @Test
    fun showsProgressAndCancel() {
        val controller = FakePreparationController()
        launch(controller) { screen, _ ->
            controller.emit(PreparationUiState.Preparing(3, 9))

            val progress = screen.findViewById<ProgressBar>(R.id.preparation_progress)
            assertEquals(9, progress.max)
            assertEquals(3, progress.progress)
            assertFalse(progress.isIndeterminate)

            val primary = screen.findViewById<Button>(R.id.preparation_primary_action)
            assertEquals(
                screen.context.getString(R.string.preparation_cancel),
                primary.text.toString(),
            )

            primary.performClick()
            assertEquals(1, controller.cancelCalls)
        }
    }

    @Test
    fun failureShowsMessageAndRetry() {
        val controller = FakePreparationController()
        launch(controller) { screen, _ ->
            controller.emit(PreparationUiState.Failed(PreparationFailure.INSUFFICIENT_COVERAGE))

            val status = screen.findViewById<TextView>(R.id.preparation_status)
            assertEquals(
                screen.context.getString(R.string.preparation_insufficient_coverage),
                status.text.toString(),
            )

            val primary = screen.findViewById<Button>(R.id.preparation_primary_action)
            assertEquals(
                screen.context.getString(R.string.preparation_retry),
                primary.text.toString(),
            )

            val secondary = screen.findViewById<Button>(R.id.preparation_secondary_action)
            assertEquals(View.VISIBLE, secondary.visibility)

            primary.performClick()
            assertEquals(1, controller.retryCalls)
        }
    }

    @Test
    fun readyInvokesCallbackOnce() {
        val controller = FakePreparationController()
        launch(controller) { screen, callbacks ->
            controller.emit(PreparationUiState.Ready)
            controller.emit(PreparationUiState.Ready)
            assertEquals(1, callbacks.ready)
        }
    }

    @Test
    fun cancelledInvokesCallbackOnce() {
        val controller = FakePreparationController()
        launch(controller) { screen, callbacks ->
            controller.emit(PreparationUiState.Cancelled)
            controller.emit(PreparationUiState.Cancelled)
            assertEquals(1, callbacks.cancelled)
        }
    }

    @Test
    fun indeterminateWhenTotalIsZero() {
        val controller = FakePreparationController()
        launch(controller) { screen, _ ->
            controller.emit(PreparationUiState.Preparing(0, 0))

            val progress = screen.findViewById<ProgressBar>(R.id.preparation_progress)
            assertTrue(progress.isIndeterminate)
        }
    }

    private fun launch(
        controller: FakePreparationController,
        block: (PreparationScreen, Callbacks) -> Unit,
    ) {
        val callbacks = Callbacks()
        val intent = Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MainActivity::class.java,
        ).putExtra(MainActivity.EXTRA_SKIP_DATA_PREPARATION, true)
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            scenario.onActivity { activity ->
                activity.findViewById<WalkSurface>(R.id.walk_surface)?.stop()
                val screen = PreparationScreen(
                    activity,
                    controller,
                    onReady = { callbacks.ready++ },
                    onCancelled = { callbacks.cancelled++ },
                )
                activity.setContentView(screen)
                screen.start()
                block(screen, callbacks)
            }
        } finally {
            scenario.close()
        }
    }

    private class Callbacks {
        var ready = 0
        var cancelled = 0
    }

    private class FakePreparationController : PreparationController {
        var listener: ((PreparationUiState) -> Unit)? = null
        var cancelCalls = 0
        var retryCalls = 0

        override fun start(listener: (PreparationUiState) -> Unit) {
            this.listener = listener
        }

        override fun cancel() {
            cancelCalls++
        }

        override fun retry() {
            retryCalls++
        }

        override fun close() = Unit

        fun emit(state: PreparationUiState) {
            listener?.invoke(state)
        }
    }
}
