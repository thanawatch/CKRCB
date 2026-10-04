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
    val continuityReset: Boolean,
    val groupChangedReset: Boolean,
    val disappearedReset: Boolean,
    val elapsedMs: Long,
    val shouldTap: Boolean,
)

internal class StuckSendLifeConfirmTracker {
    private var firstSeenAtMs = -1L
    private var lastSeenAtMs = -1L
    private var episodeGroup: DetectionGroup? = null

    fun reset(): Boolean {
        val wasTracking = firstSeenAtMs >= 0L || lastSeenAtMs >= 0L
        firstSeenAtMs = -1L
        lastSeenAtMs = -1L
        episodeGroup = null
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
                continuityReset = false,
                groupChangedReset = false,
                disappearedReset = reset,
                elapsedMs = 0L,
                shouldTap = false,
            )
        }

        val groupChanged = wasTracking && episodeGroup != group
        val startedNewEpisode = !wasTracking || groupChanged
        if (startedNewEpisode) firstSeenAtMs = nowMs
        episodeGroup = group
        lastSeenAtMs = nowMs
        val elapsedMs = (nowMs - firstSeenAtMs).coerceAtLeast(0L)

        return StuckSendLifeConfirmObservation(
            startedNewEpisode = startedNewEpisode,
            continuityReset = groupChanged,
            groupChangedReset = groupChanged,
            disappearedReset = false,
            elapsedMs = elapsedMs,
            shouldTap = elapsedMs >= StuckSendLifeConfirmPolicy.STUCK_DURATION_MS,
        )
    }
}
