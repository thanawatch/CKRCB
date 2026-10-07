package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundDetectionPolicyTest {
    @Test
    fun backgroundCoverageNeverRunsInsideFastStartPriorityWindow() {
        assertFalse(
            BackgroundDetectionPolicy.due(
                nowMs = 10_000L,
                lastMatchedAtMs = 0L,
                lastBackgroundAtMs = 0L,
                fastStartPriority = true,
            ),
        )
        assertFalse(
            BackgroundDetectionPolicy.antiBotFollowUpDue(
                nowMs = 10_000L,
                lastBackgroundAtMs = 0L,
                fastStartPriority = true,
            ),
        )
    }

    @Test
    fun backgroundCoverageWaitsUntilPrimaryHasMissedForGracePeriod() {
        assertFalse(
            BackgroundDetectionPolicy.due(
                nowMs = 2_999L,
                lastMatchedAtMs = 1_000L,
                lastBackgroundAtMs = 0L,
                fastStartPriority = false,
            ),
        )
        assertTrue(
            BackgroundDetectionPolicy.due(
                nowMs = 3_000L,
                lastMatchedAtMs = 1_000L,
                lastBackgroundAtMs = 0L,
                fastStartPriority = false,
            ),
        )
    }

    @Test
    fun rotationSkipsPrimaryStatesAndFindsAntiBotFirst() {
        val order = listOf(
            BotStage.GAME_START,
            BotStage.GAME_RELAY,
            BotStage.ANTI_BOT,
            BotStage.CONNECTION_LOST,
            BotStage.INACTIVE,
            BotStage.GAME_COMPLETE,
            BotStage.MAINMENU,
        )
        val primary = listOf(
            BotStage.GAME_START,
            BotStage.GAME_RELAY,
            BotStage.GAME_COMPLETE,
            BotStage.CONNECTION_LOST,
            BotStage.INACTIVE,
        )

        val selection = BackgroundDetectionPolicy.nextStage(order, 0, primary)

        assertEquals(BotStage.ANTI_BOT, selection.stage)
        assertEquals(3, selection.nextCursor)
    }

    @Test
    fun rotationEventuallyCoversEveryNonPrimaryStateOneAtATime() {
        val order = listOf(
            BotStage.GAME_START,
            BotStage.GAME_RELAY,
            BotStage.ANTI_BOT,
            BotStage.GAME_COMPLETE,
            BotStage.MAINMENU,
            BotStage.PURCHASE_ITEM,
        )
        val primary = setOf(
            BotStage.GAME_START,
            BotStage.GAME_RELAY,
            BotStage.GAME_COMPLETE,
        )
        var cursor = 0
        val seen = linkedSetOf<BotStage>()

        repeat(3) {
            val selection = BackgroundDetectionPolicy.nextStage(order, cursor, primary)
            cursor = selection.nextCursor
            selection.stage?.let(seen::add)
        }

        assertEquals(
            linkedSetOf(BotStage.ANTI_BOT, BotStage.MAINMENU, BotStage.PURCHASE_ITEM),
            seen,
        )
    }

    @Test
    fun antiBotFollowUpUsesLowFixedFrequency() {
        assertFalse(
            BackgroundDetectionPolicy.antiBotFollowUpDue(
                nowMs = 1_499L,
                lastBackgroundAtMs = 1_000L,
                fastStartPriority = false,
            ),
        )
        assertTrue(
            BackgroundDetectionPolicy.antiBotFollowUpDue(
                nowMs = 1_500L,
                lastBackgroundAtMs = 1_000L,
                fastStartPriority = false,
            ),
        )
    }
}
