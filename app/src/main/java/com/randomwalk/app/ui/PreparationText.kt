package com.randomwalk.app.ui

import com.randomwalk.app.R
import com.randomwalk.app.data.PreparationFailure

/** Maps each preparation failure to its user-facing string resource. */
object PreparationText {
    fun messageRes(reason: PreparationFailure): Int = when (reason) {
        PreparationFailure.NO_NETWORK -> R.string.preparation_no_network
        PreparationFailure.TIMEOUT -> R.string.preparation_timeout
        PreparationFailure.SERVER_ERROR -> R.string.preparation_server_error
        PreparationFailure.INSUFFICIENT_COVERAGE -> R.string.preparation_insufficient_coverage
        PreparationFailure.LOCATION_UNAVAILABLE -> R.string.preparation_location_unavailable
    }
}
