package com.example.cookierunbot.statistics

import org.junit.Assert.assertEquals
import org.junit.Test

class ZeroBoxRoundStatisticsTest {
    @Test
    fun zeroBoxCountIsDerivedAsTotalRoundsMinusRoundsWithOneToFiveBoxes() {
        RoundResultStore.clearForTests()
        try {
            val generation = RoundResultStore.startNewSession(1L)
            RoundResultStore.upsert(
                RoundResult(
                    round = 1,
                    finishedAt = 2L,
                    durationMs = 1L,
                    coins = 0,
                    xp = 0,
                ),
                generation,
            )
            assertEquals(1, RoundResultStore.snapshot().roundsByBoxCount[0])

            // Zero-box rounds are derived as total rounds minus the 1..5 box buckets.
            assertEquals(true, RoundResultStore.updateBoxes(1, emptyList(), generation))
            assertEquals(1, RoundResultStore.snapshot().roundsByBoxCount[0])

            // A later corrected scan replaces the contribution instead of double-counting.
            assertEquals(true, RoundResultStore.updateBoxes(1, listOf("wood", "silver"), generation))
            val stats = RoundResultStore.snapshot()
            assertEquals(0, stats.roundsByBoxCount[0])
            assertEquals(1, stats.roundsByBoxCount[2])
        } finally {
            RoundResultStore.clearForTests()
        }
    }
}
