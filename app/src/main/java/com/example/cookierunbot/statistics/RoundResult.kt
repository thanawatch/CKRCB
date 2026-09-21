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

data class SessionStatistics(
    val rounds: Int,
    val totalCoins: Int,
    val totalXp: Int,
    val totalBoxes: Int,
    val lastRound: RoundResult?,
    val history: List<RoundResult>,
    val sessionRuntimeMs: Long,
)
