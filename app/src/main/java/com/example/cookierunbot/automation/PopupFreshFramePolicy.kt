package com.example.cookierunbot.automation

internal object PopupFreshFramePolicy {
    fun blocks(frameGeneration: Long, postCallbackBaseline: Long): Boolean =
        postCallbackBaseline >= 0L && frameGeneration <= postCallbackBaseline

    fun baselineAfterAcceptedFrame(frameGeneration: Long, currentBaseline: Long): Long =
        if (currentBaseline >= 0L && frameGeneration > currentBaseline) -1L else currentBaseline
}
