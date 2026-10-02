package com.example.cookierunbot.automation

import com.example.cookierunbot.detection.TemplateMatch
import org.opencv.core.Rect
import kotlin.random.Random

/** Friend Life confirmations use detector-space matches, not fixed action coordinates. */
internal object FriendLifeConfirmPolicy {
    const val TEMPLATE = "CONFIRM_SEND_LIFE_1.png"
    const val RADIUS_LOGICAL = GameProgressRecovery.RADIUS_LOGICAL
    const val WAIT_MIN_MS = 200
    const val WAIT_MAX_MS = 400

    fun fullFrame(width: Int, height: Int): Rect = Rect(0, 0, width, height)

    fun centerIfMatched(match: TemplateMatch): LogicalPoint? =
        GameProgressRecovery.centerIfMatched(match)

    fun pointInCircle(center: LogicalPoint, random: Random = Random.Default): LogicalPoint =
        GameProgressRecovery.pointInCircle(center, random)
}
