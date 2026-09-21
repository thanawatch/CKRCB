package com.example.cookierunbot.statistics

object RoundResultStore {
    private const val MAX_ROUNDS = 50
    private val lock = Any()
    private val results = ArrayDeque<RoundResult>()
    private var sessionStartedAt = 0L

    fun markSessionStarted(wallClockMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (sessionStartedAt == 0L) sessionStartedAt = wallClockMs
    }

    fun upsert(result: RoundResult) = synchronized(lock) {
        val retained = results.filterNot { it.round == result.round }
        results.clear()
        results.addFirst(result)
        retained.take(MAX_ROUNDS - 1).forEach(results::addLast)
    }

    fun updateBoxes(round: Int, boxes: List<String>) = synchronized(lock) {
        val current = results.firstOrNull { it.round == round } ?: return@synchronized
        upsert(current.copy(boxes = boxes.toList(), boxCount = boxes.size))
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

    internal fun clear() = synchronized(lock) { results.clear(); sessionStartedAt = 0L }
}
