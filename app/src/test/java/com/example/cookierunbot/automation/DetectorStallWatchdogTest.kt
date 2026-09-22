package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorStallWatchdogTest {
    @Test fun advancingCaptureWithFrozenDetectorBecomesEligibleAtSixtySeconds() {
        val watchdog = DetectorStallWatchdog()
        warm(watchdog)
        assertTrue(watchdog.observe(stalled(2_000L, 11L, 1_000L)).episodeStarted)
        assertFalse(watchdog.observe(stalled(61_999L, 12L, 60_999L)).notificationDue)
        val due = watchdog.observe(stalled(62_000L, 13L, 61_000L))
        assertTrue(due.notificationDue)
        assertEquals(1, due.notificationIndex)
    }

    @Test fun recentFriendLifeProgressSuppressesFalseStall() {
        val watchdog = DetectorStallWatchdog()
        warm(watchdog)
        watchdog.observe(stalled(2_000L, 11L, 90_000L, friendActive = true, friendAge = 1_000L))
        val decision = watchdog.observe(
            stalled(70_000L, 12L, 90_000L, friendActive = true, friendAge = 5_000L),
        )
        assertTrue(decision.suppressedByHealthyFriendLife)
        assertFalse(decision.notificationDue)
    }

    @Test fun staleFriendLifeProgressAllowsTrueStallDiagnostic() {
        val watchdog = DetectorStallWatchdog()
        warm(watchdog)
        watchdog.observe(stalled(2_000L, 11L, 90_000L, friendActive = true, friendAge = 1_000L))
        val decision = watchdog.observe(
            stalled(70_000L, 12L, 90_000L, friendActive = true, friendAge = 45_000L),
        )
        assertTrue(decision.notificationDue)
        assertFalse(decision.suppressedByHealthyFriendLife)
    }

    @Test fun repeatNotificationWaitsOneHundredTwentySeconds() {
        val watchdog = DetectorStallWatchdog()
        warm(watchdog)
        watchdog.observe(stalled(2_000L, 11L, 1_000L))
        val first = watchdog.observe(stalled(62_000L, 12L, 61_000L))
        assertTrue(first.notificationDue)
        val firstReservation = requireNotNull(watchdog.reserveNotification())
        assertTrue(watchdog.commitNotificationConfirmed(firstReservation, 62_000L))
        assertFalse(watchdog.observe(stalled(181_999L, 13L, 180_000L)).notificationDue)
        val second = watchdog.observe(stalled(182_000L, 14L, 181_000L))
        assertTrue(second.notificationDue)
        assertEquals(2, second.notificationIndex)
    }

    @Test fun sameEpisodeStopsAfterFiveNotifications() {
        val watchdog = DetectorStallWatchdog(firstNotificationDelayMs = 10L, repeatIntervalMs = 20L)
        warm(watchdog)
        watchdog.observe(stalled(2_000L, 11L, 60_000L))
        var now = 2_010L
        repeat(TRUE_STALL_MAX_NOTIFICATIONS) { index ->
            val due = watchdog.observe(stalled(now, 12L + index, 60_000L + now))
            assertTrue(due.notificationDue)
            assertEquals(index + 1, due.notificationIndex)
            val reservation = requireNotNull(watchdog.reserveNotification())
            assertTrue(watchdog.commitNotificationConfirmed(reservation, now))
            now += 20L
        }
        assertFalse(watchdog.observe(stalled(now, 30L, 90_000L)).notificationDue)
        assertEquals(TRUE_STALL_MAX_NOTIFICATIONS, watchdog.notificationCount())
    }

    @Test fun detectorProgressResetsEpisodeAndLaterStallCanStartAgain() {
        val watchdog = DetectorStallWatchdog(firstNotificationDelayMs = 10L)
        warm(watchdog)
        watchdog.observe(stalled(2_000L, 11L, 60_000L))
        val due = watchdog.observe(stalled(2_010L, 12L, 60_010L))
        assertTrue(due.notificationDue)
        val reservation = requireNotNull(watchdog.reserveNotification())
        assertTrue(watchdog.commitNotificationConfirmed(reservation, 2_010L))

        val resumed = watchdog.observe(stalled(2_020L, 13L, 0L).copy(lastProcessedFrameSequence = 6L))
        assertEquals("detector_progress_resumed", resumed.resetReason)
        assertEquals(0, watchdog.notificationCount())
        assertTrue(watchdog.observe(stalled(2_030L, 14L, 60_000L).copy(lastProcessedFrameSequence = 6L)).episodeStarted)
    }

    @Test fun stoppedBotAndCaptureResetAnyEpisode() {
        val watchdog = DetectorStallWatchdog()
        warm(watchdog)
        watchdog.observe(stalled(2_000L, 11L, 60_000L))
        val stopped = stalled(3_000L, 12L, 61_000L).copy(botRunning = false)
        assertEquals("bot_stopped", watchdog.observe(stopped).resetReason)
    }

    @Test fun failedAttemptDoesNotConsumeNotification() {
        val watchdog = dueWatchdog()
        val reservation = requireNotNull(watchdog.reserveNotification())
        assertTrue(watchdog.releaseNotificationReservation(reservation, 62_000L))
        assertEquals(0, watchdog.notificationCount())
        assertFalse(watchdog.notificationInFlight())
    }

    @Test fun confirmedTelegramSendCommitsExactlyOneNotification() {
        val watchdog = dueWatchdog()
        val reservation = requireNotNull(watchdog.reserveNotification())
        assertTrue(watchdog.commitNotificationConfirmed(reservation, 62_000L))
        assertEquals(1, watchdog.notificationCount())
        assertFalse(watchdog.notificationInFlight())
    }

    @Test fun pngEncodingFailureReleasesReservationWithoutConsumingNotification() {
        val watchdog = dueWatchdog()
        val reservation = requireNotNull(watchdog.reserveNotification())
        // The manager uses this release path from its PNG encode exception handler.
        assertTrue(watchdog.releaseNotificationReservation(reservation, 62_000L))
        assertEquals(0, watchdog.notificationCount())
        assertFalse(watchdog.notificationInFlight())
    }

    @Test fun missingLatestFrameReleasesReservationWithoutConsumingNotification() {
        val watchdog = dueWatchdog()
        val reservation = requireNotNull(watchdog.reserveNotification())
        // The manager uses this release path when LatestFrameProvider.snapshot() is null.
        assertTrue(watchdog.releaseNotificationReservation(reservation, 62_000L))
        assertEquals(0, watchdog.notificationCount())
        assertFalse(watchdog.notificationInFlight())
    }

    @Test fun onlyOneNotificationCanBeInFlightForAnEpisode() {
        val watchdog = dueWatchdog()
        assertTrue(watchdog.reserveNotification() != null)
        assertTrue(watchdog.notificationInFlight())
        assertTrue(watchdog.reserveNotification() == null)
        assertFalse(watchdog.observe(stalled(67_000L, 14L, 66_000L)).notificationDue)
    }

    @Test fun failedAttemptDoesNotReduceFiveSuccessfulNotificationLimit() {
        val watchdog = DetectorStallWatchdog(firstNotificationDelayMs = 10L, repeatIntervalMs = 20L)
        warm(watchdog)
        watchdog.observe(stalled(2_000L, 11L, 60_000L))
        assertTrue(watchdog.observe(stalled(2_010L, 12L, 60_010L)).notificationDue)
        val failed = requireNotNull(watchdog.reserveNotification())
        watchdog.releaseNotificationReservation(failed, 2_010L, retryDelayMs = 0L)
        assertEquals(0, watchdog.notificationCount())

        var now = 2_011L
        repeat(TRUE_STALL_MAX_NOTIFICATIONS) { expected ->
            assertTrue(watchdog.observe(stalled(now, 13L + expected, 61_000L)).notificationDue)
            val reservation = requireNotNull(watchdog.reserveNotification())
            assertTrue(watchdog.commitNotificationConfirmed(reservation, now))
            assertEquals(expected + 1, watchdog.notificationCount())
            now += 20L
        }
        assertFalse(watchdog.observe(stalled(now, 30L, 90_000L)).notificationDue)
    }

    private fun dueWatchdog(): DetectorStallWatchdog = DetectorStallWatchdog().also { watchdog ->
        warm(watchdog)
        watchdog.observe(stalled(2_000L, 11L, 1_000L))
        assertTrue(watchdog.observe(stalled(62_000L, 12L, 61_000L)).notificationDue)
    }

    private fun warm(watchdog: DetectorStallWatchdog) {
        watchdog.observe(stalled(1_000L, 10L, 0L))
    }

    private fun stalled(
        now: Long,
        captureSequence: Long,
        detectionAge: Long,
        friendActive: Boolean = false,
        friendAge: Long? = null,
    ) = DetectorStallObservation(
        nowMs = now,
        botRunning = true,
        workerAlive = true,
        captureRunning = true,
        captureFrameSequence = captureSequence,
        lastProcessedFrameSequence = 5L,
        lastDetectionAttemptAgeMs = detectionAge,
        group = "PRE_GAME",
        friendLifeActive = friendActive,
        friendLifeLastProgressAgeMs = friendAge,
    )
}
