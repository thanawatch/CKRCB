package com.example.cookierunbot.statistics

object RoundResultStore {
    private const val MAX_ROUNDS = 50
    private val lock = Any()
    private val results = ArrayDeque<RoundResult>()
    private val cumulativeByRound = mutableMapOf<Int, RoundContribution>()
    private var sessionStartedAt = 0L
    private var sessionGeneration = 0L
    private var totalCoins = 0
    private var totalXp = 0
    private var totalBoxes = 0
    private var woodBoxes = 0
    private var silverBoxes = 0
    private var goldBoxes = 0
    private var rainbowBoxes = 0
    private var antiBotDetectedCount = 0
    private var antiBotSuccessfulTapCount = 0

    fun startNewSession(wallClockMs: Long = System.currentTimeMillis()): Long = synchronized(lock) {
        sessionGeneration += 1L
        results.clear()
        cumulativeByRound.clear()
        totalCoins = 0; totalXp = 0; totalBoxes = 0
        woodBoxes = 0; silverBoxes = 0; goldBoxes = 0; rainbowBoxes = 0
        antiBotDetectedCount = 0; antiBotSuccessfulTapCount = 0
        sessionStartedAt = wallClockMs
        sessionGeneration
    }

    fun recordAntiBotDetected(generation: Long): Boolean = synchronized(lock) {
        if (generation != sessionGeneration) return@synchronized false
        antiBotDetectedCount += 1
        true
    }

    fun recordAntiBotSuccessfulTap(generation: Long): Boolean = synchronized(lock) {
        if (generation != sessionGeneration) return@synchronized false
        antiBotSuccessfulTapCount += 1
        true
    }

    fun currentGeneration(): Long = synchronized(lock) { sessionGeneration }

    fun isCurrentGeneration(generation: Long): Boolean = synchronized(lock) {
        generation == sessionGeneration
    }

    fun upsert(result: RoundResult, generation: Long): Boolean = synchronized(lock) {
        if (generation != sessionGeneration) return@synchronized false
        replaceContribution(result.round, RoundContribution.from(result))
        val retained = results.filterNot { it.round == result.round }
        results.clear()
        results.addFirst(result)
        retained.take(MAX_ROUNDS - 1).forEach(results::addLast)
        true
    }

    fun updateBoxes(round: Int, boxes: List<String>, generation: Long): Boolean = synchronized(lock) {
        if (generation != sessionGeneration) return@synchronized false
        val contribution = cumulativeByRound[round] ?: return@synchronized false
        replaceContribution(round, contribution.copyWithBoxes(boxes))
        val current = results.firstOrNull { it.round == round } ?: return@synchronized true
        val retained = results.filterNot { it.round == round }
        results.clear()
        results.addFirst(current.copy(boxes = boxes.toList(), boxCount = boxes.size))
        retained.take(MAX_ROUNDS - 1).forEach(results::addLast)
        true
    }

    fun snapshot(): SessionStatistics = synchronized(lock) {
        val history = results.toList()
        SessionStatistics(
            rounds = cumulativeByRound.size,
            totalCoins = totalCoins,
            totalXp = totalXp,
            totalBoxes = totalBoxes,
            woodBoxes = woodBoxes,
            silverBoxes = silverBoxes,
            goldBoxes = goldBoxes,
            rainbowBoxes = rainbowBoxes,
            lastRound = history.firstOrNull(),
            history = history,
            sessionRuntimeMs = if (sessionStartedAt == 0L) 0L else
                (System.currentTimeMillis() - sessionStartedAt).coerceAtLeast(0L),
            antiBotDetectedCount = antiBotDetectedCount,
            antiBotSuccessfulTapCount = antiBotSuccessfulTapCount,
        )
    }

    internal fun clearForTests() = synchronized(lock) {
        results.clear()
        cumulativeByRound.clear()
        sessionStartedAt = 0L
        sessionGeneration = 0L
        totalCoins = 0; totalXp = 0; totalBoxes = 0
        woodBoxes = 0; silverBoxes = 0; goldBoxes = 0; rainbowBoxes = 0
        antiBotDetectedCount = 0; antiBotSuccessfulTapCount = 0
    }

    private fun replaceContribution(round: Int, replacement: RoundContribution) {
        cumulativeByRound.put(round, replacement)?.let(::subtract)
        add(replacement)
    }

    private fun add(value: RoundContribution) {
        totalCoins += value.coins; totalXp += value.xp; totalBoxes += value.totalBoxes
        woodBoxes += value.wood; silverBoxes += value.silver
        goldBoxes += value.gold; rainbowBoxes += value.rainbow
    }

    private fun subtract(value: RoundContribution) {
        totalCoins -= value.coins; totalXp -= value.xp; totalBoxes -= value.totalBoxes
        woodBoxes -= value.wood; silverBoxes -= value.silver
        goldBoxes -= value.gold; rainbowBoxes -= value.rainbow
    }
}

private data class RoundContribution(
    val coins: Int,
    val xp: Int,
    val totalBoxes: Int,
    val wood: Int,
    val silver: Int,
    val gold: Int,
    val rainbow: Int,
) {
    fun copyWithBoxes(boxes: List<String>) = copy(
        totalBoxes = boxes.size,
        wood = boxes.count { it == "wood" },
        silver = boxes.count { it == "silver" },
        gold = boxes.count { it == "gold" },
        rainbow = boxes.count { it == "rainbow" },
    )

    companion object {
        fun from(result: RoundResult) = RoundContribution(
            result.coins ?: 0,
            result.xp ?: 0,
            result.boxCount,
            result.boxes.count { it == "wood" },
            result.boxes.count { it == "silver" },
            result.boxes.count { it == "gold" },
            result.boxes.count { it == "rainbow" },
        )
    }
}
