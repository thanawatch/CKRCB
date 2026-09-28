package com.example.cookierunbot.automation

/**
 * Independent, full-frame, two-template popup. Never reinterpret an ordinary GAME_COMPLETE
 * as this popup and never change the existing GAME_COMPLETE or Quick Lives ROI/threshold.
 */
internal object DualGameCompleteSendLifePolicy {
    const val PROBE_INTERVAL_MS = 1_500L
    const val BEFORE_TAP_DELAY_MS = 1_600L
    const val CALLBACK_TIMEOUT_MS = 3_000L
    const val MAX_VISIBLE_ATTEMPTS = 3
    const val MATCH_THRESHOLD = 0.8

    fun matched(gameCompleteMatched: Boolean, confirmMatched: Boolean): Boolean =
        gameCompleteMatched && confirmMatched

    /** Auxiliary popup is eligible only where ordinary popup handlers run. */
    fun eligibleGroup(group: DetectionGroup): Boolean =
        group == DetectionGroup.PRE_GAME || group == DetectionGroup.POST_GAME

    /** Within eligible groups, check the special pair before a legacy GAME_COMPLETE match. */
    fun shouldProbe(
        group: DetectionGroup,
        nowMs: Long,
        previousProbeMs: Long,
        oldGameCompleteMatched: Boolean,
        fastStartPriority: Boolean,
        immediateGameplayActionMatched: Boolean,
    ): Boolean = eligibleGroup(group) && (oldGameCompleteMatched ||
        (!fastStartPriority && !immediateGameplayActionMatched &&
            (previousProbeMs <= 0L || nowMs - previousProbeMs >= PROBE_INTERVAL_MS)))

    fun center(x: Int, y: Int, width: Int, height: Int): LogicalPoint? =
        if (x < 0 || y < 0 || width <= 0 || height <= 0) null
        else LogicalPoint(x + width / 2, y + height / 2)
}
