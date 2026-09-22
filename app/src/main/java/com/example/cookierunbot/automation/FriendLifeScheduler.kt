package com.example.cookierunbot.automation

enum class FriendLifeTrigger { SCHEDULED, TEST, SCHEDULED_AND_TEST }
enum class FriendLifeTestRequest { BOT_STOPPED, QUEUED, ALREADY_QUEUED, IN_PROGRESS }
enum class FriendLifeTraversalDecision { SEND, COMPLETE, SCROLL_DOWN, ABORT_SCROLL_LIMIT }

class FriendLifeScheduler(private val intervalMs: Long = FRIEND_LIFE_INTERVAL_MS) {
    private var enabled = false
    private var nextDueAt = Long.MAX_VALUE
    private var scheduledPending = false
    private var testPending = false
    private var inProgress = false
    private var retryNotBefore = 0L

    @Synchronized fun start(enableSchedule: Boolean, nowMs: Long) {
        enabled = enableSchedule
        nextDueAt = if (enableSchedule) nowMs + intervalMs else Long.MAX_VALUE
        scheduledPending = false
        testPending = false
        inProgress = false
        retryNotBefore = 0L
    }

    @Synchronized fun stop() = start(false, 0L)

    /** Returns true only when the hourly request becomes newly pending. */
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
}

internal fun friendLifeTraversalDecision(
    sendButtonMatched: Boolean,
    bottomMatched: Boolean,
    noButtonScrollCount: Int,
    maximumNoButtonScrolls: Int,
): FriendLifeTraversalDecision = when {
    sendButtonMatched -> FriendLifeTraversalDecision.SEND
    bottomMatched -> FriendLifeTraversalDecision.COMPLETE
    noButtonScrollCount >= maximumNoButtonScrolls -> FriendLifeTraversalDecision.ABORT_SCROLL_LIMIT
    else -> FriendLifeTraversalDecision.SCROLL_DOWN
}

const val FRIEND_LIFE_INTERVAL_MS = 60L * 60L * 1000L
