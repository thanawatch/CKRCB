package com.example.cookierunbot.statistics

object RoundResultStore {
    private const val MAX_ROUNDS = 50
    private val lock = Any()
    private val results = ArrayDeque<RoundResult>()
    private var sessionStartedAt = 0L
    private var sessionGeneration = 0L

    fun startNewSession(wallClockMs: Long = System.currentTimeMillis()): Long = synchronized(lock) {
        sessionGeneration += 1L
        results.clear()
        sessionStartedAt = wallClockMs
        sessionGeneration
    }

    fun currentGeneration(): Long = synchronized(lock) { sessionGeneration }

    fun isCurrentGeneration(generation: Long): Boolean = synchronized(lock) {
        generation == sessionGeneration
    }

    fun upsert(result: RoundResult, generation: Long): Boolean = synchronized(lock) {
        if (generation != sessionGeneration) return@synchronized false
        val retained = results.filterNot { it.round == result.round }
        results.clear()
        results.addFirst(result)
        retained.take(MAX_ROUNDS - 1).forEach(results::addLast)
        true
    }

    fun updateBoxes(round: Int, boxes: List<String>, generation: Long): Boolean = synchronized(lock) {
        if (generation != sessionGeneration) return@synchronized false
        val current = results.firstOrNull { it.round == round } ?: return@synchronized false
        val retained = results.filterNot { it.round == round }
        results.clear()
        results.addFirst(current.copy(boxes = boxes.toList(), boxCount = boxes.size))
        retained.take(MAX_ROUNDS - 1).forEach(results::addLast)
        true
    }

    fun snapshot(): SessionStatistics = synchronized(lock) {
        val history = results.toList()
        SessionStatistics(
            rounds = history.size,
            totalCoins = history.mapNotNull { it.coins }.sum(),
            totalXp = history.mapNotNull { it.xp }.sum(),
            totalBoxes = history.sumOf { it.boxCount },
            lastRound = history.firstOrNull(),
            history = history,
            sessionRuntimeMs = if (sessionStartedAt == 0L) 0L else
                (System.currentTimeMillis() - sessionStartedAt).coerceAtLeast(0L),
        )
    }

    internal fun clearForTests() = synchronized(lock) {
        results.clear()
        sessionStartedAt = 0L
        sessionGeneration = 0L
    }
}
