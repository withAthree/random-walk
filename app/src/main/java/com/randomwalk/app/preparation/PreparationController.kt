package com.randomwalk.app.preparation

import com.randomwalk.app.data.PreparationFailure

sealed interface PreparationUiState {
    data class Preparing(val completed: Int, val total: Int) : PreparationUiState

    data object Ready : PreparationUiState

    data class Failed(val reason: PreparationFailure) : PreparationUiState

    data object Cancelled : PreparationUiState
}

/** Drives one preparation attempt and reports its progress as UI states. */
interface PreparationController {
    fun start(listener: (PreparationUiState) -> Unit)
    fun cancel()
    fun retry()
    fun close()
}
