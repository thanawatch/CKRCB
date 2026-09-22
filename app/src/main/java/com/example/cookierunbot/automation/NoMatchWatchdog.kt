package com.example.cookierunbot.automation

data class NoMatchCaptureDue(
    val group: DetectionGroup,
    val durationMs: Long,
    val captureIndex: Int,
)

data class NoMatchIncidentSummary(
    val group: DetectionGroup,
    val durationMs: Long,
    val captures: Int,
)

/** Monotonic, thread-safe scheduling state only; performs no I/O or bot actions. */
class NoMatchWatchdog(
    private val firstCaptureDelayMs: Long = 30_000L,
    private val repeatCaptureIntervalMs: Long = 60_000L,
    private val maximumCaptures: Int = 10,
) {
    private var group: DetectionGroup? = null
    private var startedAt = 0L
    private var nextCaptureAt = 0L
    private var captures = 0
    private var limitReported = false

    @Synchronized
    fun observeNoMatch(activeGroup: DetectionGroup, nowMs: Long): NoMatchCaptureDue? {
        if (group != activeGroup || startedAt == 0L) {
            group = activeGroup
            startedAt = nowMs
            nextCaptureAt = nowMs + firstCaptureDelayMs
            captures = 0
            limitReported = false
            return null
        }
        if (captures >= maximumCaptures || nowMs < nextCaptureAt) return null
        captures += 1
        // Schedule from the observation that actually triggered this capture.
        // This deliberately drops missed deadlines after a detector pause/stall.
        nextCaptureAt = nowMs + repeatCaptureIntervalMs
        return NoMatchCaptureDue(activeGroup, (nowMs - startedAt).coerceAtLeast(0L), captures)
    }

    @Synchronized
    fun reset(nowMs: Long): NoMatchIncidentSummary? {
        val activeGroup = group ?: return null
        val summary = NoMatchIncidentSummary(
            activeGroup,
            (nowMs - startedAt).coerceAtLeast(0L),
            captures,
        )
        group = null
        startedAt = 0L
        nextCaptureAt = 0L
        captures = 0
        limitReported = false
        return summary
    }

    @Synchronized
    fun markLimitReported(): Boolean {
        if (captures < maximumCaptures || limitReported) return false
        limitReported = true
        return true
    }

    @Synchronized fun captureCount(): Int = captures
}
