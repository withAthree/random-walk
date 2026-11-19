package com.randomwalk.app.ui

import com.randomwalk.app.walk.WalkState

interface WalkController {
    fun currentState(): WalkState
    fun addListener(listener: (WalkState?) -> Unit)
    fun removeListener(listener: (WalkState?) -> Unit)
    fun reroll()
    fun stopWalk()
}
