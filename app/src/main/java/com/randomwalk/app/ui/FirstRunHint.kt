package com.randomwalk.app.ui

import android.content.Context

class FirstRunHint(
    private val readShown: () -> Boolean,
    private val writeShown: () -> Unit,
) {
    fun shouldShow(): Boolean = !readShown()

    fun markShown() {
        writeShown()
    }

    companion object {
        private const val PREFS_NAME = "walk"
        private const val KEY_HINT_SHOWN = "hint_shown"

        fun from(context: Context): FirstRunHint {
            val prefs = context.applicationContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE,
            )
            return FirstRunHint(
                readShown = { prefs.getBoolean(KEY_HINT_SHOWN, false) },
                writeShown = { prefs.edit().putBoolean(KEY_HINT_SHOWN, true).commit() },
            )
        }
    }
}
