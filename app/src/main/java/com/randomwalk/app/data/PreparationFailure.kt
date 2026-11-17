package com.randomwalk.app.data

/** Why the street graph could not be prepared for a walk. */
enum class PreparationFailure {
    NO_NETWORK,
    TIMEOUT,
    SERVER_ERROR,
    INSUFFICIENT_COVERAGE,
    LOCATION_UNAVAILABLE,
}
