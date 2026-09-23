package com.example.cookierunbot.automation

internal object PopupFreshFramePolicy {
    fun blocks(frameGeneration: Long, postCallbackBaseline: Long): Boolean =
        postCallbackBaseline >= 0L && frameGeneration <= postCallbackBaseline
}
