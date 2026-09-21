package com.example.cookierunbot.statistics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoundStatisticsTest {
    @After fun clean() = RoundResultStore.clear()

    @Test fun digitOrderingConstructsExpectedValue() {
        assertEquals(84_302, ResultNumberDetector.constructNumber(listOf(8, 4, 3, 0, 2)))
    }

    @Test fun invalidDigitSequenceIsRejected() {
        assertNull(ResultNumberDetector.constructNumber(listOf(1, -1, 2)))
    }

    @Test fun storeKeepsLatestFiftyRounds() {
        (1..55).forEach { RoundResultStore.upsert(result(it, coins = it, xp = it * 2)) }
        val snapshot = RoundResultStore.snapshot()
        assertEquals(50, snapshot.rounds)
        assertEquals(55, snapshot.lastRound?.round)
    }

    @Test fun boxUpdatesAndSessionTotalsAreThreadSafeValues() {
        RoundResultStore.upsert(result(1, coins = 10, xp = 20))
        RoundResultStore.updateBoxes(1, listOf("gold", "silver"))
        val snapshot = RoundResultStore.snapshot()
        assertEquals(10, snapshot.totalCoins)
        assertEquals(20, snapshot.totalXp)
        assertEquals(2, snapshot.totalBoxes)
        assertEquals(listOf("gold", "silver"), snapshot.lastRound?.boxes)
    }

    @Test fun nullCoinsAndXpAreNotInventedOrAdded() {
        RoundResultStore.upsert(result(1, coins = null, xp = null))
        val snapshot = RoundResultStore.snapshot()
        assertNull(snapshot.lastRound?.coins)
        assertNull(snapshot.lastRound?.xp)
        assertEquals(0, snapshot.totalCoins)
        assertEquals(0, snapshot.totalXp)
    }

    private fun result(round: Int, coins: Int?, xp: Int?) = RoundResult(
        round = round,
        finishedAt = round.toLong(),
        durationMs = 1000L,
        coins = coins,
        xp = xp,
    )
}
