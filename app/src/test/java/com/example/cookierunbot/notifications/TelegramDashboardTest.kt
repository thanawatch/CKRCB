package com.example.cookierunbot.notifications

import com.example.cookierunbot.automation.BotControllerDiagnostics
import com.example.cookierunbot.automation.FriendLifeDashboardStatus
import com.example.cookierunbot.automation.GameProgressWatchdog
import com.example.cookierunbot.capture.Diagnostics
import com.example.cookierunbot.statistics.RoundResult
import com.example.cookierunbot.statistics.SessionStatistics
import com.example.cookierunbot.update.UpdateDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramDashboardTest {
    @Test fun dashboardAllButtonAndRefreshUseExistingAllCommand() {
        assertEquals("/all", parseTelegramCommand(TelegramDashboardPolicy.ALL))
        assertEquals("/all", parseTelegramCommand(TelegramDashboardPolicy.REFRESH))
        assertEquals("/screen", parseTelegramCommand(TelegramDashboardPolicy.SCREEN))
        assertEquals("/friend", parseTelegramCommand(TelegramDashboardPolicy.FRIEND))
        assertEquals("/health", parseTelegramCommand(TelegramDashboardPolicy.HEALTH))
        assertEquals("/watchdog", parseTelegramCommand(TelegramDashboardPolicy.WATCHDOG))
        assertEquals("/rounds", parseTelegramCommand(TelegramDashboardPolicy.ROUNDS))
        assertEquals("/errors", parseTelegramCommand(TelegramDashboardPolicy.ERRORS))
        assertEquals("/dashboard", parseTelegramCommand(TelegramDashboardPolicy.DASHBOARD))
        assertEquals("/update", parseTelegramCommand(TelegramDashboardPolicy.UPDATE))
        assertEquals("/logs", parseTelegramCommand(TelegramDashboardPolicy.LOGS))
        assertEquals("/logs", parseTelegramCommand("/logs"))
        assertNull(parseTelegramCommand("Stop Bot"))
    }

    @Test fun allKeepsTheCompleteSummaryWhenScreenIsUnavailable() {
        val summary = listOf("📊 RobotRun Session", "🤖 RobotRun Status", "🎮 Last Round")
            .joinToString("\n\n")
        assertEquals(summary, formatAllWithScreenNote(summary, null))
        val reply = formatAllWithScreenNote(summary, "📸 Screen: capture is not running.")
        assertTrue(reply.startsWith("📸 Screen: capture is not running."))
        assertTrue(reply.endsWith(summary))
        assertTrue(reply.indexOf("📊 RobotRun Session") < reply.indexOf("🤖 RobotRun Status"))
        assertTrue(reply.indexOf("🤖 RobotRun Status") < reply.indexOf("🎮 Last Round"))
    }

    @Test fun dashboardKeyboardIsPersistentAndHelpListsAllCommands() {
        val markup = TelegramDashboardPolicy.keyboardJson
        assertTrue(markup.contains("\"is_persistent\":true"))
        assertTrue(markup.contains("\"one_time_keyboard\":false"))
        assertTrue(markup.contains("📊 All"))
        assertTrue(markup.contains("📸 Screen"))
        assertTrue(markup.contains("📦 Update App"))
        assertTrue(markup.contains("📥 Download Logs"))
        assertTrue(requireNotNull(telegramCommandReply("/help")).contains("/all"))
        assertTrue(requireNotNull(telegramCommandReply("/help")).contains("/friend"))
        assertTrue(requireNotNull(telegramCommandReply("/help")).contains("/screen"))
        assertTrue(requireNotNull(telegramCommandReply("/help")).contains("/update"))
        assertTrue(requireNotNull(telegramCommandReply("/help")).contains("/logs"))
        assertTrue(requireNotNull(telegramCommandReply("/dashboard")).contains("Tap a button below"))
    }

    @Test fun updateButtonReportsConfiguredFeedAvailabilityWithoutInstalling() {
        val noFeed = formatTelegramUpdateReply("1.1.1", 3L, null, false)
        assertTrue(noFeed.contains("not configured"))
        assertTrue(noFeed.contains("Update App"))
        val update = UpdateDescriptor(
            packageName = "com.example.cookierunbot", versionCode = 4,
            versionName = "1.1.2", apkUrl = "https://updates.example.com/robotrun.apk",
            sha256 = "ab".repeat(32), notes = "Fixes and improvements",
        )
        val available = formatTelegramUpdateReply("1.1.1", 3L, update, true)
        assertTrue(available.contains("New update available"))
        assertTrue(available.contains("1.1.2 (code 4)"))
        assertTrue(available.contains("Fixes and improvements"))
        assertFalse(available.contains(update.apkUrl))
        assertTrue(formatTelegramUpdateReply("1.1.2", 4L, update, true).contains("up to date"))
        assertTrue(formatTelegramUpdateReply("1.1.1", 3L, null, true, true).contains("Could not check"))
    }

    @Test fun screenshotRequiresRecentPostCaptureTimestamp() {
        assertFalse(TelegramDashboardPolicy.screenshotFrameIsFresh(0L, 1_000L))
        assertFalse(TelegramDashboardPolicy.screenshotFrameIsFresh(2_000L, 1_000L))
        assertTrue(TelegramDashboardPolicy.screenshotFrameIsFresh(1_000L, 1_000_001_000L))
        assertFalse(TelegramDashboardPolicy.screenshotFrameIsFresh(1_000L, 1_000_001_001L))
    }

    @Test fun friendReplyShowsUserIntervalDueStateAndSentCount() {
        val status = FriendLifeDashboardStatus(
            botRunning = true, enabled = true, intervalMinutes = 7,
            scheduledPending = true, testPending = false, inProgress = false,
            nextDueInMs = 0L, retryInMs = 0L, currentSentCount = 0,
            lastSentCount = 12, lastOutcome = "COMPLETED", lastProgressAgeMs = 2_000L,
        )
        val reply = formatFriendReply(status)
        assertTrue(reply.contains("Interval: 7 minutes"))
        assertTrue(reply.contains("WAITING_FOR_MAINMENU"))
        assertTrue(reply.contains("Last run: COMPLETED (sent 12)"))
    }

    @Test fun healthReplyShowsCaptureAndAccessibilityWithoutUntrustedErrorText() {
        val capture = Diagnostics(
            captureRunning = true, frameSequence = 110L, sourceWidth = 2400,
            sourceHeight = 1080, normalizedWidth = 1280, normalizedHeight = 720,
            frameAgeMs = 85.0, captureFps = 21.5, detection = null,
            error = "sensitive arbitrary error text",
        )
        val reply = formatHealthReply(bot(), capture, true, false)
        assertTrue(reply.contains("Frame age: 85ms"))
        assertTrue(reply.contains("Capture FPS: 21.5"))
        assertTrue(reply.contains("Accessibility connected: false"))
        assertFalse(reply.contains("sensitive arbitrary error text"))
    }

    @Test fun watchdogAndRecoveryLogAreReadOnlySummaries() {
        val status = GameProgressWatchdog().status(100_000L)
        val bot = bot().copy(progressRecoveryActive = true)
        val log = "[2026-09-27] [GAME_PROGRESS_RECOVERY] freshGeneration=101 resumed_detector=true"
        val reply = formatWatchdogReply(status, bot, latestRecoverySummary(log))
        assertTrue(reply.contains("Recovery active: true"))
        assertTrue(reply.contains("resumed_detector=true"))
        assertTrue(reply.contains("Last progress: none"))
    }

    @Test fun roundsKeepsLatestTenAndShowsBoxTypesWithoutInventingMissingTypes() {
        val rounds = (1..11).reversed().map { number ->
            RoundResult(
                number, 0L, 5_000L,
                coins = if (number == 11) null else 10,
                xp = 20,
                boxes = when (number) {
                    11 -> listOf("wood", "silver")
                    10 -> emptyList()
                    else -> listOf("gold")
                },
                boxCount = if (number == 11) 2 else 1,
            )
        }
        val reply = formatRoundsReply(stats(rounds))
        assertTrue(reply.contains("Recent Rounds (latest 10)"))
        assertTrue(reply.contains("#11: Coins unknown"))
        assertTrue(reply.contains("Boxes: 2 | Types: wood, silver"))
        assertTrue(reply.contains("#10: Coins 10"))
        assertTrue(reply.contains("Boxes: 1 | Types: unknown"))
        assertTrue(reply.contains("#2:"))
        assertFalse(reply.contains("#1:"))
        assertTrue(formatRoundsReply(stats(emptyList())).contains("No completed rounds"))
    }

    @Test fun errorSummaryOnlyReturnsWhitelistedEventsAndAtMostFive() {
        val text = buildString {
            appendLine("[2026] [TELEGRAM_CONFIG] botToken=should_not_appear")
            repeat(6) { index -> appendLine("[2026] [FRIEND_LIFE_ABORT] reason=timeout" + index) }
        }
        val reply = formatErrorsReply(text)
        assertTrue(reply.contains("latest 5"))
        assertFalse(reply.contains("botToken"))
        assertFalse(reply.contains("timeout0"))
        assertTrue(reply.contains("timeout5"))
    }

    private fun stats(history: List<RoundResult>) = SessionStatistics(
        rounds = history.size, totalCoins = 0, totalXp = 0, totalBoxes = 0,
        woodBoxes = 0, silverBoxes = 0, goldBoxes = 0, rainbowBoxes = 0,
        lastRound = history.firstOrNull(), history = history, sessionRuntimeMs = 0L,
    )

    private fun bot() = BotControllerDiagnostics(
        running = true, jobActive = true, actionInProgress = false, workerAlive = true,
        lastProcessedFrameSequence = 100L, lastDetectionAttemptAgeMs = 500L,
        group = "IN_GAME", currentHandler = "GAME_START",
        minimumFreshFrameGeneration = -1L, gestureBusy = false,
        pendingPopupAction = "NONE", calibrationBlockedAction = null,
        friendLifeWorkflowActive = false, friendLifeLastProgressAgeMs = null, roundNumber = 1,
    )
}
