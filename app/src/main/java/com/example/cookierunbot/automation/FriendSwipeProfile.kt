package com.example.cookierunbot.automation

import kotlin.math.roundToInt
import kotlin.random.Random

internal enum class FriendSwipePhase { FIND_TOP, SCROLL_DOWN }

internal data class FriendSwipeProfile(
    val logicalPoints: List<LogicalPoint>,
    val durationMs: Long,
)

internal object FriendSwipeProfileGenerator {
    const val SAFE_MIN_X = 415
    const val SAFE_MAX_X = 455
    const val SAFE_MIN_Y = 315
    const val SAFE_MAX_Y = 585
    const val POINT_COUNT = 6
    const val FIND_TOP_MIN_DURATION_MS = 300L
    const val FIND_TOP_MAX_DURATION_MS = 480L
    const val SCROLL_DOWN_MIN_DURATION_MS = 650L
    const val SCROLL_DOWN_MAX_DURATION_MS = 950L

    fun create(
        phase: FriendSwipePhase,
        canonicalStart: LogicalPoint,
        canonicalEnd: LogicalPoint,
        random: Random = Random.Default,
    ): FriendSwipeProfile {
        val start = LogicalPoint(
            jitterWithin(canonicalStart.x, 15, SAFE_MIN_X, SAFE_MAX_X, random),
            jitterWithin(canonicalStart.y, 14, SAFE_MIN_Y, SAFE_MAX_Y, random),
        )
        val end = LogicalPoint(
            jitterWithin(canonicalEnd.x, 15, SAFE_MIN_X, SAFE_MAX_X, random),
            jitterWithin(canonicalEnd.y, 14, SAFE_MIN_Y, SAFE_MAX_Y, random),
        )
        val increasing = end.y > start.y
        val points = ArrayList<LogicalPoint>(POINT_COUNT)
        points += start
        var previousY = start.y
        for (index in 1 until POINT_COUNT - 1) {
            val fraction = index.toDouble() / (POINT_COUNT - 1)
            val baseX = start.x + (end.x - start.x) * fraction
            val baseY = start.y + (end.y - start.y) * fraction
            val x = (baseX.roundToInt() + random.nextInt(-8, 9)).coerceIn(SAFE_MIN_X, SAFE_MAX_X)
            val rawY = (baseY.roundToInt() + random.nextInt(-4, 5)).coerceIn(SAFE_MIN_Y, SAFE_MAX_Y)
            val remaining = POINT_COUNT - 1 - index
            val y = if (increasing) {
                rawY.coerceIn(previousY + 1, end.y - remaining)
            } else {
                rawY.coerceIn(end.y + remaining, previousY - 1)
            }
            points += LogicalPoint(x, y)
            previousY = y
        }
        points += end
        val duration = when (phase) {
            FriendSwipePhase.FIND_TOP -> random.nextLong(
                FIND_TOP_MIN_DURATION_MS,
                FIND_TOP_MAX_DURATION_MS + 1L,
            )
            FriendSwipePhase.SCROLL_DOWN -> random.nextLong(
                SCROLL_DOWN_MIN_DURATION_MS,
                SCROLL_DOWN_MAX_DURATION_MS + 1L,
            )
        }
        return FriendSwipeProfile(points, duration)
    }

    private fun jitterWithin(
        center: Int,
        requestedRadius: Int,
        minimum: Int,
        maximum: Int,
        random: Random,
    ): Int {
        // Keep the random envelope symmetric about the canonical point so bounds never create drift.
        val radius = minOf(requestedRadius, center - minimum, maximum - center)
        return center + random.nextInt(-radius, radius + 1)
    }
}
