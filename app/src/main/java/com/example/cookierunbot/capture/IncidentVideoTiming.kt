package com.example.cookierunbot.capture

/** Monotonic times: the recording covers the entire Recovery plus a 10s tail, capped at 3min TOTAL, including the pre-roll. */
internal object IncidentVideoTiming {
    const val PRELUDE_NANOS = 30_000_000_000L
    const val TAIL_NANOS = 10_000_000_000L
    const val MAX_VIDEO_NANOS = 180_000_000_000L
    // Reserve 30 seconds for the footage before the trigger.
    const val POST_TRIGGER_BUDGET_NANOS = MAX_VIDEO_NANOS - PRELUDE_NANOS

    fun finalStopAt(triggerNanos: Long, recoveryFinishedNanos: Long): Long =
        minOf(safeAdd(triggerNanos, POST_TRIGGER_BUDGET_NANOS), safeAdd(recoveryFinishedNanos, TAIL_NANOS))

    private fun safeAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
