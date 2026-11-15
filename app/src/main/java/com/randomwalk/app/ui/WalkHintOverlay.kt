package com.randomwalk.app.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.TextView
import com.randomwalk.app.R

class WalkHintOverlay(context: Context) : TextView(context) {
    init {
        id = R.id.walk_hint
        text = context.getString(R.string.walk_hint)
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER
        setBackgroundColor(Color.argb(0xCC, 0x00, 0x00, 0x00))
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        isClickable = true
        isLongClickable = true
    }
}
