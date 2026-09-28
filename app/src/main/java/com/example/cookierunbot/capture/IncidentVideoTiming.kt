package com.example.cookierunbot.capture

/** Event-triggered recording: Recovery + 10s, with a hard 3-minute cap FROM trigger (no pre-roll). */
internal object IncidentVideoTiming {
    const val TAIL_NANOS = 10_000_000_000L
    const val MAX_VIDEO_NANOS = 180_000_000_000L

    fun finalStopAt(triggerNanos: Long, recoveryFinishedNanos: Long): Long =
        minOf(safeAdd(triggerNanos, MAX_VIDEO_NANOS), safeAdd(recoveryFinishedNanos, TAIL_NANOS))

    private fun safeAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
