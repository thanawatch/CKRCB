package com.example.cookierunbot.automation

const val GAME_PROGRESS_TIMEOUT_MS = 15L * 60L * 1000L
const val GAME_PROGRESS_FAILED_RETRY_MS = 5L * 60L * 1000L

enum class GameProgressReason { BOT_START, GAME_COMPLETE, FRIEND_SEND_LIFE_TAP }

data class GameProgressStatus(
    val running: Boolean,
    val hasStarted: Boolean,
    val lastProgressAt: Long,
    val lastProgressReason: GameProgressReason,
    val ageMs: Long,
    val remainingMs: Long,
    val overdue: Boolean,
    val alertDelivered: Boolean,
    val alertInFlight: Boolean,
)

data class GameProgressAlertReservation(val generation: Long)

class GameProgressWatchdog(
    private val timeoutMs: Long = GAME_PROGRESS_TIMEOUT_MS,
    private val failedRetryMs: Long = GAME_PROGRESS_FAILED_RETRY_MS,
) {
    private var running = false
    private var hasStarted = false
    private var lastProgressAt = 0L
    private var reason = GameProgressReason.BOT_START
    private var alertDelivered = false
    private var alertInFlight = false
    private var retryNotBefore = 0L
    private var generation = 0L

    @Synchronized fun start(nowMs: Long) {
        running = true
        hasStarted = true
        generation += 1L
        lastProgressAt = nowMs
        reason = GameProgressReason.BOT_START
        alertDelivered = false
        alertInFlight = false
        retryNotBefore = 0L
    }

    @Synchronized fun stop() {
        running = false
        generation += 1L
        alertInFlight = false
    }

    @Synchronized fun recordProgress(progressReason: GameProgressReason, nowMs: Long): Long {
        val previousAge = (nowMs - lastProgressAt).coerceAtLeast(0L)
        generation += 1L
        lastProgressAt = nowMs
        reason = progressReason
        alertDelivered = false
        alertInFlight = false
        retryNotBefore = 0L
        return previousAge
    }

    @Synchronized fun reserveIfDue(nowMs: Long): GameProgressAlertReservation? {
        if (!running || alertDelivered || alertInFlight || nowMs < retryNotBefore) return null
        if (nowMs - lastProgressAt < timeoutMs) return null
        alertInFlight = true
        return GameProgressAlertReservation(generation)
    }

    @Synchronized fun confirmDelivered(reservation: GameProgressAlertReservation): Boolean {
        if (!reservationCurrent(reservation)) return false
        alertDelivered = true
        alertInFlight = false
        return true
    }

    @Synchronized fun failAttempt(
        reservation: GameProgressAlertReservation,
        nowMs: Long,
        retryDelayMs: Long = failedRetryMs,
    ): Boolean {
        if (!reservationCurrent(reservation)) return false
        alertInFlight = false
        retryNotBefore = safeAdd(nowMs, retryDelayMs.coerceAtLeast(failedRetryMs))
        return true
    }

    @Synchronized fun status(nowMs: Long): GameProgressStatus {
        val age = if (!running && lastProgressAt == 0L) 0L else
            (nowMs - lastProgressAt).coerceAtLeast(0L)
        return GameProgressStatus(
            running, hasStarted, lastProgressAt, reason, age, (timeoutMs - age).coerceAtLeast(0L),
            running && age >= timeoutMs, alertDelivered, alertInFlight,
        )
    }

    private fun reservationCurrent(reservation: GameProgressAlertReservation): Boolean =
        running && alertInFlight && reservation.generation == generation

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}
