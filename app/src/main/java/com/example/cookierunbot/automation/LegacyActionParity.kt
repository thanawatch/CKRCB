package com.example.cookierunbot.automation

import android.util.Log
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.capture.LatestFrameProvider
import com.example.cookierunbot.detection.StateDetector
import org.opencv.core.Rect
import kotlin.random.Random

/**
 * Source-parity implementations for helpers still present in actions.py but
 * not called by the current Python bot.main workflow. They are deliberately
 * not exposed as startup options.
 */
class LegacyActionParity(private val actions: ActionExecutor = ActionExecutor()) {
    fun purchaseFastStart(sourceWidth: Int, sourceHeight: Int, keepRunning: () -> Boolean) {
        tap("PURCHASE_FAST_START_ITEM", LogicalPoint(235, 600), sourceWidth, sourceHeight)
        wait(800, 1400, keepRunning)
        tap("PURCHASE_FAST_START_CONFIRM", LogicalPoint(925, 295), sourceWidth, sourceHeight)
        wait(1000, 2000, keepRunning)
    }

    fun purchaseCookieRelay(sourceWidth: Int, sourceHeight: Int, keepRunning: () -> Boolean) {
        tap("PURCHASE_COOKIE_RELAY_ITEM", LogicalPoint(385, 600), sourceWidth, sourceHeight)
        wait(800, 1400, keepRunning)
        tap("PURCHASE_COOKIE_RELAY_CONFIRM", LogicalPoint(925, 295), sourceWidth, sourceHeight)
        wait(1000, 2000, keepRunning)
    }

    fun purchaseRandomBoost(sourceWidth: Int, sourceHeight: Int, keepRunning: () -> Boolean) {
        tap("PURCHASE_RANDOM_BOOST_ITEM", LogicalPoint(535, 600), sourceWidth, sourceHeight)
        wait(800, 1400, keepRunning)
        tap("PURCHASE_RANDOM_BOOST_CONFIRM", LogicalPoint(925, 295), sourceWidth, sourceHeight)
        wait(1000, 2000, keepRunning)
    }

    fun handleSendFriendLife(
        detector: StateDetector,
        sourceWidth: Int,
        sourceHeight: Int,
        keepRunning: () -> Boolean,
    ) {
        while (keepRunning()) {
            val snapshot = LatestFrameProvider.snapshot() ?: continue
            val topReached = try {
                detector.detectTemplate(snapshot.mat, "FRIEND_TOP_LEADERBOARD_1.png", Rect(149, 272, 54, 302)).matched
            } finally { snapshot.mat.release() }
            if (topReached) break
            swipe(
                LogicalPoint(435, 620 - 300),
                LogicalPoint(435, 620 + 300),
                sourceWidth,
                sourceHeight,
                "FRIEND_SCROLL_TO_TOP",
            )
            wait(800, 1400, keepRunning)
        }

        var noButtonScrolls = 0
        while (keepRunning()) {
            val snapshot = LatestFrameProvider.snapshot() ?: continue
            val bottomReached: Boolean
            val sendButton: com.example.cookierunbot.detection.TemplateMatch
            try {
                bottomReached = detector.detectTemplate(
                    snapshot.mat,
                    "FRIEND_BOTTOM_LEADERBOARD_1.png",
                    Rect(272, 545, 338, 80),
                ).matched
                sendButton = detector.detectTemplate(
                    snapshot.mat,
                    "FRIEND_SEND_LIFE_1.png",
                    Rect(580, 250, 180, 400),
                )
            } finally { snapshot.mat.release() }
            if (bottomReached) return
            if (sendButton.matched) {
                noButtonScrolls = 0
                tap(
                    "LEGACY_SEND_FRIEND_TEMPLATE",
                    LogicalPoint(sendButton.x + sendButton.width / 2, sendButton.y + sendButton.height / 2),
                    sourceWidth,
                    sourceHeight,
                )
                wait(800, 1400, keepRunning)
                tap("LEGACY_SEND_FRIEND_CONFIRM", AutomationConfig.SEND_LIFE_CONFIRM, sourceWidth, sourceHeight)
                wait(800, 1400, keepRunning)
                tap("LEGACY_SEND_FRIEND_CLOSE", AutomationConfig.SEND_LIFE_CLOSE, sourceWidth, sourceHeight)
                wait(800, 1400, keepRunning)
            } else {
                noButtonScrolls += 1
                if (noButtonScrolls >= 30) return
                swipe(
                    LogicalPoint(435, 260 + 70),
                    LogicalPoint(435, 260 - 70),
                    sourceWidth,
                    sourceHeight,
                    "FRIEND_SCROLL_DOWN",
                )
                wait(800, 1400, keepRunning)
            }
        }
    }

    private fun tap(name: String, point: LogicalPoint, width: Int, height: Int) {
        actions.tap(name, point, width, height)
    }

    private fun swipe(start: LogicalPoint, end: LogicalPoint, width: Int, height: Int, name: String) {
        val jitterX = Random.nextInt(-15, 16)
        val jitterY = Random.nextInt(-15, 16)
        BotAccessibilityService.dispatchLogicalSwipe(
            LogicalPoint(start.x + jitterX, start.y + jitterY),
            LogicalPoint(end.x + jitterX, end.y + jitterY),
            width,
            height,
            150L,
            "[ACTION] $name",
        )
    }

    private fun wait(minMs: Int, maxMs: Int, keepRunning: () -> Boolean) {
        if (!keepRunning()) return
        try {
            Thread.sleep(Random.nextLong(minMs.toLong(), maxMs.toLong() + 1L))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!keepRunning()) Log.i(CookieRunBotApplication.TAG, "[ACTION] Legacy helper cancelled")
    }
}
