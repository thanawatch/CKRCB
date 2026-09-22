package com.example.cookierunbot.automation

const val TRUE_STALL_NOTIFICATION_THRESHOLD_MS = 60_000L
const val TRUE_STALL_REPEAT_INTERVAL_MS = 120_000L
const val TRUE_STALL_MAX_NOTIFICATIONS = 5
const val FRIEND_LIFE_HEALTHY_PROGRESS_WINDOW_MS = 30_000L

data class DetectorStallObservation(
    val nowMs: Long,
    val botRunning: Boolean,
    val workerAlive: Boolean,
    val captureRunning: Boolean,
    val captureFrameSequence: Long,
    val lastProcessedFrameSequence: Long,
    val lastDetectionAttemptAgeMs: Long?,
    val group: String,
    val friendLifeActive: Boolean,
    val friendLifeLastProgressAgeMs: Long?,
)

data class DetectorStallDecision(
    val episodeStarted: Boolean = false,
    val resetReason: String? = null,
    val suppressedByHealthyFriendLife: Boolean = false,
    val notificationDue: Boolean = false,
    val notificationIndex: Int = 0,
    val stallDurationMs: Long = 0L,
)

/** Thread-safe scheduling state only. It never captures frames or performs network I/O. */
class DetectorStallWatchdog(
    private val firstNotificationDelayMs: Long = TRUE_STALL_NOTIFICATION_THRESHOLD_MS,
    private val repeatIntervalMs: Long = TRUE_STALL_REPEAT_INTERVAL_MS,
    private val maximumNotifications: Int = TRUE_STALL_MAX_NOTIFICATIONS,
    private val healthyFriendLifeWindowMs: Long = FRIEND_LIFE_HEALTHY_PROGRESS_WINDOW_MS,
) {
    private var previousCaptureSequence = -1L
    private var previousProcessedSequence = -1L
    private var previousGroup: String? = null
    private var episodeStartedAt = 0L
    private var notifications = 0
    private var lastNotificationAt = 0L

    @Synchronized
    fun observe(observation: DetectorStallObservation): DetectorStallDecision {
        val groupChanged = previousGroup != null && previousGroup != observation.group
        val detectorProgressResumed = previousProcessedSequence >= 0L &&
            observation.lastProcessedFrameSequence != previousProcessedSequence
        val resetReason = when {
            !observation.botRunning -> "bot_stopped"
            !observation.workerAlive -> "worker_not_alive"
            !observation.captureRunning -> "capture_not_running"
            groupChanged -> "detection_group_changed"
            detectorProgressResumed -> "detector_progress_resumed"
            else -> null
        }
        val episodeWasActive = episodeStartedAt != 0L
        if (resetReason != null) resetEpisode()

        val captureAdvanced = previousCaptureSequence >= 0L &&
            observation.captureFrameSequence > previousCaptureSequence
        val detectorUnchanged = previousProcessedSequence >= 0L &&
            observation.lastProcessedFrameSequence == previousProcessedSequence
        previousCaptureSequence = observation.captureFrameSequence
        previousProcessedSequence = observation.lastProcessedFrameSequence
        previousGroup = observation.group

        if (resetReason != null) {
            return DetectorStallDecision(resetReason = resetReason.takeIf { episodeWasActive })
        }
        if (!captureAdvanced || !detectorUnchanged) {
            val reason = if (episodeStartedAt != 0L) "stall_condition_cleared" else null
            if (reason != null) resetEpisode()
            return DetectorStallDecision(resetReason = reason)
        }

        val started = episodeStartedAt == 0L
        if (started) episodeStartedAt = observation.nowMs
        val duration = (observation.nowMs - episodeStartedAt).coerceAtLeast(0L)
        val healthyFriendLife = observation.friendLifeActive &&
            observation.friendLifeLastProgressAgeMs != null &&
            observation.friendLifeLastProgressAgeMs <= healthyFriendLifeWindowMs
        if (healthyFriendLife) {
            return DetectorStallDecision(
                episodeStarted = started,
                suppressedByHealthyFriendLife = true,
                stallDurationMs = duration,
            )
        }

        val detectionOldEnough = (observation.lastDetectionAttemptAgeMs ?: 0L) >=
            TRUE_STALL_NOTIFICATION_THRESHOLD_MS
        val intervalElapsed = notifications == 0 ||
            observation.nowMs - lastNotificationAt >= repeatIntervalMs
        val due = detectionOldEnough && duration >= firstNotificationDelayMs &&
            notifications < maximumNotifications && intervalElapsed
        return DetectorStallDecision(
            episodeStarted = started,
            notificationDue = due,
            notificationIndex = if (due) notifications + 1 else 0,
            stallDurationMs = duration,
        )
    }

    @Synchronized
    fun markNotificationQueued(nowMs: Long) {
        if (episodeStartedAt == 0L || notifications >= maximumNotifications) return
        notifications += 1
        lastNotificationAt = nowMs
    }

    @Synchronized
    fun reset(): Boolean {
        val active = episodeStartedAt != 0L
        resetEpisode()
        previousCaptureSequence = -1L
        previousProcessedSequence = -1L
        previousGroup = null
        return active
    }

    @Synchronized fun notificationCount(): Int = notifications

    private fun resetEpisode() {
        episodeStartedAt = 0L
        notifications = 0
        lastNotificationAt = 0L
    }
}
