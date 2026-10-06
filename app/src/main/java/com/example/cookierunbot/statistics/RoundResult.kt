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
)

data class AntiBotEvent(
    val detectionIndex: Int,
    val round: Int,
    val detectedAt: Long,
    val selectedCards: List<Int>,
    val successfulCards: List<Int> = emptyList(),
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
    val antiBotDetectedCount: Int = 0,
    val antiBotSelectedTapCount: Int = 0,
    val antiBotSuccessfulTapCount: Int = 0,
    val antiBotHistory: List<AntiBotEvent> = emptyList(),
)
