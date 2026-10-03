package com.example.cookierunbot.automation

import com.example.cookierunbot.detection.TemplateMatch
import org.opencv.core.Rect

internal enum class CloseRecoveryDecision { STOP_NO_MATCH, TAP_AND_CONTINUE, TAP_AND_STOP }

internal object GameProgressCloseRecovery {
    const val TEMPLATE = "CLOSE_BUTTON_1.png"
    const val MATCH_THRESHOLD = 0.8
    val MATCH_SCALES = listOf(1.0, 0.9, 1.1, 0.8, 1.2, 1.3, 1.4)
    const val MAX_ATTEMPTS = 3
    const val RETRY_DELAY_MS = 2_000L

    fun topRightRoi(width: Int, height: Int): Rect =
        Rect(width / 2, 0, width - width / 2, height / 2)

    /** StateDetector.detectTemplate() already offsets ROI-local matches into full-frame coordinates. */
    fun centerIfMatched(match: TemplateMatch): LogicalPoint? =
        if (match.matched) LogicalPoint(match.x + match.width / 2, match.y + match.height / 2)
        else null

    fun decision(attempt: Int, matched: Boolean): CloseRecoveryDecision {
        require(attempt in 1..MAX_ATTEMPTS)
        if (!matched) return CloseRecoveryDecision.STOP_NO_MATCH
        return if (attempt == MAX_ATTEMPTS) CloseRecoveryDecision.TAP_AND_STOP
        else CloseRecoveryDecision.TAP_AND_CONTINUE
    }
}
