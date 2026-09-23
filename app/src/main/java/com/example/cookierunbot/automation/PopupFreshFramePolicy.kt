package com.example.cookierunbot.automation

internal object PopupFreshFramePolicy {
    fun blocks(frameGeneration: Long, postCallbackBaseline: Long): Boolean =
        postCallbackBaseline >= 0L && frameGeneration <= postCallbackBaseline

    fun baselineAfterAcceptedFrame(frameGeneration: Long, currentBaseline: Long): Long =
        if (currentBaseline >= 0L && frameGeneration > currentBaseline) -1L else currentBaseline

    /** Caller holds the controller lock while reading the current baseline and mutating state. */
    fun applySemanticMutationIfFresh(
        frameGeneration: Long,
        currentBaseline: Long,
        currentRunActive: Boolean,
        mutation: () -> Unit,
    ): Boolean {
        if (!currentRunActive || blocks(frameGeneration, currentBaseline)) return false
        mutation()
        return true
    }

    /** Detection-originated actions are checked again at the dispatch boundary. */
    fun allowsActionDispatch(
        currentRunActive: Boolean,
        detectionFrameGeneration: Long?,
        currentBaseline: Long,
    ): Boolean = currentRunActive &&
        (detectionFrameGeneration == null || !blocks(detectionFrameGeneration, currentBaseline))
}
