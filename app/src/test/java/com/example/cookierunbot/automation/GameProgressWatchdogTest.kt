package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProgressWatchdogTest {
    @Test fun noAlertBeforeFifteenMinutes() {
        val watchdog = GameProgressWatchdog()
        watchdog.start(0L)
        assertNull(watchdog.reserveIfDue(GAME_PROGRESS_TIMEOUT_MS - 1L))
    }

    @Test fun watchdogReportsNeverStartedBeforeFirstStart() {
        val status = GameProgressWatchdog().status(123_000L)
        assertFalse(status.running)
        assertFalse(status.hasStarted)
        assertEquals(0L, status.ageMs)
    }

    @Test fun alertIsDueAtFifteenMinutes() {
        val watchdog = GameProgressWatchdog()
        watchdog.start(0L)
        assertNotNull(watchdog.reserveIfDue(GAME_PROGRESS_TIMEOUT_MS))
    }

    @Test fun gameCompleteResetsTimer() {
        val watchdog = GameProgressWatchdog()
        watchdog.start(0L)
        watchdog.recordProgress(GameProgressReason.GAME_COMPLETE, GAME_PROGRESS_TIMEOUT_MS - 1L)
        assertNull(watchdog.reserveIfDue(GAME_PROGRESS_TIMEOUT_MS * 2L - 2L))
        assertNotNull(watchdog.reserveIfDue(GAME_PROGRESS_TIMEOUT_MS * 2L - 1L))
    }

    @Test fun successfulFriendSendTapResetsTimerImmediately() {
        val watchdog = GameProgressWatchdog()
        watchdog.start(0L)
        watchdog.recordProgress(GameProgressReason.FRIEND_SEND_LIFE_TAP, 100L)
        assertEquals(GameProgressReason.FRIEND_SEND_LIFE_TAP, watchdog.status(100L).lastProgressReason)
        assertNull(watchdog.reserveIfDue(GAME_PROGRESS_TIMEOUT_MS))
    }

    @Test fun failedFriendSendDoesNotResetTimer() {
        val watchdog = GameProgressWatchdog()
        watchdog.start(0L)
        // A failed/rejected gesture never calls recordProgress().
        assertNotNull(watchdog.reserveIfDue(GAME_PROGRESS_TIMEOUT_MS))
    }

    @Test fun onlySuccessfulSendButtonGestureProducesFriendProgressReason() {
        assertEquals(
            GameProgressReason.FRIEND_SEND_LIFE_TAP,
            friendSendTapProgressReason("SEND_BUTTON", gestureSucceeded = true),
        )
        assertNull(friendSendTapProgressReason("SEND_BUTTON", gestureSucceeded = false))
        assertNull(friendSendTapProgressReason("CONFIRM_1", gestureSucceeded = true))
        assertNull(friendSendTapProgressReason("CONFIRM_2", gestureSucceeded = true))
    }

    @Test fun successfulDeliveryAllowsOnlyOneAlertForEpisode() {
        val watchdog = GameProgressWatchdog()
        watchdog.start(0L)
        val reservation = requireNotNull(watchdog.reserveIfDue(GAME_PROGRESS_TIMEOUT_MS))
        assertTrue(watchdog.confirmDelivered(reservation))
        assertNull(watchdog.reserveIfDue(GAME_PROGRESS_TIMEOUT_MS * 5L))
        assertTrue(watchdog.status(GAME_PROGRESS_TIMEOUT_MS * 5L).alertDelivered)
    }

    @Test fun progressAfterAlertStartsNewEpisode() {
        val watchdog = GameProgressWatchdog()
        watchdog.start(0L)
        watchdog.confirmDelivered(requireNotNull(watchdog.reserveIfDue(GAME_PROGRESS_TIMEOUT_MS)))
        watchdog.recordProgress(GameProgressReason.GAME_COMPLETE, GAME_PROGRESS_TIMEOUT_MS + 1L)
        assertFalse(watchdog.status(GAME_PROGRESS_TIMEOUT_MS + 1L).alertDelivered)
        assertNotNull(watchdog.reserveIfDue(GAME_PROGRESS_TIMEOUT_MS * 2L + 1L))
    }

    @Test fun failedDeliveryUsesFiveMinuteBackoffWithoutConsumingEpisode() {
        val watchdog = GameProgressWatchdog()
        watchdog.start(0L)
        val failedAt = GAME_PROGRESS_TIMEOUT_MS
        val reservation = requireNotNull(watchdog.reserveIfDue(failedAt))
        assertTrue(watchdog.failAttempt(reservation, failedAt))
        assertNull(watchdog.reserveIfDue(failedAt + GAME_PROGRESS_FAILED_RETRY_MS - 1L))
        assertNotNull(watchdog.reserveIfDue(failedAt + GAME_PROGRESS_FAILED_RETRY_MS))
    }

    @Test fun stoppedBotCannotAlert() {
        val watchdog = GameProgressWatchdog()
        watchdog.start(0L)
        watchdog.stop()
        assertNull(watchdog.reserveIfDue(GAME_PROGRESS_TIMEOUT_MS * 2L))
    }

    @Test fun staleDeliveryCallbackCannotMarkNewEpisodeDelivered() {
        val watchdog = GameProgressWatchdog()
        watchdog.start(0L)
        val old = requireNotNull(watchdog.reserveIfDue(GAME_PROGRESS_TIMEOUT_MS))
        watchdog.recordProgress(GameProgressReason.FRIEND_SEND_LIFE_TAP, GAME_PROGRESS_TIMEOUT_MS + 1L)
        assertFalse(watchdog.confirmDelivered(old))
        assertFalse(watchdog.status(GAME_PROGRESS_TIMEOUT_MS + 1L).alertDelivered)
    }

    @Test fun freshDeliveryCallbackCommitsCurrentEpisode() {
        val watchdog = GameProgressWatchdog()
        watchdog.start(0L)
        val current = requireNotNull(watchdog.reserveIfDue(GAME_PROGRESS_TIMEOUT_MS))
        assertTrue(watchdog.confirmDelivered(current))
        assertTrue(watchdog.status(GAME_PROGRESS_TIMEOUT_MS).alertDelivered)
    }

    @Test fun telegram429CannotShortenFiveMinuteWatchdogBackoff() {
        val watchdog = GameProgressWatchdog()
        watchdog.start(0L)
        val failedAt = GAME_PROGRESS_TIMEOUT_MS
        val reservation = requireNotNull(watchdog.reserveIfDue(failedAt))
        watchdog.failAttempt(reservation, failedAt, retryDelayMs = 12_000L)
        assertNull(watchdog.reserveIfDue(failedAt + GAME_PROGRESS_FAILED_RETRY_MS - 1L))
        assertNotNull(watchdog.reserveIfDue(failedAt + GAME_PROGRESS_FAILED_RETRY_MS))
    }

    @Test fun telegram429LongerThanSafetyBackoffIsRespected() {
        val watchdog = GameProgressWatchdog()
        watchdog.start(0L)
        val failedAt = GAME_PROGRESS_TIMEOUT_MS
        val telegramRetry = 10L * 60L * 1000L
        val reservation = requireNotNull(watchdog.reserveIfDue(failedAt))
        watchdog.failAttempt(reservation, failedAt, retryDelayMs = telegramRetry)
        assertNull(watchdog.reserveIfDue(failedAt + telegramRetry - 1L))
        assertNotNull(watchdog.reserveIfDue(failedAt + telegramRetry))
    }
}
