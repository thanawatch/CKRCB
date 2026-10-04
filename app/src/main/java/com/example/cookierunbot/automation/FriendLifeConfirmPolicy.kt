package com.example.cookierunbot.automation

import com.example.cookierunbot.detection.TemplateMatch
import org.opencv.core.Rect
import kotlin.random.Random

/** Friend Life confirmations use detector-space matches, not fixed action coordinates. */
internal object FriendLifeConfirmPolicy {
    const val TEMPLATE = "CONFIRM_SEND_LIFE_1.png"
    const val RADIUS_LOGICAL = GameProgressRecovery.RADIUS_LOGICAL
    const val WAIT_MIN_MS = 150
    const val WAIT_MAX_MS = 300
    const val CONFIRM_2_SEARCH_TIMEOUT_MS = 10_000L
    const val CONFIRM_2_RETRY_INTERVAL_MS = 120L

    private const val CONFIRM_1_ROI_X = 640
    private const val CONFIRM_1_ROI_Y = 402
    private const val CONFIRM_1_ROI_WIDTH = 304
    private const val CONFIRM_1_ROI_HEIGHT = 113

    private const val CONFIRM_2_ROI_X = 487
    private const val CONFIRM_2_ROI_Y = 402
    private const val CONFIRM_2_ROI_WIDTH = 304
    private const val CONFIRM_2_ROI_HEIGHT = 113

    fun fullFrame(width: Int, height: Int): Rect = Rect(0, 0, width, height)

    fun confirmOneRegion(width: Int, height: Int): Rect =
        boundedRegion(CONFIRM_1_ROI_X, CONFIRM_1_ROI_Y, CONFIRM_1_ROI_WIDTH, CONFIRM_1_ROI_HEIGHT, width, height)

    fun confirmTwoRegion(width: Int, height: Int): Rect =
        boundedRegion(CONFIRM_2_ROI_X, CONFIRM_2_ROI_Y, CONFIRM_2_ROI_WIDTH, CONFIRM_2_ROI_HEIGHT, width, height)

    private fun boundedRegion(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): Rect {
        val left = x.coerceIn(0, frameWidth.coerceAtLeast(1) - 1)
        val top = y.coerceIn(0, frameHeight.coerceAtLeast(1) - 1)
        val right = (x + width).coerceIn(left + 1, frameWidth.coerceAtLeast(left + 1))
        val bottom = (y + height).coerceIn(top + 1, frameHeight.coerceAtLeast(top + 1))
        return Rect(left, top, right - left, bottom - top)
    }

    fun centerIfMatched(match: TemplateMatch): LogicalPoint? =
        GameProgressRecovery.centerIfMatched(match)

    fun pointInCircle(center: LogicalPoint, random: Random = Random.Default): LogicalPoint =
        GameProgressRecovery.pointInCircle(center, random)
}
