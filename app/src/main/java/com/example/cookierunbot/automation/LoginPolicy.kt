package com.example.cookierunbot.automation

import com.example.cookierunbot.detection.DetectionResult

internal object LoginPolicy {
    const val RETRY_INTERVAL_MS = 3_000L
    const val GOOGLE_ACCOUNT_DELAY_MS = 5_000L
    const val ACCOUNT_PICKER_TEMPLATE = "LOGIN_ACCOUNT_PICKER_1.png"
    const val ACCOUNT_PICKER_MATCH_THRESHOLD = 0.8
    const val ACCOUNT_PICKER_SEARCH_TIMEOUT_MS = 60_000L
    const val ACCOUNT_PICKER_FRAME_WAIT_MS = 1_000L
    const val ACCOUNT_PICKER_LOG_INTERVAL_MS = 5_000L
    const val ACCOUNT_PICKER_ROI_LEFT = 270
    const val ACCOUNT_PICKER_ROI_TOP = 75
    const val ACCOUNT_PICKER_ROI_RIGHT = 1010
    const val ACCOUNT_PICKER_ROI_BOTTOM = 285
    val GOOGLE_LOGO_POINT = LogicalPoint(657, 294)
    val ACCOUNT_PROFILE_POINT = LogicalPoint(323, 299)

    fun tapCenter(result: DetectionResult): LogicalPoint =
        LogicalPoint(
            result.x + result.width / 2,
            result.y + result.height / 2,
        )

    fun retryDue(nowMs: Long, lastTapAtMs: Long): Boolean =
        lastTapAtMs == 0L || nowMs < lastTapAtMs || nowMs - lastTapAtMs >= RETRY_INTERVAL_MS

    fun accountPickerSearchExpired(startedAtMs: Long, nowMs: Long): Boolean =
        nowMs < startedAtMs || nowMs - startedAtMs >= ACCOUNT_PICKER_SEARCH_TIMEOUT_MS
}
