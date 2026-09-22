package com.example.cookierunbot.automation

import com.example.cookierunbot.notifications.TelegramSendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorStallAcknowledgementTest {
    @Test fun localQueueAcceptanceDoesNotIncrementSuccessfulCount() {
        val watchdog = dueWatchdog()
        requireNotNull(watchdog.reserveNotification())
        assertEquals(0, watchdog.notificationCount())
        assertTrue(watchdog.notificationInFlight())
    }

    @Test fun http200ConfirmationCommitsNotification() {
        val watchdog = dueWatchdog()
        val result = complete(
            watchdog,
            requireNotNull(watchdog.reserveNotification()),
            TelegramSendResult(true, "success", 200),
            75_000L,
        )
        assertEquals(StallNotificationCompletion.CONFIRMED, result)
        assertEquals(1, watchdog.notificationCount())
        assertFalse(watchdog.notificationInFlight())
    }

    @Test fun http400DoesNotConsumeNotificationAndAppliesBackoff() = assertHttpFailure(400)

    @Test fun http401DoesNotConsumeNotificationAndAppliesBackoff() = assertHttpFailure(401)

    @Test fun http500DoesNotConsumeNotificationAndAppliesBackoff() = assertHttpFailure(500)

    @Test fun networkTimeoutDoesNotConsumeNotification() {
        val watchdog = dueWatchdog()
        val result = complete(
            watchdog,
            requireNotNull(watchdog.reserveNotification()),
            TelegramSendResult(false, "exception:SocketTimeoutException"),
            62_000L,
        )
        assertEquals(StallNotificationCompletion.FAILED, result)
        assertEquals(0, watchdog.notificationCount())
        assertFalse(watchdog.notificationInFlight())
    }

    @Test fun repeatedFailuresDoNotReduceFiveConfirmedSuccessLimit() {
        val watchdog = dueWatchdog()
        var reservation = requireNotNull(watchdog.reserveNotification())
        assertEquals(
            StallNotificationCompletion.FAILED,
            complete(watchdog, reservation, TelegramSendResult(false, "http_error", 400), 62_000L),
        )
        assertTrue(watchdog.observe(stalled(92_000L, 13L, 91_000L)).notificationDue)
        reservation = requireNotNull(watchdog.reserveNotification())
        assertEquals(
            StallNotificationCompletion.FAILED,
            complete(
                watchdog,
                reservation,
                TelegramSendResult(false, "exception:SocketTimeoutException"),
                92_000L,
            ),
        )
        assertTrue(watchdog.observe(stalled(122_000L, 14L, 121_000L)).notificationDue)
        reservation = requireNotNull(watchdog.reserveNotification())
        assertEquals(
            StallNotificationCompletion.FAILED,
            complete(watchdog, reservation, TelegramSendResult(false, "http_error", 500), 122_000L),
        )
        assertTrue(watchdog.observe(stalled(152_000L, 15L, 151_000L)).notificationDue)
        reservation = requireNotNull(watchdog.reserveNotification())
        assertEquals(
            StallNotificationCompletion.CONFIRMED,
            complete(watchdog, reservation, TelegramSendResult(true, "success", 200), 152_000L),
        )
        assertEquals(1, watchdog.notificationCount())
    }

    @Test fun staleSuccessCallbackCannotMutateNewEpisode() {
        val watchdog = dueWatchdog()
        val oldReservation = requireNotNull(watchdog.reserveNotification())
        resetAndStartNewEpisode(watchdog)
        assertEquals(
            StallNotificationCompletion.STALE,
            complete(watchdog, oldReservation, TelegramSendResult(true, "success", 200), 64_000L),
        )
        assertEquals(0, watchdog.notificationCount())
    }

    @Test fun staleFailureCallbackCannotAlterNewEpisodeRetryTimer() {
        val watchdog = dueWatchdog()
        val oldReservation = requireNotNull(watchdog.reserveNotification())
        resetAndStartNewEpisode(watchdog)
        assertEquals(
            StallNotificationCompletion.STALE,
            complete(watchdog, oldReservation, TelegramSendResult(false, "http_error", 500), 64_000L),
        )
        assertTrue(watchdog.observe(stalled(123_100L, 16L, 122_100L, processed = 6L)).notificationDue)
    }

    @Test fun secondCandidateIsBlockedWhileHttpResultIsPending() {
        val watchdog = dueWatchdog()
        requireNotNull(watchdog.reserveNotification())
        assertFalse(watchdog.observe(stalled(90_000L, 13L, 89_000L)).notificationDue)
        assertTrue(watchdog.reserveNotification() == null)
    }

    @Test fun repeatIntervalStartsAtHttpConfirmationTime() {
        val watchdog = dueWatchdog()
        val reservation = requireNotNull(watchdog.reserveNotification())
        complete(watchdog, reservation, TelegramSendResult(true, "success", 200), 75_000L)
        assertFalse(watchdog.observe(stalled(194_999L, 13L, 193_999L)).notificationDue)
        assertTrue(watchdog.observe(stalled(195_000L, 14L, 194_000L)).notificationDue)
    }

    private fun assertHttpFailure(code: Int) {
        val watchdog = dueWatchdog()
        val result = complete(
            watchdog,
            requireNotNull(watchdog.reserveNotification()),
            TelegramSendResult(false, "http_error", code),
            62_000L,
        )
        assertEquals(StallNotificationCompletion.FAILED, result)
        assertEquals(0, watchdog.notificationCount())
        assertFalse(watchdog.notificationInFlight())
        assertFalse(watchdog.observe(stalled(91_999L, 13L, 90_999L)).notificationDue)
        assertTrue(watchdog.observe(stalled(92_000L, 14L, 91_000L)).notificationDue)
    }

    private fun complete(
        watchdog: DetectorStallWatchdog,
        reservation: StallNotificationReservation,
        result: TelegramSendResult,
        nowMs: Long,
    ) = completeStallNotification(watchdog, reservation, nowMs, result)

    private fun dueWatchdog(): DetectorStallWatchdog = DetectorStallWatchdog().also { watchdog ->
        watchdog.observe(stalled(1_000L, 10L, 0L))
        watchdog.observe(stalled(2_000L, 11L, 1_000L))
        assertTrue(watchdog.observe(stalled(62_000L, 12L, 61_000L)).notificationDue)
    }

    private fun resetAndStartNewEpisode(watchdog: DetectorStallWatchdog) {
        watchdog.observe(stalled(63_000L, 13L, 0L, processed = 6L))
        watchdog.observe(stalled(63_100L, 14L, 60_000L, processed = 6L))
    }

    private fun stalled(
        now: Long,
        capture: Long,
        detectionAge: Long,
        processed: Long = 5L,
    ) = DetectorStallObservation(
        nowMs = now,
        botRunning = true,
        workerAlive = true,
        captureRunning = true,
        captureFrameSequence = capture,
        lastProcessedFrameSequence = processed,
        lastDetectionAttemptAgeMs = detectionAge,
        group = "PRE_GAME",
        friendLifeActive = false,
        friendLifeLastProgressAgeMs = null,
    )
}
