package com.example.cookierunbot.automation

enum class FriendLifeTrigger { SCHEDULED, TEST, SCHEDULED_AND_TEST }
enum class FriendLifeTestRequest { BOT_STOPPED, QUEUED, ALREADY_QUEUED, IN_PROGRESS }
enum class FriendLifeTraversalDecision {
    SEND, COMPLETE, SCROLL_DOWN, ABORT_NO_PROGRESS, ABORT_ABSOLUTE_TIMEOUT,
}
internal enum class FriendLifeTopSearchDecision { TOP_FOUND, SWIPE, CONTINUE_TRAVERSAL }
internal enum class FriendLifeSendStep { SEND_BUTTON, CONFIRM_1, CONFIRM_2, COMPLETE }

internal data class FriendLifeSendDecision(
    val nextStep: FriendLifeSendStep?,
    val interrupt: BotStage?,
)

internal fun friendLifeSendDecision(
    completedStep: FriendLifeSendStep,
    interrupt: BotStage?,
): FriendLifeSendDecision {
    if (interrupt != null) return FriendLifeSendDecision(null, interrupt)
    val next = when (completedStep) {
        FriendLifeSendStep.SEND_BUTTON -> FriendLifeSendStep.CONFIRM_1
        FriendLifeSendStep.CONFIRM_1 -> FriendLifeSendStep.CONFIRM_2
        FriendLifeSendStep.CONFIRM_2 -> FriendLifeSendStep.COMPLETE
        FriendLifeSendStep.COMPLETE -> null
    }
    return FriendLifeSendDecision(next, null)
}

class FriendLifeScheduler(private var intervalMs: Long = FRIEND_LIFE_INTERVAL_MS) {
    init { require(intervalMs in FRIEND_LIFE_MIN_INTERVAL_MS..FRIEND_LIFE_MAX_INTERVAL_MS) }
    private var enabled = false
    private var nextDueAt = Long.MAX_VALUE
    private var scheduledPending = false
    private var testPending = false
    private var inProgress = false
    private var retryNotBefore = 0L

    @Synchronized fun start(
        enableSchedule: Boolean,
        nowMs: Long,
        configuredIntervalMs: Long = intervalMs,
    ) {
        require(configuredIntervalMs in FRIEND_LIFE_MIN_INTERVAL_MS..FRIEND_LIFE_MAX_INTERVAL_MS)
        intervalMs = configuredIntervalMs
        enabled = enableSchedule
        nextDueAt = if (enableSchedule) nowMs + intervalMs else Long.MAX_VALUE
        scheduledPending = false
        testPending = false
        inProgress = false
        retryNotBefore = 0L
    }

    @Synchronized fun stop() = start(false, 0L)

    /** Returns true only when the scheduled request becomes newly pending. */
    @Synchronized fun observeTime(nowMs: Long): Boolean {
        if (!enabled || scheduledPending || nowMs < nextDueAt) return false
        scheduledPending = true
        return true
    }

    @Synchronized fun requestTest(botRunning: Boolean): FriendLifeTestRequest = when {
        !botRunning -> FriendLifeTestRequest.BOT_STOPPED
        inProgress -> FriendLifeTestRequest.IN_PROGRESS
        testPending -> FriendLifeTestRequest.ALREADY_QUEUED
        else -> { testPending = true; FriendLifeTestRequest.QUEUED }
    }

    /** Read-only readiness check. It deliberately does not begin a transaction. */
    @Synchronized fun hasPendingWork(nowMs: Long): Boolean =
        !inProgress && nowMs >= retryNotBefore && (scheduledPending || testPending)

    @Synchronized fun beginIfReady(nowMs: Long): FriendLifeTrigger? {
        if (inProgress || nowMs < retryNotBefore || (!scheduledPending && !testPending)) return null
        inProgress = true
        return when {
            scheduledPending && testPending -> FriendLifeTrigger.SCHEDULED_AND_TEST
            scheduledPending -> FriendLifeTrigger.SCHEDULED
            else -> FriendLifeTrigger.TEST
        }
    }

    @Synchronized fun complete(trigger: FriendLifeTrigger, completedAtMs: Long) {
        inProgress = false
        retryNotBefore = 0L
        if (trigger != FriendLifeTrigger.TEST) {
            scheduledPending = false
            nextDueAt = completedAtMs + intervalMs
        }
        testPending = false
    }

    @Synchronized fun interrupt() { inProgress = false }

    @Synchronized fun fail(nowMs: Long, retryDelayMs: Long) {
        inProgress = false
        testPending = false
        if (scheduledPending) retryNotBefore = nowMs + retryDelayMs
    }

    @Synchronized fun nextDueAt(): Long = nextDueAt
    @Synchronized fun scheduledPending(): Boolean = scheduledPending
    @Synchronized fun testPending(): Boolean = testPending
    @Synchronized fun inProgress(): Boolean = inProgress

    @Synchronized fun dashboardStatus(nowMs: Long): FriendLifeScheduleStatus = FriendLifeScheduleStatus(
        enabled = enabled,
        scheduledPending = scheduledPending,
        testPending = testPending,
        inProgress = inProgress,
        nextDueInMs = if (enabled) (nextDueAt - nowMs).coerceAtLeast(0L) else null,
        retryInMs = (retryNotBefore - nowMs).coerceAtLeast(0L),
    )
}

data class FriendLifeScheduleStatus(
    val enabled: Boolean,
    val scheduledPending: Boolean,
    val testPending: Boolean,
    val inProgress: Boolean,
    val nextDueInMs: Long?,
    val retryInMs: Long,
)

data class FriendLifeDashboardStatus(
    val botRunning: Boolean,
    val enabled: Boolean,
    val intervalMinutes: Int,
    val scheduledPending: Boolean,
    val testPending: Boolean,
    val inProgress: Boolean,
    val nextDueInMs: Long?,
    val retryInMs: Long,
    val currentSentCount: Int,
    val lastSentCount: Int,
    val lastOutcome: String,
    val lastProgressAgeMs: Long?,
)

internal fun friendLifeInterruptStages(
    always: List<BotStage>,
    preGame: List<BotStage>,
    detectRelic: Boolean,
    relicStages: Set<BotStage>,
): List<BotStage> = (always + preGame.filterNot { it == BotStage.MAINMENU })
    .filterNot { !detectRelic && it in relicStages }

internal fun isGenuinelyPostGestureFrame(frameGeneration: Long, postGestureBaseline: Long): Boolean =
    frameGeneration > postGestureBaseline

internal fun friendLifeInterruptState(primary: BotStage?, closeButtonMatched: Boolean): BotStage? =
    primary ?: if (closeButtonMatched) BotStage.CLOSE_BUTTON else null

internal fun friendLifeRoiContainsTemplate(
    roiWidth: Int,
    roiHeight: Int,
    templateWidth: Int,
    templateHeight: Int,
): Boolean = roiWidth >= templateWidth && roiHeight >= templateHeight

internal fun friendLifeTraversalDecision(
    sendButtonMatched: Boolean,
    bottomMatched: Boolean,
    elapsedMs: Long,
    noProgressMs: Long,
    noProgressTimeoutMs: Long = FRIEND_LIFE_NO_PROGRESS_TIMEOUT_MS,
    absoluteTimeoutMs: Long = FRIEND_LIFE_ABSOLUTE_TIMEOUT_MS,
): FriendLifeTraversalDecision = when {
    bottomMatched && !sendButtonMatched -> FriendLifeTraversalDecision.COMPLETE
    elapsedMs >= absoluteTimeoutMs -> FriendLifeTraversalDecision.ABORT_ABSOLUTE_TIMEOUT
    sendButtonMatched -> FriendLifeTraversalDecision.SEND
    noProgressMs >= noProgressTimeoutMs -> FriendLifeTraversalDecision.ABORT_NO_PROGRESS
    else -> FriendLifeTraversalDecision.SCROLL_DOWN
}

internal fun friendLifeTopSearchDecision(
    matched: Boolean,
    elapsedMs: Long,
): FriendLifeTopSearchDecision = when {
    matched -> FriendLifeTopSearchDecision.TOP_FOUND
    elapsedMs >= FRIEND_TOP_SEARCH_TIMEOUT_MS -> FriendLifeTopSearchDecision.CONTINUE_TRAVERSAL
    else -> FriendLifeTopSearchDecision.SWIPE
}

internal fun friendLifeTopCheckDue(swipeCount: Int): Boolean =
    swipeCount > 0 && swipeCount % FRIEND_TOP_SWIPES_PER_CHECK == 0

const val FRIEND_LIFE_DEFAULT_INTERVAL_MINUTES = 61
const val FRIEND_LIFE_MIN_INTERVAL_MINUTES = 1
const val FRIEND_LIFE_MAX_INTERVAL_MINUTES = 1440
const val FRIEND_LIFE_INTERVAL_MS = FRIEND_LIFE_DEFAULT_INTERVAL_MINUTES * 60_000L
private const val FRIEND_LIFE_MIN_INTERVAL_MS = FRIEND_LIFE_MIN_INTERVAL_MINUTES * 60_000L
private const val FRIEND_LIFE_MAX_INTERVAL_MS = FRIEND_LIFE_MAX_INTERVAL_MINUTES * 60_000L

internal fun friendLifeIntervalMs(minutes: Int): Long {
    require(minutes in FRIEND_LIFE_MIN_INTERVAL_MINUTES..FRIEND_LIFE_MAX_INTERVAL_MINUTES) {
        "Friend Life interval must be 1-1440 minutes"
    }
    return minutes.toLong() * 60_000L
}
internal const val FRIEND_FIND_TOP_SWIPE_DURATION_MS = 360L
internal const val FRIEND_TRAVERSE_SWIPE_DURATION_MS = 800L
internal const val FRIEND_GESTURE_TIMEOUT_MS = 3_500L
internal const val FRIEND_SEND_PACING_MIN_MS = 300
internal const val FRIEND_SEND_PACING_MAX_MS = 600
internal const val FRIEND_LIFE_NO_PROGRESS_TIMEOUT_MS = 30_000L
internal const val FRIEND_LIFE_ABSOLUTE_TIMEOUT_MS = 30L * 60L * 1000L
internal const val FRIEND_LIFE_WATCHDOG_LOG_INTERVAL_MS = 10_000L
internal const val FRIEND_TOP_SWIPES_PER_CHECK = 5
internal const val FRIEND_TOP_CHECK_WAIT_MIN_MS = 2_000
internal const val FRIEND_TOP_CHECK_WAIT_MAX_MS = 2_600
internal const val FRIEND_TOP_SEARCH_TIMEOUT_MS = 10L * 60L * 1000L
internal const val FRIEND_TRAVERSE_SETTLE_MIN_MS = 1_000
internal const val FRIEND_TRAVERSE_SETTLE_MAX_MS = 1_600
internal const val FRIEND_LIFE_MATCH_THRESHOLD = 0.80
