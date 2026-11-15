package com.randomwalk.app.location

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationSamplingConfigTest {

    @Test
    fun productionRequestMatchesSpec() {
        assertEquals(5_000L, LocationSamplingConfig.PRODUCTION.intervalMillis)
        assertEquals(5f, LocationSamplingConfig.PRODUCTION.minDistanceMeters)
    }

    @Test
    fun debugRequestUpdatesAboutEveryMeter() {
        assertEquals(1_000L, LocationSamplingConfig.DEBUG.intervalMillis)
        assertEquals(1f, LocationSamplingConfig.DEBUG.minDistanceMeters)
    }
}
