package com.example.cookierunbot.notifications

const val TELEGRAM_DIAGNOSTIC_MIN_GAP_MS = 15_000L

/**
 * Process-wide attempt-level image burst guard. It is not a delivery tracker: stall notification
 * counts are committed separately only after Telegram confirms an HTTP-successful send.
 * Text notifications are deliberately unaffected.
 */
object TelegramDiagnosticImageGate {
    private var lastImageQueuedAt = Long.MIN_VALUE

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (lastImageQueuedAt != Long.MIN_VALUE &&
            nowMs - lastImageQueuedAt < TELEGRAM_DIAGNOSTIC_MIN_GAP_MS
        ) return false
        lastImageQueuedAt = nowMs
        return true
    }

    @Synchronized
    fun resetForTest() {
        lastImageQueuedAt = Long.MIN_VALUE
    }
}
