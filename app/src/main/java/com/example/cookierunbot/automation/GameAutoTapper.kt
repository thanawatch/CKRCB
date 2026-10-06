package com.example.cookierunbot.automation

import com.example.cookierunbot.diagnostics.RobotLog as Log
import com.example.cookierunbot.CookieRunBotApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GameAutoTapper(private val actionExecutor: ActionExecutor) {
    private val lock = Any()
    private val stopRequested = AtomicBoolean(false)
    private var worker: Thread? = null
    private var options = BotOptions()
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var finishedForRound = false

    fun resetForRound(options: BotOptions, sourceWidth: Int, sourceHeight: Int) {
        stop("NEW_GAME")
        synchronized(lock) {
            this.options = options
            this.sourceWidth = sourceWidth
            this.sourceHeight = sourceHeight
            finishedForRound = false
        }
    }

    fun updateDisplay(sourceWidth: Int, sourceHeight: Int) = synchronized(lock) {
        this.sourceWidth = sourceWidth
        this.sourceHeight = sourceHeight
    }

    fun start() {
        synchronized(lock) {
            if (options.jumpMode == JumpMode.NONE || finishedForRound || worker?.isAlive == true) return
            stopRequested.set(false)
            worker = Thread(::runWorker, "game-auto-jump").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop(reason: String) {
        val thread = synchronized(lock) {
            stopRequested.set(true)
            worker
        }
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            thread.join(1500L)
        }
        synchronized(lock) {
            if (worker === thread && thread?.isAlive != true) worker = null
        }
        if (thread != null) Log.i(CookieRunBotApplication.TAG, "[AUTO TAP] Stopped: $reason")
    }

    private fun runWorker() {
        try {
            val local = synchronized(lock) { options }
            Log.i(CookieRunBotApplication.TAG, "[AUTO TAP] Started")
            Log.i(CookieRunBotApplication.TAG, "[AUTO TAP] Mode: ${local.jumpMode}")
            Log.i(CookieRunBotApplication.TAG, "[AUTO TAP] Base=(108,600)")
            Log.i(CookieRunBotApplication.TAG, "[AUTO TAP] Interval=${local.tapIntervalMs}ms")
            Log.i(CookieRunBotApplication.TAG, "[AUTO TAP] Radius=100 phone px")

            if (local.jumpMode == JumpMode.FIXED_COUNT) {
                Log.i(CookieRunBotApplication.TAG, "[AUTO TAP] Jump by Count=${local.fixedJumpCount}")
                Log.i(CookieRunBotApplication.TAG, "[AUTO TAP] Jump by Count started")
                repeat(local.fixedJumpCount) {
                    if (stopRequested.get()) return
                    tapRandomJumpUntilAccepted(local.tapIntervalMs.toLong())
                    sleepCancellable(local.tapIntervalMs.toLong())
                }
                synchronized(lock) { finishedForRound = true }
                Log.i(CookieRunBotApplication.TAG, "[AUTO TAP] Jump by Count completed")
                Log.i(CookieRunBotApplication.TAG, "[AUTO TAP] Stopped")
            } else {
                Log.i(CookieRunBotApplication.TAG, "[AUTO TAP] Auto Jump started")
                while (!stopRequested.get()) {
                    tapRandomJumpUntilAccepted(local.tapIntervalMs.toLong())
                    sleepCancellable(local.tapIntervalMs.toLong())
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            Log.e(CookieRunBotApplication.TAG, "[AUTO TAP] Error: ${error.message}", error)
        } finally {
            synchronized(lock) {
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    private fun tapRandomJumpUntilAccepted(notReadyRetryMs: Long) {
        while (!stopRequested.get()) {
            if (tapRandomJump()) return
            val retryMs = if (BotAccessibilityService.isConnected()) {
                AUTO_ACTION_BUSY_RETRY_MS
            } else {
                notReadyRetryMs
            }
            sleepCancellable(retryMs)
        }
    }

    private fun tapRandomJump(): Boolean {
        val (width, height) = synchronized(lock) { sourceWidth to sourceHeight }
        if (width <= 0 || height <= 0 || !BotAccessibilityService.isConnected()) return false
        val cropScale = com.example.cookierunbot.capture.FrameNormalizer
            .cropRect(width, height).width.toDouble() /
            com.example.cookierunbot.capture.FrameNormalizer.LOGICAL_WIDTH
        val canonicalRadius = AutomationConfig.AUTO_JUMP_PHONE_RADIUS / cropScale
        val angle = Random.nextDouble(0.0, 2.0 * PI)
        val distance = canonicalRadius * sqrt(Random.nextDouble())
        val point = LogicalPoint(
            AutomationConfig.AUTO_JUMP.x + (distance * cos(angle)).roundToInt(),
            AutomationConfig.AUTO_JUMP.y + (distance * sin(angle)).roundToInt(),
        )
        // Background jumps never wait in front of a state-transition gesture.
        // If a critical GAME_START/GAME_RELAY action is pending, this tap is skipped.
        return actionExecutor.tapCalibrated(
            "AUTO_JUMP",
            point,
            width,
            height,
            priority = GesturePriority.BACKGROUND,
        ).accepted
    }

    private fun sleepCancellable(ms: Long) {
        if (stopRequested.get()) throw InterruptedException()
        Thread.sleep(ms)
    }

    companion object {
        private const val AUTO_ACTION_BUSY_RETRY_MS = 25L
    }
}
