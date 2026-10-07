package com.example.cookierunbot.automation

internal object AntiBotStallRecoveryPolicy {
    const val STALL_TIMEOUT_MS = 60_000L
    const val MAX_MATCH_GAP_MS = 2_000L
    const val REQUIRED_CONFIRMATION_FRAMES = 3
    const val CONFIRMATION_FRAME_TIMEOUT_MS = 1_500L
}

internal data class AntiBotStallStatus(
    val idleMs: Long,
    val recoveryDue: Boolean,
    val recoveryConsumed: Boolean,
)

/**
 * Tracks a continuously visible Anti-bot follow-up that is making no progress.
 *
 * One emergency recovery is allowed per 60-second stall window. A successful card tap, an accepted
 * new layout, or confirmed Anti-bot exit explicitly re-arms normal tracking. If an emergency
 * recovery produces no successful card tap, it starts a fresh 60-second window instead of retrying
 * immediately or remaining permanently disabled.
 */
internal class AntiBotStallRecoveryTracker(
    private val stallTimeoutMs: Long = AntiBotStallRecoveryPolicy.STALL_TIMEOUT_MS,
    private val maxMatchGapMs: Long = AntiBotStallRecoveryPolicy.MAX_MATCH_GAP_MS,
) {
    private val lock = Any()
    private var continuousVisibleSinceMs = 0L
    private var lastMatchedAtMs = 0L
    private var lastProgressAtMs = 0L
    private var recoveryConsumed = false

    init {
        require(stallTimeoutMs > 0L)
        require(maxMatchGapMs > 0L)
    }

    fun reset() = synchronized(lock) {
        continuousVisibleSinceMs = 0L
        lastMatchedAtMs = 0L
        lastProgressAtMs = 0L
        recoveryConsumed = false
    }

    fun observeMatched(nowMs: Long): AntiBotStallStatus = synchronized(lock) {
        val matchGapMs = if (lastMatchedAtMs > 0L) nowMs - lastMatchedAtMs else 0L
        val restartedVisibilityWindow =
            continuousVisibleSinceMs == 0L ||
                nowMs < lastMatchedAtMs ||
                (!recoveryConsumed && lastMatchedAtMs > 0L && matchGapMs > maxMatchGapMs)

        if (restartedVisibilityWindow) {
            continuousVisibleSinceMs = nowMs
            lastProgressAtMs = nowMs
        }
        lastMatchedAtMs = nowMs
        statusLocked(nowMs)
    }

    fun recordProgress(nowMs: Long): AntiBotStallStatus = synchronized(lock) {
        restartWindowLocked(nowMs)
        statusLocked(nowMs)
    }

    fun recordFailedRecoveryAttempt(nowMs: Long): AntiBotStallStatus = synchronized(lock) {
        restartWindowLocked(nowMs)
        statusLocked(nowMs)
    }

    fun tryConsumeRecovery(nowMs: Long): Boolean = synchronized(lock) {
        val status = statusLocked(nowMs)
        if (!status.recoveryDue) return@synchronized false
        recoveryConsumed = true
        true
    }

    fun status(nowMs: Long): AntiBotStallStatus = synchronized(lock) {
        statusLocked(nowMs)
    }

    private fun restartWindowLocked(nowMs: Long) {
        continuousVisibleSinceMs = nowMs
        lastMatchedAtMs = nowMs
        lastProgressAtMs = nowMs
        recoveryConsumed = false
    }

    private fun statusLocked(nowMs: Long): AntiBotStallStatus {
        if (continuousVisibleSinceMs == 0L || lastMatchedAtMs == 0L) {
            return AntiBotStallStatus(0L, recoveryDue = false, recoveryConsumed = recoveryConsumed)
        }
        if (nowMs < lastMatchedAtMs || nowMs < lastProgressAtMs) {
            return AntiBotStallStatus(0L, recoveryDue = false, recoveryConsumed = recoveryConsumed)
        }
        val baseMs = maxOf(continuousVisibleSinceMs, lastProgressAtMs)
        val idleMs = (nowMs - baseMs).coerceAtLeast(0L)
        val recentlyMatched = nowMs - lastMatchedAtMs <= maxMatchGapMs
        return AntiBotStallStatus(
            idleMs = idleMs,
            recoveryDue = !recoveryConsumed && recentlyMatched && idleMs >= stallTimeoutMs,
            recoveryConsumed = recoveryConsumed,
        )
    }
}

internal fun antiBotIncompleteRetryCards(
    testIndexInChallenge: Int,
    selectedCards: List<Int>,
    successfulCards: List<Int>,
    cardCount: Int = 6,
): List<Int> {
    if (testIndexInChallenge !in 1..2 || selectedCards.isEmpty() || cardCount <= 0) return emptyList()
    val successful = successfulCards.toSet()
    return selectedCards.distinct().filter { card ->
        card in 1..cardCount && card !in successful
    }
}
