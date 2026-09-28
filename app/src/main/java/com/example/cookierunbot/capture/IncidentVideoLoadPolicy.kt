package com.example.cookierunbot.capture

/**
 * Conservative optional-video budgets. The process CPU window measures RobotRun's own
 * CPU time, not the cloud host/Redfinger total CPU load. Capture freshness and wall-clock
 * sample latency remain independent signals; if uncertain, preserving gameplay wins.
 */
internal object IncidentVideoLoadPolicy {
    const val CPU_WINDOW_MS = 2_000L
    private const val MAX_SAMPLE_WALL_MS = 250L
    private const val MAX_SAMPLE_THREAD_CPU_NANOS = 80_000_000L

    /** More than 1.5 single-core equivalents sustained by this app during a 2s window. */
    fun processCpuTooBusy(processCpuDeltaMs: Long, wallDeltaMs: Long): Boolean =
        wallDeltaMs >= CPU_WINDOW_MS && processCpuDeltaMs >= 0L &&
            processCpuDeltaMs > wallDeltaMs * 3L / 2L

    fun sampleTooExpensive(wallMs: Long, threadCpuNanos: Long): Boolean =
        wallMs > MAX_SAMPLE_WALL_MS ||
            (threadCpuNanos >= 0L && threadCpuNanos > MAX_SAMPLE_THREAD_CPU_NANOS)
}
