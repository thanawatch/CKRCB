package com.example.cookierunbot.notifications

import com.example.cookierunbot.automation.BotControllerDiagnostics
import com.example.cookierunbot.automation.GameProgressWatchdog
import com.example.cookierunbot.statistics.RoundResult
import com.example.cookierunbot.statistics.SessionStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramCommandReceiverTest {
    @Test fun parsesAllReadOnlyCommands() {
        listOf("/help", "/status", "/stats", "/last", "/boxes").forEach { command ->
            assertEquals(command, parseTelegramCommand(command))
        }
        assertNull(parseTelegramCommand("/startbot"))
        assertNull(parseTelegramCommand("hello"))
    }

    @Test fun stripsBotUsernameSuffixAndIgnoresArguments() {
        assertEquals("/status", parseTelegramCommand("/status@RobotRunBot"))
        assertEquals("/stats", parseTelegramCommand("/stats@RobotRunBot extra"))
    }

    @Test fun configuredChatIdIsTheOnlyAuthorizedChat() {
        assertTrue(isAuthorizedTelegramChat("-100123456", "-100123456"))
        assertTrue(isAuthorizedTelegramChat(" 12345 ", "12345"))
        assertFalse(isAuthorizedTelegramChat("12346", "12345"))
        assertFalse(isAuthorizedTelegramChat("", "12345"))
    }

    @Test fun consumedUpdateIdsAreNotProcessedAgain() {
        assertFalse(shouldProcessTelegramUpdate(42L, 42L))
        assertFalse(shouldProcessTelegramUpdate(42L, 41L))
        assertTrue(shouldProcessTelegramUpdate(42L, 43L))
    }

    @Test fun statisticsAndBoxRepliesUseCumulativeSnapshotValues() {
        val stats = statistics(rounds = 75, totalBoxes = 17)
        val sessionReply = formatStatsReply(stats)
        val boxReply = formatBoxesReply(stats)
        assertTrue(sessionReply.contains("Rounds: 75"))
        assertTrue(sessionReply.contains("Coins: 126,540"))
        assertTrue(boxReply.contains("Total: 17"))
        assertTrue(boxReply.contains("Rainbow: 1"))
    }

    @Test fun lastRoundDoesNotInventUnknownOcrValues() {
        val reply = formatLastRoundReply(
            RoundResult(84, 0L, 252_000L, coins = null, xp = null, boxes = listOf("gold"), boxCount = 1),
        )
        assertTrue(reply.contains("Coins: unknown"))
        assertTrue(reply.contains("XP: unknown"))
        assertTrue(reply.contains("Box types: gold"))
        assertEquals("No completed rounds in this session yet.", formatLastRoundReply(null))
    }

    @Test fun statusBeforeFirstBotStartDoesNotClaimBotStartProgress() {
        val reply = formatStatusReply(stoppedBot(), GameProgressWatchdog().status(100_000L))
        assertTrue(reply.contains("Bot: STOPPED"))
        assertTrue(reply.contains("Last progress: none"))
        assertTrue(reply.contains("Watchdog:\nStopped"))
        assertFalse(reply.contains("Last progress: BOT_START"))
    }

    private fun stoppedBot() = BotControllerDiagnostics(
        running = false,
        jobActive = false,
        actionInProgress = false,
        workerAlive = false,
        lastProcessedFrameSequence = -1L,
        lastDetectionAttemptAgeMs = null,
        group = "PRE_GAME",
        currentHandler = "IDLE",
        minimumFreshFrameGeneration = -1L,
        gestureBusy = false,
        pendingPopupAction = "NONE",
        friendLifeWorkflowActive = false,
        friendLifeLastProgressAgeMs = null,
        roundNumber = 0,
    )

    private fun statistics(rounds: Int, totalBoxes: Int) = SessionStatistics(
        rounds = rounds,
        totalCoins = 126_540,
        totalXp = 48_220,
        totalBoxes = totalBoxes,
        woodBoxes = 8,
        silverBoxes = 5,
        goldBoxes = 3,
        rainbowBoxes = 1,
        lastRound = null,
        history = emptyList(),
        sessionRuntimeMs = 1_000L,
    )
}
