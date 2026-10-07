package com.example.cookierunbot.automation

import com.example.cookierunbot.detection.DetectionResult

internal object LoginPolicy {
    const val RETRY_INTERVAL_MS = 3_000L
    const val GOOGLE_ACCOUNT_DELAY_MS = 5_000L
    val GOOGLE_LOGO_POINT = LogicalPoint(657, 294)

    fun tapCenter(result: DetectionResult): LogicalPoint =
        LogicalPoint(
            result.x + result.width / 2,
            result.y + result.height / 2,
        )

    fun retryDue(nowMs: Long, lastTapAtMs: Long): Boolean =
        lastTapAtMs == 0L || nowMs < lastTapAtMs || nowMs - lastTapAtMs >= RETRY_INTERVAL_MS
}
