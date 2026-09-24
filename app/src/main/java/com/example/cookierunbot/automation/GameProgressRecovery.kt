package com.example.cookierunbot.automation

import com.example.cookierunbot.detection.TemplateMatch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

internal object GameProgressRecovery {
    const val TEMPLATE = "CONFIRM_SEND_LIFE_1.png"
    const val RADIUS_LOGICAL = 5.0

    /** A matched CONFIRM owns this incident even if its gesture subsequently fails. */
    fun shouldRunCloseFallback(confirmMatched: Boolean): Boolean = !confirmMatched

    fun centerIfMatched(match: TemplateMatch): LogicalPoint? =
        if (match.matched) LogicalPoint(match.x + match.width / 2, match.y + match.height / 2)
        else null

    /** Uniform-area circle in normalized detector coordinates, rounded without leaving radius 5. */
    fun pointInCircle(center: LogicalPoint, random: Random = Random.Default): LogicalPoint {
        while (true) {
            val angle = random.nextDouble() * 2.0 * PI
            val distance = RADIUS_LOGICAL * sqrt(random.nextDouble())
            val dx = (cos(angle) * distance).roundToInt()
            val dy = (sin(angle) * distance).roundToInt()
            if (hypot(dx.toDouble(), dy.toDouble()) <= RADIUS_LOGICAL) {
                return LogicalPoint(center.x + dx, center.y + dy)
            }
        }
    }
}
