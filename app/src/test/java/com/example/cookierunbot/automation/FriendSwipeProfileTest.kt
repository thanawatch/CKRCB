package com.example.cookierunbot.automation

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendSwipeProfileTest {
    @Test fun findTopProfilesStayInsideSafeEnvelope() {
        profiles(FriendSwipePhase.FIND_TOP, FIND_TOP_START, FIND_TOP_END).forEach(::assertInBounds)
    }

    @Test fun scrollDownProfilesStayInsideSafeEnvelope() {
        profiles(FriendSwipePhase.SCROLL_DOWN, SCROLL_DOWN_START, SCROLL_DOWN_END).forEach(::assertInBounds)
    }

    @Test fun profilesKeepCorrectBroadlyMonotonicDirection() {
        profiles(FriendSwipePhase.FIND_TOP, FIND_TOP_START, FIND_TOP_END).forEach { profile ->
            assertTrue(profile.logicalPoints.last().y > profile.logicalPoints.first().y)
            assertTrue(profile.logicalPoints.zipWithNext().all { (first, second) -> second.y > first.y })
        }
        profiles(FriendSwipePhase.SCROLL_DOWN, SCROLL_DOWN_START, SCROLL_DOWN_END).forEach { profile ->
            assertTrue(profile.logicalPoints.last().y < profile.logicalPoints.first().y)
            assertTrue(profile.logicalPoints.zipWithNext().all { (first, second) -> second.y < first.y })
        }
    }

    @Test fun durationsStayInsidePhaseSpecificRanges() {
        profiles(FriendSwipePhase.FIND_TOP, FIND_TOP_START, FIND_TOP_END).forEach { profile ->
            assertTrue(profile.durationMs in 120L..160L)
        }
        profiles(FriendSwipePhase.SCROLL_DOWN, SCROLL_DOWN_START, SCROLL_DOWN_END).forEach { profile ->
            assertTrue(profile.durationMs in 650L..950L)
        }
    }

    @Test fun generatedProfilesAreNotMechanicallyIdentical() {
        val signatures = profiles(FriendSwipePhase.SCROLL_DOWN, SCROLL_DOWN_START, SCROLL_DOWN_END)
            .map { it.logicalPoints to it.durationMs }
            .toSet()
        assertTrue(signatures.size > 1)
    }

    @Test fun samplingRemainsCenteredOnCanonicalEndpoints() {
        val findTop = profiles(FriendSwipePhase.FIND_TOP, FIND_TOP_START, FIND_TOP_END, count = 2_000)
        val scrollDown = profiles(FriendSwipePhase.SCROLL_DOWN, SCROLL_DOWN_START, SCROLL_DOWN_END, count = 2_000)
        assertMeanNear(findTop.map { it.logicalPoints.first() }, FIND_TOP_START)
        assertMeanNear(findTop.map { it.logicalPoints.last() }, FIND_TOP_END)
        assertMeanNear(scrollDown.map { it.logicalPoints.first() }, SCROLL_DOWN_START)
        assertMeanNear(scrollDown.map { it.logicalPoints.last() }, SCROLL_DOWN_END)
    }

    @Test fun pathsUseSixControlPointsAndIncludeSubtleLateralVariation() {
        val generated = profiles(FriendSwipePhase.SCROLL_DOWN, SCROLL_DOWN_START, SCROLL_DOWN_END)
        assertTrue(generated.all { it.logicalPoints.size == FriendSwipeProfileGenerator.POINT_COUNT })
        assertTrue(generated.any { profile -> profile.logicalPoints.map { it.x }.distinct().size >= 3 })
    }

    private fun profiles(
        phase: FriendSwipePhase,
        start: LogicalPoint,
        end: LogicalPoint,
        count: Int = 250,
    ): List<FriendSwipeProfile> {
        val random = Random(0xC00C1E)
        return List(count) { FriendSwipeProfileGenerator.create(phase, start, end, random) }
    }

    private fun assertInBounds(profile: FriendSwipeProfile) {
        profile.logicalPoints.forEach { point ->
            assertTrue(point.x in FriendSwipeProfileGenerator.SAFE_MIN_X..FriendSwipeProfileGenerator.SAFE_MAX_X)
            assertTrue(point.y in FriendSwipeProfileGenerator.SAFE_MIN_Y..FriendSwipeProfileGenerator.SAFE_MAX_Y)
        }
    }

    private fun assertMeanNear(points: List<LogicalPoint>, expected: LogicalPoint) {
        val meanX = points.map { it.x }.average()
        val meanY = points.map { it.y }.average()
        assertTrue("mean x=$meanX expected=${expected.x}", abs(meanX - expected.x) < 1.0)
        assertTrue("mean y=$meanY expected=${expected.y}", abs(meanY - expected.y) < 1.0)
    }

    private companion object {
        val FIND_TOP_START = LogicalPoint(435, 350)
        val FIND_TOP_END = LogicalPoint(435, 580)
        val SCROLL_DOWN_START = LogicalPoint(435, 550)
        val SCROLL_DOWN_END = LogicalPoint(435, 330)
    }
}
