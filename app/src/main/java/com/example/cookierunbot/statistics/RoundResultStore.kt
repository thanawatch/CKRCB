package com.example.cookierunbot.statistics

object RoundResultStore {
    private const val MAX_ROUNDS = 50
    private const val MAX_ANTI_BOT_EVENTS = 30
    private const val ANTI_BOT_TESTS_PER_CHALLENGE = 3
    private const val ANTI_BOT_CHALLENGE_GAP_MS = 2 * 60 * 1000L
    private val lock = Any()
    private val results = ArrayDeque<RoundResult>()
    private val antiBotHistory = ArrayDeque<AntiBotEvent>()
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
    private var antiBotChallengeCount = 0
    private var antiBotCompletedChallengeCount = 0
    private val completedAntiBotChallenges = mutableSetOf<Int>()
    private var antiBotDetectedCount = 0
    private var antiBotCompletedTestCount = 0
    private var antiBotSelectedTapCount = 0
    private var antiBotSuccessfulTapCount = 0
    private var antiBotLatestEvidence: AntiBotEvidence? = null

    fun startNewSession(wallClockMs: Long = System.currentTimeMillis()): Long = synchronized(lock) {
        sessionGeneration += 1L
        results.clear()
        antiBotHistory.clear()
        cumulativeByRound.clear()
        totalCoins = 0; totalXp = 0; totalBoxes = 0
        woodBoxes = 0; silverBoxes = 0; goldBoxes = 0; rainbowBoxes = 0
        antiBotChallengeCount = 0; antiBotCompletedChallengeCount = 0
        completedAntiBotChallenges.clear()
        antiBotDetectedCount = 0; antiBotCompletedTestCount = 0
        antiBotSelectedTapCount = 0; antiBotSuccessfulTapCount = 0
        antiBotLatestEvidence = null
        sessionStartedAt = wallClockMs
        sessionGeneration
    }

    fun recordAntiBotDetected(
        generation: Long,
        round: Int = 0,
        selectedCards: List<Int> = emptyList(),
        detectedAt: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        if (generation != sessionGeneration) return@synchronized false
        val previous = antiBotHistory.firstOrNull()
        val startsNewChallenge = previous == null ||
            previous.testIndexInChallenge >= ANTI_BOT_TESTS_PER_CHALLENGE ||
            (previous.round > 0 && round > 0 && previous.round != round) ||
            detectedAt - previous.detectedAt > ANTI_BOT_CHALLENGE_GAP_MS ||
            detectedAt < previous.detectedAt
        val challengeIndex = if (startsNewChallenge) {
            antiBotChallengeCount += 1
            antiBotChallengeCount
        } else {
            previous.challengeIndex
        }
        val testIndex = if (startsNewChallenge) 1 else previous.testIndexInChallenge + 1

        antiBotDetectedCount += 1
        antiBotSelectedTapCount += selectedCards.size
        antiBotHistory.addFirst(
            AntiBotEvent(
                detectionIndex = antiBotDetectedCount,
                challengeIndex = challengeIndex,
                testIndexInChallenge = testIndex,
                round = round,
                detectedAt = detectedAt,
                selectedCards = selectedCards.toList(),
            ),
        )
        while (antiBotHistory.size > MAX_ANTI_BOT_EVENTS) antiBotHistory.removeLast()
        true
    }

    fun recordAntiBotSuccessfulTap(
        generation: Long,
        detectionIndex: Int? = null,
        card: Int? = null,
    ): Boolean = synchronized(lock) {
        if (generation != sessionGeneration) return@synchronized false
        if (detectionIndex == null || card == null) {
            antiBotSuccessfulTapCount += 1
            return@synchronized true
        }
        val index = antiBotHistory.indexOfFirst { it.detectionIndex == detectionIndex }
        if (index < 0) return@synchronized false

        val current = antiBotHistory[index]
        if (card in current.successfulCards) return@synchronized true
        val wasCompleted = current.completed
        val updated = current.copy(successfulCards = current.successfulCards + card)
        antiBotHistory[index] = updated
        antiBotSuccessfulTapCount += 1

        if (!wasCompleted && updated.completed) {
            antiBotCompletedTestCount += 1
            val challengeComplete = updated.testIndexInChallenge == ANTI_BOT_TESTS_PER_CHALLENGE &&
                antiBotHistory
                    .filter { it.challengeIndex == updated.challengeIndex }
                    .let { events ->
                        events.size >= ANTI_BOT_TESTS_PER_CHALLENGE &&
                            events.all { it.completed }
                    }
            if (challengeComplete && completedAntiBotChallenges.add(updated.challengeIndex)) {
                antiBotCompletedChallengeCount += 1
            }
        }
        true
    }

    fun recordAntiBotEvidence(
        generation: Long,
        detectionIndex: Int,
        status: AntiBotEvidenceStatus,
        attempt: Int = 0,
        updatedAt: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        if (generation != sessionGeneration) return@synchronized false
        val current = antiBotLatestEvidence
        if (current != null && detectionIndex < current.detectionIndex) {
            return@synchronized false
        }
        antiBotLatestEvidence = AntiBotEvidence(
            detectionIndex = detectionIndex,
            status = status,
            attempt = attempt,
            updatedAt = updatedAt,
        )
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
            antiBotChallengeCount = antiBotChallengeCount,
            antiBotCompletedChallengeCount = antiBotCompletedChallengeCount,
            antiBotDetectedCount = antiBotDetectedCount,
            antiBotCompletedTestCount = antiBotCompletedTestCount,
            antiBotSelectedTapCount = antiBotSelectedTapCount,
            antiBotSuccessfulTapCount = antiBotSuccessfulTapCount,
            antiBotHistory = antiBotHistory.toList(),
            antiBotLatestEvidence = antiBotLatestEvidence,
        )
    }

    internal fun clearForTests() = synchronized(lock) {
        results.clear()
        antiBotHistory.clear()
        cumulativeByRound.clear()
        sessionStartedAt = 0L
        sessionGeneration = 0L
        totalCoins = 0; totalXp = 0; totalBoxes = 0
        woodBoxes = 0; silverBoxes = 0; goldBoxes = 0; rainbowBoxes = 0
        antiBotChallengeCount = 0; antiBotCompletedChallengeCount = 0
        completedAntiBotChallenges.clear()
        antiBotDetectedCount = 0; antiBotCompletedTestCount = 0
        antiBotSelectedTapCount = 0; antiBotSuccessfulTapCount = 0
        antiBotLatestEvidence = null
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
