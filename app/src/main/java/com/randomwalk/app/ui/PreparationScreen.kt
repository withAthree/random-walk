package com.randomwalk.app.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.randomwalk.app.R
import com.randomwalk.app.data.PreparationFailure
import com.randomwalk.app.preparation.PreparationController
import com.randomwalk.app.preparation.PreparationUiState

/**
 * Full-screen preparation view: shows fetch progress and maps failures to a
 * retry/cancel action pair. Ready and Cancelled are reported through callbacks
 * exactly once each.
 */
class PreparationScreen(
    context: Context,
    private val controller: PreparationController,
    private val onReady: () -> Unit,
    private val onCancelled: () -> Unit,
) : LinearLayout(context) {

    private val status = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER
    }

    private val progress = ProgressBar(
        context,
        null,
        android.R.attr.progressBarStyleHorizontal,
    )

    private val primary = Button(context)

    private val secondary = Button(context).apply {
        text = context.getString(R.string.preparation_cancel)
        visibility = View.GONE
    }

    private var currentState: PreparationUiState = PreparationUiState.Preparing(0, 0)
    private var readyHandled = false
    private var cancelledHandled = false
    private var stopped = false

    init {
        id = R.id.preparation_screen
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)

        val density = context.resources.displayMetrics.density
        val padding = (24 * density).toInt()
        setPadding(padding, padding, padding, padding)

        status.id = R.id.preparation_status
        progress.id = R.id.preparation_progress
        primary.id = R.id.preparation_primary_action
        secondary.id = R.id.preparation_secondary_action

        addView(
            status,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        addView(
            progress,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (16 * density).toInt()
            },
        )
        addView(
            primary,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (16 * density).toInt()
            },
        )
        addView(
            secondary,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (16 * density).toInt()
            },
        )

        primary.setOnClickListener {
            if (currentState is PreparationUiState.Failed) {
                controller.retry()
            } else {
                controller.cancel()
            }
        }
        secondary.setOnClickListener { controller.cancel() }
    }

    fun start() {
        controller.start(::render)
    }

    fun stop() {
        stopped = true
        controller.cancel()
    }

    private fun render(state: PreparationUiState) {
        if (stopped) return
        currentState = state

        when (state) {
            is PreparationUiState.Preparing -> {
                status.setText(R.string.preparation_preparing)
                progress.visibility = View.VISIBLE
                if (state.total == 0) {
                    progress.isIndeterminate = true
                } else {
                    progress.isIndeterminate = false
                    progress.max = state.total
                    progress.progress = state.completed
                }
                primary.setText(R.string.preparation_cancel)
                secondary.visibility = View.GONE
            }

            is PreparationUiState.Failed -> {
                status.setText(PreparationText.messageRes(state.reason))
                progress.visibility = View.GONE
                primary.setText(R.string.preparation_retry)
                secondary.visibility = View.VISIBLE
            }

            is PreparationUiState.Ready -> {
                if (!readyHandled) {
                    readyHandled = true
                    onReady()
                }
            }

            is PreparationUiState.Cancelled -> {
                if (!cancelledHandled) {
                    cancelledHandled = true
                    onCancelled()
                }
            }
        }
    }
}
