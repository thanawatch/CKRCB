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
    fun antiBotFollowUpCannotResetOtherRecoveryGraceForever() {
        assertFalse(
            BackgroundDetectionPolicy.due(
                nowMs = 10_999L,
                lastMatchedAtMs = 10_900L,
                lastBackgroundAtMs = 10_000L,
                fastStartPriority = false,
                antiBotFollowUpActive = true,
            ),
        )
        assertTrue(
            BackgroundDetectionPolicy.due(
                nowMs = 11_000L,
                lastMatchedAtMs = 10_999L,
                lastBackgroundAtMs = 10_000L,
                fastStartPriority = false,
                antiBotFollowUpActive = true,
            ),
        )
    }

    @Test
    fun handledGameStartAndRelayAreRemovedFromInGameScans() {
        val stages = listOf(
            BotStage.GAME_START,
            BotStage.GAME_RELAY,
            BotStage.GAME_COMPLETE,
            BotStage.CONNECTION_LOST,
        )

        assertEquals(
            listOf(BotStage.GAME_RELAY, BotStage.GAME_COMPLETE, BotStage.CONNECTION_LOST),
            excludeHandledInGameStages(stages, gameStartHandled = true, gameRelayHandled = false),
        )
        assertEquals(
            listOf(BotStage.GAME_COMPLETE, BotStage.CONNECTION_LOST),
            excludeHandledInGameStages(stages, gameStartHandled = true, gameRelayHandled = true),
        )
    }

    @Test
    fun productionInGameBackgroundListExcludesCriticalGameplayAndCloseButton() {
        val forbidden = setOf(
            BotStage.GAME_START,
            BotStage.GAME_RELAY,
            BotStage.GAME_COMPLETE,
            BotStage.CONNECTION_LOST,
            BotStage.INACTIVE,
            BotStage.CLOSE_BUTTON,
        )

        assertTrue(BotStage.ANTI_BOT in IN_GAME_BACKGROUND_RECOVERY_STAGES)
        assertTrue(BotStage.LOGIN in IN_GAME_BACKGROUND_RECOVERY_STAGES)
        assertTrue(IN_GAME_BACKGROUND_RECOVERY_STAGES.none { it in forbidden })
    }

    @Test
    fun productionPostRelayOrderAlternatesAntiBotWithRecoveryCandidates() {
        val primary = setOf(
            BotStage.GAME_RELAY,
            BotStage.GAME_COMPLETE,
            BotStage.CONNECTION_LOST,
            BotStage.INACTIVE,
        )
        var cursor = 0
        var antiBotTurn = true
        val seen = mutableListOf<BotStage>()

        repeat(4) {
            val selection = BackgroundDetectionPolicy.nextAfterGameRelay(
                orderedStages = IN_GAME_BACKGROUND_RECOVERY_STAGES,
                cursor = cursor,
                primaryStages = primary,
                antiBotTurn = antiBotTurn,
            )
            cursor = selection.nextCursor
            antiBotTurn = selection.nextAntiBotTurn
            selection.stage?.let(seen::add)
        }

        assertEquals(
            listOf(
                BotStage.ANTI_BOT,
                BotStage.LOGIN,
                BotStage.ANTI_BOT,
                BotStage.OVERTAKE_BREAK_SCORE,
            ),
            seen,
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
    fun postRelayRotationAlternatesAntiBotWithOtherBackgroundStates() {
        val order = listOf(
            BotStage.GAME_START,
            BotStage.GAME_RELAY,
            BotStage.ANTI_BOT,
            BotStage.GAME_COMPLETE,
            BotStage.MAINMENU,
            BotStage.PURCHASE_ITEM,
            BotStage.LEVEL_UP,
        )
        val primary = setOf(
            BotStage.GAME_START,
            BotStage.GAME_RELAY,
            BotStage.GAME_COMPLETE,
        )
        var cursor = 0
        var antiBotTurn = true
        val seen = mutableListOf<BotStage>()

        repeat(6) {
            val selection = BackgroundDetectionPolicy.nextAfterGameRelay(
                orderedStages = order,
                cursor = cursor,
                primaryStages = primary,
                antiBotTurn = antiBotTurn,
            )
            cursor = selection.nextCursor
            antiBotTurn = selection.nextAntiBotTurn
            selection.stage?.let(seen::add)
        }

        assertEquals(
            listOf(
                BotStage.ANTI_BOT,
                BotStage.MAINMENU,
                BotStage.ANTI_BOT,
                BotStage.PURCHASE_ITEM,
                BotStage.ANTI_BOT,
                BotStage.LEVEL_UP,
            ),
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
