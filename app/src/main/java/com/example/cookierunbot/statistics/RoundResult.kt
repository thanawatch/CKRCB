package com.example.cookierunbot.statistics

data class RoundResult(
    val round: Int,
    val finishedAt: Long,
    val durationMs: Long?,
    val coins: Int?,
    val xp: Int?,
    val boxes: List<String> = emptyList(),
    val boxCount: Int = boxes.size,
    val screenshotPath: String? = null,
    val hasScreenshot: Boolean = screenshotPath != null,
    /** True only after the Mystery Box scan completed; an empty list alone is not proof of zero boxes. */
    val boxesScanned: Boolean = false,
)

data class AntiBotEvent(
    val detectionIndex: Int,
    val challengeIndex: Int,
    val testIndexInChallenge: Int,
    val round: Int,
    val detectedAt: Long,
    val selectedCards: List<Int>,
    val successfulCards: List<Int> = emptyList(),
) {
    val completed: Boolean
        get() = selectedCards.isNotEmpty() && successfulCards.size >= selectedCards.size
}

enum class AntiBotEvidenceStatus {
    QUEUED,
    RETRYING,
    SENT,
    ENCODE_FAILED,
    QUEUE_FAILED,
    SEND_FAILED,
    CONFIG_CHANGED,
    DELIVERY_DISABLED,
    TELEGRAM_DISABLED,
    INVALID_CONFIG,
}

data class AntiBotEvidence(
    val detectionIndex: Int,
    val status: AntiBotEvidenceStatus,
    val attempt: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class SessionStatistics(
    val rounds: Int,
    val totalCoins: Int,
    val totalXp: Int,
    val totalBoxes: Int,
    val woodBoxes: Int,
    val silverBoxes: Int,
    val goldBoxes: Int,
    val rainbowBoxes: Int,
    val lastRound: RoundResult?,
    val history: List<RoundResult>,
    val sessionRuntimeMs: Long,
    val antiBotChallengeCount: Int = 0,
    val antiBotCompletedChallengeCount: Int = 0,
    val antiBotDetectedCount: Int = 0,
    val antiBotCompletedTestCount: Int = 0,
    val antiBotSelectedTapCount: Int = 0,
    val antiBotSuccessfulTapCount: Int = 0,
    val antiBotHistory: List<AntiBotEvent> = emptyList(),
    val antiBotLatestEvidence: AntiBotEvidence? = null,
    /** Number of rounds confirmed to have produced exactly 0..5 Mystery Boxes, keyed by box count. */
    val roundsByBoxCount: Map<Int, Int> = emptyMap(),
)
