package com.randomwalk.app.ui

import com.randomwalk.app.R
import com.randomwalk.app.data.PreparationFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class PreparationTextTest {

    @Test
    fun mapsEveryFailureToExpectedString() {
        assertEquals(R.string.preparation_no_network, PreparationText.messageRes(PreparationFailure.NO_NETWORK))
        assertEquals(R.string.preparation_timeout, PreparationText.messageRes(PreparationFailure.TIMEOUT))
        assertEquals(R.string.preparation_server_error, PreparationText.messageRes(PreparationFailure.SERVER_ERROR))
        assertEquals(R.string.preparation_insufficient_coverage, PreparationText.messageRes(PreparationFailure.INSUFFICIENT_COVERAGE))
        assertEquals(R.string.preparation_location_unavailable, PreparationText.messageRes(PreparationFailure.LOCATION_UNAVAILABLE))
    }
}
