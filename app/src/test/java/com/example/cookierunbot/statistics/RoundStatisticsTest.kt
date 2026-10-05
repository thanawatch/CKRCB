package com.example.cookierunbot.statistics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Rect

class RoundStatisticsTest {
    @After fun clean() = RoundResultStore.clearForTests()

    @Test fun digitOrderingConstructsExpectedValue() {
        assertEquals(84_302, ResultNumberDetector.constructNumber(listOf(8, 4, 3, 0, 2)))
    }

    @Test fun invalidDigitSequenceIsRejected() {
        assertNull(ResultNumberDetector.constructNumber(listOf(1, -1, 2)))
    }

    @Test fun storeKeepsLatestFiftyRounds() {
        val generation = RoundResultStore.startNewSession()
        (1..55).forEach { RoundResultStore.upsert(result(it, coins = it, xp = it * 2), generation) }
        val snapshot = RoundResultStore.snapshot()
        assertEquals(55, snapshot.rounds)
        assertEquals(50, snapshot.history.size)
        assertEquals(55, snapshot.lastRound?.round)
    }

    @Test fun cumulativeSessionTotalsIncludeRoundsEvictedFromHistory() {
        val generation = RoundResultStore.startNewSession()
        (1..75).forEach { round ->
            RoundResultStore.upsert(
                result(round, coins = 10, xp = 20).copy(boxes = listOf("wood"), boxCount = 1),
                generation,
            )
        }
        val snapshot = RoundResultStore.snapshot()
        assertEquals(75, snapshot.rounds)
        assertEquals(750, snapshot.totalCoins)
        assertEquals(1_500, snapshot.totalXp)
        assertEquals(75, snapshot.totalBoxes)
        assertEquals(75, snapshot.woodBoxes)
        assertEquals(50, snapshot.history.size)
    }

    @Test fun lateMysteryBoxReplacementUsesDeltaAndDoesNotDoubleCount() {
        val generation = RoundResultStore.startNewSession()
        RoundResultStore.upsert(result(1, 10, 20), generation)
        RoundResultStore.updateBoxes(1, listOf("wood", "gold"), generation)
        RoundResultStore.updateBoxes(1, listOf("rainbow"), generation)
        val snapshot = RoundResultStore.snapshot()
        assertEquals(1, snapshot.totalBoxes)
        assertEquals(0, snapshot.woodBoxes)
        assertEquals(0, snapshot.goldBoxes)
        assertEquals(1, snapshot.rainbowBoxes)
    }

    @Test fun boxUpdatesAndSessionTotalsAreThreadSafeValues() {
        val generation = RoundResultStore.startNewSession()
        RoundResultStore.upsert(result(1, coins = 10, xp = 20), generation)
        RoundResultStore.updateBoxes(1, listOf("gold", "silver"), generation)
        val snapshot = RoundResultStore.snapshot()
        assertEquals(10, snapshot.totalCoins)
        assertEquals(20, snapshot.totalXp)
        assertEquals(2, snapshot.totalBoxes)
        assertEquals(listOf("gold", "silver"), snapshot.lastRound?.boxes)
    }

    @Test fun nullCoinsAndXpAreNotInventedOrAdded() {
        val generation = RoundResultStore.startNewSession()
        RoundResultStore.upsert(result(1, coins = null, xp = null), generation)
        val snapshot = RoundResultStore.snapshot()
        assertNull(snapshot.lastRound?.coins)
        assertNull(snapshot.lastRound?.xp)
        assertEquals(0, snapshot.totalCoins)
        assertEquals(0, snapshot.totalXp)
    }

    @Test fun newSessionClearsPreviousRoundsAndTotals() {
        val first = RoundResultStore.startNewSession()
        RoundResultStore.upsert(result(1, 100, 10), first)
        RoundResultStore.upsert(result(2, 200, 20).copy(boxes = listOf("gold"), boxCount = 1), first)

        RoundResultStore.startNewSession()
        val snapshot = RoundResultStore.snapshot()
        assertEquals(0, snapshot.rounds)
        assertEquals(0, snapshot.totalCoins)
        assertEquals(0, snapshot.totalXp)
        assertEquals(0, snapshot.totalBoxes)
        assertEquals(emptyList<RoundResult>(), snapshot.history)
        assertNull(snapshot.lastRound)
    }

    @Test fun roundNumbersCanRestartWithoutRetainingOldRound() {
        val first = RoundResultStore.startNewSession()
        RoundResultStore.upsert(result(1, 100, 10), first)
        val second = RoundResultStore.startNewSession()
        RoundResultStore.upsert(result(1, 200, 20), second)
        val snapshot = RoundResultStore.snapshot()
        assertEquals(1, snapshot.rounds)
        assertEquals(200, snapshot.lastRound?.coins)
    }

    @Test fun sessionRuntimeResets() {
        RoundResultStore.startNewSession(System.currentTimeMillis() - 10_000L)
        assertTrue(RoundResultStore.snapshot().sessionRuntimeMs >= 9_000L)
        RoundResultStore.startNewSession(System.currentTimeMillis())
        assertTrue(RoundResultStore.snapshot().sessionRuntimeMs < 1_000L)
    }

    @Test fun staleResultGenerationIsRejected() {
        val stale = RoundResultStore.startNewSession()
        val current = RoundResultStore.startNewSession()
        assertFalse(RoundResultStore.upsert(result(1, 100, 10), stale))
        assertTrue(RoundResultStore.upsert(result(1, 200, 20), current))
        assertEquals(200, RoundResultStore.snapshot().lastRound?.coins)
    }

    @Test fun staleMysteryBoxGenerationIsRejected() {
        val stale = RoundResultStore.startNewSession()
        RoundResultStore.upsert(result(1, 100, 10), stale)
        val current = RoundResultStore.startNewSession()
        RoundResultStore.upsert(result(1, 200, 20), current)
        assertFalse(RoundResultStore.updateBoxes(1, listOf("gold"), stale))
        assertEquals(0, RoundResultStore.snapshot().totalBoxes)
    }

    @Test fun currentGenerationBoxUpdateStillWorks() {
        val generation = RoundResultStore.startNewSession()
        RoundResultStore.upsert(result(1, 100, 10), generation)
        assertTrue(RoundResultStore.updateBoxes(1, listOf("rainbow"), generation))
        assertEquals(listOf("rainbow"), RoundResultStore.snapshot().lastRound?.boxes)
    }

    @Test fun sessionBoxTotalsCountRepeatedGradesSeparately() {
        val generation = RoundResultStore.startNewSession()
        RoundResultStore.upsert(result(1, 1, 2).copy(boxes = listOf("wood", "rainbow"), boxCount = 2), generation)
        RoundResultStore.upsert(result(2, 3, 4).copy(boxes = listOf("wood", "wood", "gold"), boxCount = 3), generation)
        val stats = RoundResultStore.snapshot()
        assertEquals(3, stats.woodBoxes)
        assertEquals(0, stats.silverBoxes)
        assertEquals(1, stats.goldBoxes)
        assertEquals(1, stats.rainbowBoxes)
        assertEquals(5, stats.totalBoxes)
        assertEquals(4, stats.totalCoins)
        assertEquals(6, stats.totalXp)
    }

    @Test fun nmsKeepsDistinctBoxesAndOrdersThemLeftToRight() {
        val selected = MysteryBoxClassifier.suppressOverlaps(
            listOf(
                detection("rainbow", .93, 500),
                detection("wood", .94, 100),
                detection("silver", .92, 300),
                detection("gold", .91, 400),
            ),
        )
        assertEquals(listOf("wood", "silver", "gold", "rainbow"), selected.map { it.grade })
    }

    @Test fun nmsPreservesRepeatedGradeAtDifferentLocations() {
        val selected = MysteryBoxClassifier.suppressOverlaps(
            listOf(detection("wood", .95, 100), detection("wood", .94, 350)),
        )
        assertEquals(listOf("wood", "wood"), selected.map { it.grade })
    }

    @Test fun nmsCollapsesScaleAndGradeCompetitionAtSameLocation() {
        val selected = MysteryBoxClassifier.suppressOverlaps(
            listOf(
                detection("gold", .91, 200, 100),
                detection("gold", .87, 203, 105),
                detection("silver", .72, 201, 98),
            ),
        )
        assertEquals(1, selected.size)
        assertEquals("gold", selected.single().grade)
        assertEquals(.91, selected.single().score, .0001)
    }

    @Test fun nmsCollapsesShiftedDuplicateOfSamePhysicalBox() {
        val selected = MysteryBoxClassifier.suppressOverlaps(
            listOf(
                BoxDetection("wood", .96, Rect(200, 100, 169, 152)),
                BoxDetection("wood", .90, Rect(305, 100, 169, 152)),
                BoxDetection("gold", .94, Rect(430, 100, 169, 152)),
                BoxDetection("silver", .93, Rect(650, 100, 169, 152)),
            ),
        )
        assertEquals(listOf("wood", "gold", "silver"), selected.map { it.grade })
    }

    @Test fun overlapDedupDoesNotMergeSeparateAdjacentBoxes() {
        assertFalse(
            MysteryBoxClassifier.samePhysicalBox(
                Rect(200, 100, 169, 152),
                Rect(380, 100, 169, 152),
            ),
        )
    }

    @Test fun positionClusterCollapsesDuplicateEvenWhenOverlapIsTooSmall() {
        val selected = MysteryBoxClassifier.suppressOverlaps(
            listOf(
                BoxDetection("silver", .96, Rect(200, 100, 169, 152)),
                // Shifted 145 px: IoU and smaller-area overlap are both below 0.30,
                // but this is still within one physical box-width cluster.
                BoxDetection("silver", .88, Rect(345, 100, 169, 152)),
                BoxDetection("rainbow", .95, Rect(600, 100, 175, 150)),
            ),
        )
        assertEquals(listOf("silver", "rainbow"), selected.map { it.grade })
    }

    @Test fun positionClusterDoesNotMergeTwoRealSideBySideBoxes() {
        assertFalse(
            MysteryBoxClassifier.centersBelongToSamePositionCluster(
                Rect(200, 100, 169, 152),
                Rect(370, 100, 169, 152),
            ),
        )
    }

    @Test fun positionClusterWorksAcrossDifferentTemplateGrades() {
        val selected = MysteryBoxClassifier.suppressOverlaps(
            listOf(
                BoxDetection("silver", .94, Rect(300, 100, 169, 152)),
                BoxDetection("wood", .70, Rect(440, 105, 169, 152)),
                BoxDetection("gold", .93, Rect(650, 100, 169, 152)),
            ),
        )
        assertEquals(listOf("silver", "gold"), selected.map { it.grade })
    }

    @Test fun threePhysicalBoxesIgnoreDuplicateMiddlePeak() {
        val selected = MysteryBoxClassifier.suppressOverlaps(
            listOf(
                BoxDetection("wood", .96, Rect(235, 280, 169, 152)),
                BoxDetection("silver", .95, Rect(550, 310, 169, 152)),
                BoxDetection("silver", .82, Rect(695, 310, 169, 152)),
                BoxDetection("rainbow", .94, Rect(875, 280, 175, 150)),
            ),
        )
        assertEquals(listOf("wood", "silver", "rainbow"), selected.map { it.grade })
    }

    @Test fun nmsLimitsOutputToFourBoxes() {
        val selected = MysteryBoxClassifier.suppressOverlaps(
            (0..5).map { detection("wood", .99 - it * .01, it * 140) },
        )
        assertEquals(4, selected.size)
    }

    @Test fun closedScreenBoxListReplacesRatherThanAppends() {
        val generation = RoundResultStore.startNewSession()
        RoundResultStore.upsert(result(1, 10, 20), generation)
        RoundResultStore.updateBoxes(1, listOf("wood", "rainbow"), generation)
        RoundResultStore.updateBoxes(1, listOf("silver"), generation)
        val result = RoundResultStore.snapshot().lastRound
        assertEquals(listOf("silver"), result?.boxes)
        assertEquals(1, result?.boxCount)
    }

    @Test fun onlyFirstMysteryBoxPhaseIsClassified() {
        assertTrue(MysteryBoxPhasePolicy.shouldClassify(1))
        assertFalse(MysteryBoxPhasePolicy.shouldClassify(2))
        assertFalse(MysteryBoxPhasePolicy.shouldClassify(3))
    }

    @Test fun mysteryBoxFastPathFallsBackOnlyWhenScaleOneFindsNothing() {
        assertEquals(1.0, MysteryBoxScanPolicy.FAST_SCALE, 0.0)
        assertTrue(MysteryBoxScanPolicy.shouldRunFallback(emptyList()))
        assertFalse(
            MysteryBoxScanPolicy.shouldRunFallback(
                listOf(detection("gold", .91, 200)),
            ),
        )
    }

    private fun detection(grade: String, score: Double, x: Int, size: Int = 100) =
        BoxDetection(grade, score, Rect(x, 20, size, size))

    private fun result(round: Int, coins: Int?, xp: Int?) = RoundResult(
        round = round,
        finishedAt = round.toLong(),
        durationMs = 1000L,
        coins = coins,
        xp = xp,
    )
}
