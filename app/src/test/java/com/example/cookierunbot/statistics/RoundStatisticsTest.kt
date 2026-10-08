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

    @Test fun fallbackRoundWithUnknownNumbersStillAcceptsLateMysteryBoxes() {
        val generation = RoundResultStore.startNewSession()
        RoundResultStore.upsert(result(1, coins = null, xp = null), generation)

        assertTrue(
            RoundResultStore.updateBoxes(
                1,
                listOf("wood", "rainbow"),
                generation,
            ),
        )

        val snapshot = RoundResultStore.snapshot()
        assertNull(snapshot.lastRound?.coins)
        assertNull(snapshot.lastRound?.xp)
        assertEquals(listOf("wood", "rainbow"), snapshot.lastRound?.boxes)
        assertEquals(2, snapshot.totalBoxes)
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

    @Test fun sessionRuntimeFormatterSwitchesToDaysAtTwentyFourHours() {
        assertEquals("23h 59m 59s", formatSessionRuntime(86_399_000L))
        assertEquals("1 day", formatSessionRuntime(86_400_000L))
        assertEquals("1 day 1h 15m", formatSessionRuntime(90_900_000L))
        assertEquals("2 days", formatSessionRuntime(172_800_000L))
        assertEquals("3 days 30m", formatSessionRuntime(261_000_000L))
    }

    @Test fun antiBotSessionCountersTrackOnlyCurrentGenerationAndReset() {
        val first = RoundResultStore.startNewSession()
        assertTrue(RoundResultStore.recordAntiBotDetected(first, 1, listOf(2, 6)))
        assertTrue(RoundResultStore.recordAntiBotSuccessfulTap(first, 1, 2))
        assertTrue(RoundResultStore.recordAntiBotSuccessfulTap(first, 1, 6))
        assertEquals(1, RoundResultStore.snapshot().antiBotDetectedCount)
        assertEquals(2, RoundResultStore.snapshot().antiBotSuccessfulTapCount)

        val second = RoundResultStore.startNewSession()
        assertFalse(RoundResultStore.recordAntiBotDetected(first, 1, listOf(2, 6)))
        assertFalse(RoundResultStore.recordAntiBotSuccessfulTap(first, 1, 2))
        assertEquals(0, RoundResultStore.snapshot().antiBotDetectedCount)
        assertEquals(0, RoundResultStore.snapshot().antiBotSuccessfulTapCount)

        assertTrue(RoundResultStore.recordAntiBotDetected(second, 1, listOf(3, 5)))
        assertEquals(1, RoundResultStore.snapshot().antiBotDetectedCount)
    }

    @Test fun antiBotHistoryTracksCardsAndSuccessfulCallbacks() {
        val generation = RoundResultStore.startNewSession()
        assertTrue(RoundResultStore.recordAntiBotDetected(generation, 31, listOf(2, 6), 1_000L))
        val detected = RoundResultStore.snapshot()
        assertEquals(1, detected.antiBotChallengeCount)
        assertEquals(1, detected.antiBotDetectedCount)
        assertEquals(2, detected.antiBotSelectedTapCount)
        assertEquals(1, detected.antiBotHistory.single().challengeIndex)
        assertEquals(1, detected.antiBotHistory.single().testIndexInChallenge)
        assertEquals(listOf(2, 6), detected.antiBotHistory.single().selectedCards)

        assertTrue(RoundResultStore.recordAntiBotSuccessfulTap(generation, 1, 2))
        assertTrue(RoundResultStore.recordAntiBotSuccessfulTap(generation, 1, 6))
        val completed = RoundResultStore.snapshot()
        assertEquals(2, completed.antiBotSuccessfulTapCount)
        assertEquals(1, completed.antiBotCompletedTestCount)
        assertEquals(listOf(2, 6), completed.antiBotHistory.single().successfulCards)

        // A duplicate callback for the same card is ignored.
        assertTrue(RoundResultStore.recordAntiBotSuccessfulTap(generation, 1, 6))
        assertEquals(2, RoundResultStore.snapshot().antiBotSuccessfulTapCount)
    }

    @Test fun antiBotChallengeGroupingUsesThreeTestsAndTimeGap() {
        val generation = RoundResultStore.startNewSession()
        RoundResultStore.recordAntiBotDetected(generation, 31, listOf(1, 2), 1_000L)
        RoundResultStore.recordAntiBotDetected(generation, 31, listOf(3, 4), 31_000L)
        RoundResultStore.recordAntiBotDetected(generation, 31, listOf(5, 6), 61_000L)
        RoundResultStore.recordAntiBotDetected(generation, 32, listOf(1, 4), 91_000L)
        val grouped = RoundResultStore.snapshot()
        assertEquals(2, grouped.antiBotChallengeCount)
        assertEquals(listOf(2, 1, 1, 1), grouped.antiBotHistory.map { it.challengeIndex })
        assertEquals(listOf(1, 3, 2, 1), grouped.antiBotHistory.map { it.testIndexInChallenge })

        RoundResultStore.startNewSession()
        val second = RoundResultStore.currentGeneration()
        RoundResultStore.recordAntiBotDetected(second, 40, listOf(1, 2), 1_000L)
        RoundResultStore.recordAntiBotDetected(second, 40, listOf(3, 4), 122_001L)
        val gap = RoundResultStore.snapshot()
        assertEquals(2, gap.antiBotChallengeCount)
        assertEquals(listOf(2, 1), gap.antiBotHistory.map { it.challengeIndex })
    }

    @Test fun antiBotActiveFollowUpKeepsChallengeAcrossLongGap() {
        val generation = RoundResultStore.startNewSession()
        RoundResultStore.recordAntiBotDetected(
            generation,
            round = 40,
            selectedCards = listOf(3, 6),
            detectedAt = 1_000L,
        )
        RoundResultStore.recordAntiBotDetected(
            generation,
            round = 40,
            selectedCards = listOf(5, 6),
            detectedAt = 122_001L,
            continueCurrentChallenge = true,
        )
        RoundResultStore.recordAntiBotDetected(
            generation,
            round = 40,
            selectedCards = listOf(2, 4),
            detectedAt = 243_500L,
            continueCurrentChallenge = true,
        )

        val continued = RoundResultStore.snapshot()
        assertEquals(1, continued.antiBotChallengeCount)
        assertEquals(listOf(1, 1, 1), continued.antiBotHistory.map { it.challengeIndex })
        assertEquals(listOf(3, 2, 1), continued.antiBotHistory.map { it.testIndexInChallenge })

        val nextGeneration = RoundResultStore.startNewSession()
        RoundResultStore.recordAntiBotDetected(
            nextGeneration,
            round = 40,
            selectedCards = listOf(1, 2),
            detectedAt = 1_000L,
        )
        RoundResultStore.recordAntiBotDetected(
            nextGeneration,
            round = 41,
            selectedCards = listOf(3, 4),
            detectedAt = 122_001L,
            continueCurrentChallenge = true,
        )
        val roundChanged = RoundResultStore.snapshot()
        assertEquals(2, roundChanged.antiBotChallengeCount)
        assertEquals(listOf(2, 1), roundChanged.antiBotHistory.map { it.challengeIndex })
        assertEquals(listOf(1, 1), roundChanged.antiBotHistory.map { it.testIndexInChallenge })
    }

    @Test fun antiBotChallengeCompletesOnlyAfterAllThreeTestsComplete() {
        val generation = RoundResultStore.startNewSession()
        (1..3).forEach { test ->
            RoundResultStore.recordAntiBotDetected(
                generation,
                round = 31,
                selectedCards = listOf(1, 2),
                detectedAt = test * 30_000L,
            )
            val detection = RoundResultStore.snapshot().antiBotHistory.first().detectionIndex
            RoundResultStore.recordAntiBotSuccessfulTap(generation, detection, 1)
            RoundResultStore.recordAntiBotSuccessfulTap(generation, detection, 2)
        }
        val beforeExit = RoundResultStore.snapshot()
        assertEquals(1, beforeExit.antiBotChallengeCount)
        assertEquals(0, beforeExit.antiBotCompletedChallengeCount)
        assertEquals(3, beforeExit.antiBotCompletedTestCount)

        assertTrue(RoundResultStore.recordAntiBotExited(generation))
        val afterExit = RoundResultStore.snapshot()
        assertEquals(1, afterExit.antiBotCompletedChallengeCount)
        assertFalse(RoundResultStore.recordAntiBotExited(generation))
        assertEquals(1, RoundResultStore.snapshot().antiBotCompletedChallengeCount)
    }

    @Test fun antiBotEvidenceIsGenerationScopedAndResetsWithSession() {
        val first = RoundResultStore.startNewSession()
        assertTrue(
            RoundResultStore.recordAntiBotEvidence(
                first, 1, AntiBotEvidenceStatus.SENT, attempt = 2,
            ),
        )
        assertEquals(AntiBotEvidenceStatus.SENT, RoundResultStore.snapshot().antiBotLatestEvidence?.status)
        val second = RoundResultStore.startNewSession()
        assertNull(RoundResultStore.snapshot().antiBotLatestEvidence)
        assertFalse(
            RoundResultStore.recordAntiBotEvidence(
                first, 1, AntiBotEvidenceStatus.SEND_FAILED, attempt = 3,
            ),
        )
        assertTrue(
            RoundResultStore.recordAntiBotEvidence(
                second, 1, AntiBotEvidenceStatus.ENCODE_FAILED,
            ),
        )
        assertEquals(
            AntiBotEvidenceStatus.ENCODE_FAILED,
            RoundResultStore.snapshot().antiBotLatestEvidence?.status,
        )
    }

    @Test fun staleAntiBotEvidenceCannotOverwriteNewerDetection() {
        val generation = RoundResultStore.startNewSession()
        assertTrue(
            RoundResultStore.recordAntiBotEvidence(
                generation, 2, AntiBotEvidenceStatus.QUEUED,
            ),
        )
        assertFalse(
            RoundResultStore.recordAntiBotEvidence(
                generation, 1, AntiBotEvidenceStatus.SENT,
            ),
        )
        val latest = RoundResultStore.snapshot().antiBotLatestEvidence
        assertEquals(2, latest?.detectionIndex)
        assertEquals(AntiBotEvidenceStatus.QUEUED, latest?.status)
    }

    @Test fun antiBotHistoryKeepsLatestThirtyButTotalsCoverWholeSession() {
        val generation = RoundResultStore.startNewSession()
        (1..35).forEach { index ->
            RoundResultStore.recordAntiBotDetected(generation, 31, listOf(1, 4), index.toLong())
        }
        val snapshot = RoundResultStore.snapshot()
        assertEquals(12, snapshot.antiBotChallengeCount)
        assertEquals(35, snapshot.antiBotDetectedCount)
        assertEquals(70, snapshot.antiBotSelectedTapCount)
        assertEquals(30, snapshot.antiBotHistory.size)
        assertEquals(35, snapshot.antiBotHistory.first().detectionIndex)
        assertEquals(6, snapshot.antiBotHistory.last().detectionIndex)
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

    @Test fun realLogDuplicateBetweenTwoSilverBoxesIsCollapsed() {
        val selected = MysteryBoxClassifier.suppressOverlaps(
            listOf(
                BoxDetection("wood", .8863, Rect(236, 270, 169, 152)),
                BoxDetection("silver", .8410, Rect(545, 301, 169, 152)),
                BoxDetection("silver", .6500, Rect(708, 353, 169, 152)),
                BoxDetection("silver", .8426, Rect(876, 269, 169, 152)),
            ),
        )
        assertEquals(listOf("wood", "silver", "silver"), selected.map { it.grade })
    }

    @Test fun twoRealSilverBoxesFromLogStaySeparate() {
        assertFalse(
            MysteryBoxClassifier.samePhysicalBox(
                Rect(545, 301, 169, 152),
                Rect(876, 269, 169, 152),
            ),
        )
    }

    @Test fun highScoringBridgeCannotSuppressTwoRealBoxes() {
        val left = BoxDetection("silver", .84, Rect(545, 301, 169, 152))
        val bridge = BoxDetection("silver", .92, Rect(708, 320, 169, 152))
        val right = BoxDetection("silver", .83, Rect(876, 269, 169, 152))

        assertTrue(
            MysteryBoxClassifier.isBridgeCandidate(
                bridge,
                listOf(left, bridge, right),
            ),
        )

        val selected = MysteryBoxClassifier.suppressOverlaps(
            listOf(left, bridge, right),
        )
        assertEquals(listOf(left, right), selected)
    }

    @Test fun strongSingleCandidateDoesNotYieldToWeakFlankingNoise() {
        val leftNoise = BoxDetection("silver", .70, Rect(545, 301, 169, 152))
        val strong = BoxDetection("silver", .95, Rect(708, 320, 169, 152))
        val rightNoise = BoxDetection("silver", .69, Rect(876, 269, 169, 152))

        assertFalse(
            MysteryBoxClassifier.isBridgeCandidate(
                strong,
                listOf(leftNoise, strong, rightNoise),
            ),
        )

        val selected = MysteryBoxClassifier.suppressOverlaps(
            listOf(leftNoise, strong, rightNoise),
        )
        assertEquals(listOf(strong), selected)
    }

    @Test fun exactlyOneBoxWidthApartStaysSeparate() {
        assertFalse(
            MysteryBoxClassifier.centersBelongToSamePositionCluster(
                Rect(200, 100, 100, 100),
                Rect(300, 100, 100, 100),
            ),
        )
    }

    @Test fun verticalDuplicateGapFromLogIsCollapsed() {
        assertTrue(
            MysteryBoxClassifier.samePhysicalBox(
                Rect(564, 358, 169, 152),
                Rect(566, 243, 169, 152),
            ),
        )
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

    @Test fun mysteryBoxScaleFallbackUsesStagedPolicy() {
        assertEquals(1.0, MysteryBoxScanPolicy.FAST_SCALE, 0.0)

        assertFalse(
            MysteryBoxScanPolicy.shouldRunNearFallback(
                listOf(
                    detection("wood", .95, 100),
                    detection("silver", .94, 300),
                    detection("gold", .93, 500),
                    detection("rainbow", .92, 700),
                ),
            ),
        )
        assertFalse(
            MysteryBoxScanPolicy.shouldRunNearFallback(
                listOf(
                    detection("wood", .95, 100),
                    detection("silver", .94, 300),
                    detection("rainbow", .92, 700),
                ),
            ),
        )
        assertTrue(
            MysteryBoxScanPolicy.shouldRunNearFallback(
                listOf(
                    detection("wood", .95, 100),
                    detection("silver", .70, 300),
                    detection("rainbow", .92, 700),
                ),
            ),
        )
        assertTrue(
            MysteryBoxScanPolicy.shouldRunNearFallback(
                listOf(
                    detection("wood", .95, 100),
                    detection("silver", .94, 300),
                ),
            ),
        )

        assertFalse(
            MysteryBoxScanPolicy.shouldRunFarProbe(
                listOf(
                    detection("wood", .95, 100),
                    detection("silver", .94, 300),
                    detection("gold", .93, 500),
                    detection("rainbow", .92, 700),
                ),
            ),
        )
        assertFalse(
            MysteryBoxScanPolicy.shouldRunFarProbe(
                listOf(
                    detection("wood", .95, 100),
                    detection("silver", .94, 300),
                    detection("rainbow", .92, 700),
                ),
            ),
        )
        assertTrue(
            MysteryBoxScanPolicy.shouldRunFarProbe(
                listOf(
                    detection("wood", .95, 100),
                    detection("silver", .70, 300),
                    detection("rainbow", .92, 700),
                ),
            ),
        )
    }

    @Test fun mysteryBoxExtremeFallbackOnlyRunsForSuspiciousFarProbeResult() {
        val stableThree = listOf(
            detection("wood", .95, 100),
            detection("silver", .94, 300),
            detection("rainbow", .92, 700),
        )
        assertFalse(
            MysteryBoxScanPolicy.shouldRunExtremeFallback(
                beforeFarProbeCount = 3,
                farProbeDetections = stableThree,
            ),
        )

        assertTrue(
            MysteryBoxScanPolicy.shouldRunExtremeFallback(
                beforeFarProbeCount = 2,
                farProbeDetections = stableThree,
            ),
        )

        assertTrue(
            MysteryBoxScanPolicy.shouldRunExtremeFallback(
                beforeFarProbeCount = 2,
                farProbeDetections = stableThree.take(2),
            ),
        )

        assertTrue(
            MysteryBoxScanPolicy.shouldRunExtremeFallback(
                beforeFarProbeCount = 3,
                farProbeDetections = listOf(
                    detection("wood", .95, 100),
                    detection("silver", .70, 300),
                    detection("rainbow", .92, 700),
                ),
            ),
        )

        assertFalse(
            MysteryBoxScanPolicy.shouldRunExtremeFallback(
                beforeFarProbeCount = 3,
                farProbeDetections = listOf(
                    detection("wood", .95, 100),
                    detection("silver", .94, 300),
                    detection("gold", .93, 500),
                    detection("rainbow", .92, 700),
                ),
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
