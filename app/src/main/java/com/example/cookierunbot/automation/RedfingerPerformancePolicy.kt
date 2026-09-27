package com.example.cookierunbot.automation

internal object RedfingerPerformancePolicy {
    fun profilingEnabled(profile: PerformanceProfile): Boolean =
        profile == PerformanceProfile.REDFINGER_OPTIMIZED
}

internal data class FastStartAttemptSummary(
    val count: Long,
    val totalDurationNanos: Long,
    val minDurationNanos: Long,
    val maxDurationNanos: Long,
    val skippedGenerations: Long,
)

/** Mutable detector-thread-only counters; snapshots are immutable for asynchronous publication. */
internal class FastStartAttemptMetrics {
    private var count = 0L
    private var totalDurationNanos = 0L
    private var minDurationNanos = Long.MAX_VALUE
    private var maxDurationNanos = 0L
    private var skippedGenerations = 0L

    @Synchronized
    fun record(durationNanos: Long, skipped: Long) {
        count += 1L
        totalDurationNanos += durationNanos
        minDurationNanos = minOf(minDurationNanos, durationNanos)
        maxDurationNanos = maxOf(maxDurationNanos, durationNanos)
        skippedGenerations += skipped
    }

    @Synchronized
    fun drain(): FastStartAttemptSummary? {
        if (count == 0L) return null
        val summary = FastStartAttemptSummary(
            count, totalDurationNanos, minDurationNanos, maxDurationNanos, skippedGenerations,
        )
        reset()
        return summary
    }

    @Synchronized
    fun reset() {
        count = 0L
        totalDurationNanos = 0L
        minDurationNanos = Long.MAX_VALUE
        maxDurationNanos = 0L
        skippedGenerations = 0L
    }
}
