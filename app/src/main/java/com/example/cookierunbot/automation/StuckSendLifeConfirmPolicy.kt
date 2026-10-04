package com.example.cookierunbot.automation

internal object StuckSendLifeConfirmPolicy {
    const val MATCH_THRESHOLD = 0.95
    const val STUCK_DURATION_MS = 60_000L
    const val CALLBACK_TIMEOUT_MS = 3_000L
    const val PROBE_INTERVAL_MS = 1_500L

    fun eligibleGroup(group: DetectionGroup): Boolean =
        group == DetectionGroup.PRE_GAME || group == DetectionGroup.POST_GAME

    fun highConfidence(score: Double): Boolean = score >= MATCH_THRESHOLD

    fun center(x: Int, y: Int, width: Int, height: Int): LogicalPoint? =
        if (x < 0 || y < 0 || width <= 0 || height <= 0) null
        else LogicalPoint(x + width / 2, y + height / 2)
}

internal data class StuckSendLifeConfirmObservation(
    val startedNewEpisode: Boolean,
    val disappearedReset: Boolean,
    val elapsedMs: Long,
    val shouldTap: Boolean,
)

internal class StuckSendLifeConfirmTracker {
    private var firstSeenAtMs = -1L
    private var lastSeenAtMs = -1L

    fun reset(): Boolean {
        val wasTracking = firstSeenAtMs >= 0L || lastSeenAtMs >= 0L
        firstSeenAtMs = -1L
        lastSeenAtMs = -1L
        return wasTracking
    }

    fun observe(
        group: DetectionGroup,
        nowMs: Long,
        score: Double,
    ): StuckSendLifeConfirmObservation {
        val wasTracking = firstSeenAtMs >= 0L && lastSeenAtMs >= 0L
        if (!StuckSendLifeConfirmPolicy.eligibleGroup(group) ||
            !StuckSendLifeConfirmPolicy.highConfidence(score)
        ) {
            val reset = reset()
            return StuckSendLifeConfirmObservation(
                startedNewEpisode = false,
                disappearedReset = reset,
                elapsedMs = 0L,
                shouldTap = false,
            )
        }

        val startedNewEpisode = !wasTracking
        if (startedNewEpisode) firstSeenAtMs = nowMs
        lastSeenAtMs = nowMs
        val elapsedMs = (nowMs - firstSeenAtMs).coerceAtLeast(0L)

        return StuckSendLifeConfirmObservation(
            startedNewEpisode = startedNewEpisode,
            disappearedReset = false,
            elapsedMs = elapsedMs,
            shouldTap = elapsedMs >= StuckSendLifeConfirmPolicy.STUCK_DURATION_MS,
        )
    }
}

internal object StuckSendLifeFollowUpPolicy {
    const val OLD_CONFIRM_DISAPPEAR_TIMEOUT_MS = 3_000L
    const val NEXT_CONFIRM_WAIT_MS = 2_500L

    enum class Phase {
        INACTIVE,
        WAIT_OLD_CONFIRM_DISAPPEAR,
        WAIT_NEXT_CONFIRM,
    }

    enum class Decision {
        WAIT,
        OLD_CONFIRM_DISAPPEARED,
        TAP_NEXT_CONFIRM,
        STOP_CHAIN,
    }

    fun decide(
        phase: Phase,
        elapsedMs: Long,
        confirmMatched: Boolean,
    ): Decision = when (phase) {
        Phase.INACTIVE -> Decision.STOP_CHAIN
        Phase.WAIT_OLD_CONFIRM_DISAPPEAR -> when {
            !confirmMatched -> Decision.OLD_CONFIRM_DISAPPEARED
            elapsedMs >= OLD_CONFIRM_DISAPPEAR_TIMEOUT_MS -> Decision.STOP_CHAIN
            else -> Decision.WAIT
        }
        Phase.WAIT_NEXT_CONFIRM -> when {
            confirmMatched -> Decision.TAP_NEXT_CONFIRM
            elapsedMs >= NEXT_CONFIRM_WAIT_MS -> Decision.STOP_CHAIN
            else -> Decision.WAIT
        }
    }
}

