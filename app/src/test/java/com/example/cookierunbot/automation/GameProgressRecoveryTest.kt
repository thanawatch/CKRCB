package com.example.cookierunbot.automation

import com.example.cookierunbot.detection.TemplateMatch
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProgressRecoveryTest {
    @Test fun actualTemplateMatchDeterminesTapCenter() {
        assertEquals(
            LogicalPoint(740, 452),
            GameProgressRecovery.centerIfMatched(TemplateMatch(0.95, true, 640, 402, 200, 100)),
        )
    }

    @Test fun noMatchNeverProducesTapTarget() {
        assertNull(GameProgressRecovery.centerIfMatched(TemplateMatch(0.1, false, 640, 402, 200, 100)))
    }

    @Test fun circularLogicalJitterNeverExceedsRadiusFive() {
        val center = LogicalPoint(740, 452)
        val random = Random(1234)
        repeat(10_000) {
            val point = GameProgressRecovery.pointInCircle(center, random)
            assertTrue(hypot((point.x - center.x).toDouble(), (point.y - center.y).toDouble()) <= 5.0)
        }
    }
}
