package com.example.cookierunbot.automation

internal object RedfingerPerformancePolicy {
    fun profilingEnabled(profile: PerformanceProfile): Boolean =
        profile == PerformanceProfile.REDFINGER_OPTIMIZED

    /** Restrict only optimized GAME_START matching to the native template size. */
    fun matchScales(
        profile: PerformanceProfile,
        stage: BotStage,
        configuredScales: List<Double>,
    ): List<Double> =
        if (profile == PerformanceProfile.REDFINGER_OPTIMIZED && stage == BotStage.GAME_START) {
            require(1.0 in configuredScales) { "GAME_START requires a 1.0 template scale" }
            listOf(1.0)
        } else {
            configuredScales
        }

    /** A handled GAME_START cannot trigger a second Fast Start in the same round. */
    fun inGameCandidates(
        profile: PerformanceProfile,
        gameStartHandled: Boolean,
        stages: List<BotStage>,
    ): List<BotStage> =
        if (profile == PerformanceProfile.REDFINGER_OPTIMIZED && gameStartHandled) {
            stages.filterNot { it == BotStage.GAME_START }
        } else {
            stages
        }

    /** Full no-match score reports rescan stages on the detector thread. Keep gameplay responsive. */
    fun deferDetailedNoMatchScoring(profile: PerformanceProfile, group: DetectionGroup): Boolean =
        profile == PerformanceProfile.REDFINGER_OPTIMIZED && group == DetectionGroup.IN_GAME
}

/** Serializes complete Start/Stop transitions, including process-global service side effects. */
internal class BotLifecycleTransition {
    private val lock = Any()

    fun <T> run(block: () -> T): T = synchronized(lock) { block() }
}

/** Generation ownership for process-global services shared across controller runs. */
internal class RunGenerationSession {
    private var activeGeneration: Long? = null

    @Synchronized
    fun activate(generation: Long) {
        activeGeneration = generation
    }

    @Synchronized
    fun deactivateIfCurrent(generation: Long): Boolean {
        if (activeGeneration != generation) return false
        activeGeneration = null
        return true
    }
}

/** Durable state for an uncalibrated popup which only a user can safely dismiss. */
internal class CalibrationBlockState {
    @Volatile
    var action: String? = null
        private set

    @Synchronized
    fun block(action: String): Boolean {
        if (this.action == action) return false
        this.action = action
        return true
    }

    @Synchronized
    fun clearIfDifferent(detectedStage: String): String? {
        val blocked = action ?: return null
        if (blocked == detectedStage) return null
        action = null
        return blocked
    }

    fun authoritativeState(requestedState: String): String =
        if (action != null) WAITING_FOR_REDFINGER_CALIBRATION else requestedState

    @Synchronized
    fun clear() {
        action = null
    }

    companion object {
        const val WAITING_FOR_REDFINGER_CALIBRATION = "WAITING_FOR_REDFINGER_CALIBRATION"
    }
}

internal fun redfingerCalibrationStatusText(action: String): String =
    "Waiting: Redfinger calibration ($action) — clear popup manually"

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
