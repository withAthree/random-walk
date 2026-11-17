package com.randomwalk.app.data

import com.randomwalk.app.data.model.StreetGraph

/** The result of preparing the street graph for a walk. */
sealed interface PreparationResult {
    data class Ready(val graph: StreetGraph) : PreparationResult

    data class Failed(val reason: PreparationFailure) : PreparationResult

    data object Cancelled : PreparationResult
}
