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
        listOf("/help", "/status", "/stats", "/last", "/boxes", "/all", "/friend", "/health",
            "/screen", "/watchdog", "/rounds", "/errors", "/dashboard", "/update").forEach { command ->
            assertEquals(command, parseTelegramCommand(command))
        }
        assertNull(parseTelegramCommand("/startbot"))
        assertNull(parseTelegramCommand("hello"))
    }

    @Test fun stripsBotUsernameSuffixAndIgnoresArguments() {
        assertEquals("/status", parseTelegramCommand("/status@RobotRunBot"))
        assertEquals("/stats", parseTelegramCommand("/stats@RobotRunBot extra"))
        assertEquals("/all", parseTelegramCommand("/all@RobotRunBot"))
        assertEquals("/friend", parseTelegramCommand("/friend@RobotRunBot"))
        assertEquals("/dashboard", parseTelegramCommand("/start"))
        assertEquals("/dashboard", parseTelegramCommand("/menu@RobotRunBot"))
        assertEquals("/update", parseTelegramCommand("/update@RobotRunBot"))
    }

    @Test fun configuredChatIdIsTheOnlyAuthorizedChat() {
        assertTrue(isAuthorizedTelegramChat("-100123456", "-100123456"))
        assertTrue(isAuthorizedTelegramChat(" 12345 ", "12345"))
        assertFalse(isAuthorizedTelegramChat("12346", "12345"))
        assertFalse(isAuthorizedTelegramChat("", "12345"))
    }

    @Test fun receiverBindsReplyTargetToSameConfigThatAuthorizedCommand() {
        val authorizingConfig = TelegramConfig(
            enabled = true,
            botToken = "token-a",
            chatId = "123",
        )
        val target = requireNotNull(telegramCommandReplyTarget("123", authorizingConfig))
        assertTrue(target.matches(authorizingConfig))
        assertFalse(target.matches(authorizingConfig.copy(chatId = "456")))
        assertFalse(target.matches(authorizingConfig.copy(botToken = "token-b")))
        assertNull(telegramCommandReplyTarget("456", authorizingConfig))
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

    @Test fun allCommandReturnsOneReplyInStatsStatusLastOrder() {
        val stats = statistics(rounds = 75, totalBoxes = 17).copy(
            lastRound = RoundResult(75, 0L, 252_000L, coins = 3_200, xp = 1_400, boxes = listOf("gold"), boxCount = 1),
        )
        val bot = stoppedBot()
        val progress = GameProgressWatchdog().status(100_000L)
        val combined = formatAllReply(stats, bot, progress)
        assertEquals(
            listOf(
                formatStatsReply(stats),
                formatStatusReply(bot, progress),
                formatLastRoundReply(stats.lastRound),
            ).joinToString("\n\n──────────\n\n"),
            combined,
        )
        assertTrue(combined.indexOf("📊 RobotRun Session") < combined.indexOf("🤖 RobotRun Status"))
        assertTrue(combined.indexOf("🤖 RobotRun Status") < combined.indexOf("🎮 Last Round"))
        assertTrue(combined.contains("Rounds: 75"))
        assertTrue(combined.contains("Coins: 3,200"))
    }

    @Test fun allCommandRetainsMissingLastRoundMessage() {
        val reply = formatAllReply(statistics(0, 0), stoppedBot(), GameProgressWatchdog().status(100_000L))
        assertTrue(reply.startsWith("📊 RobotRun Session"))
        assertTrue(reply.contains("🤖 RobotRun Status"))
        assertTrue(reply.endsWith("No completed rounds in this session yet."))
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
        calibrationBlockedAction = null,
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
