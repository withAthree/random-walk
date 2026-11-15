package com.randomwalk.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunHintTest {

    @Test
    fun showsUntilMarkedShown() {
        var shown = false
        val hint = FirstRunHint(
            readShown = { shown },
            writeShown = { shown = true },
        )

        assertTrue(hint.shouldShow())
        hint.markShown()
        assertFalse(hint.shouldShow())
    }
}
