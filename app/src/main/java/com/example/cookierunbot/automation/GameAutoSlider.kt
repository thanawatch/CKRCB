package com.example.cookierunbot.automation

import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.diagnostics.RobotLog as Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GameAutoSlider(private val actionExecutor: ActionExecutor) {
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
            if (options.slideMode == SlideMode.NONE || finishedForRound || worker?.isAlive == true) return
            stopRequested.set(false)
            worker = Thread(::runWorker, "game-auto-slide").apply {
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
        if (thread != null) Log.i(CookieRunBotApplication.TAG, "[AUTO SLIDE] Stopped: $reason")
    }

    private fun runWorker() {
        try {
            val local = synchronized(lock) { options }
            Log.i(CookieRunBotApplication.TAG, "[AUTO SLIDE] Started")
            Log.i(CookieRunBotApplication.TAG, "[AUTO SLIDE] Mode: ${local.slideMode}")
            Log.i(CookieRunBotApplication.TAG, "[AUTO SLIDE] Base=(1115,619)")
            Log.i(CookieRunBotApplication.TAG, "[AUTO SLIDE] Interval=${local.slideIntervalMs}-${local.slideIntervalMaxMs}ms (random)")
            Log.i(CookieRunBotApplication.TAG, "[AUTO SLIDE] Radius=100 phone px")

            if (local.slideMode == SlideMode.FIXED_COUNT) {
                Log.i(CookieRunBotApplication.TAG, "[AUTO SLIDE] Slide by Count=${local.fixedSlideCount}")
                Log.i(CookieRunBotApplication.TAG, "[AUTO SLIDE] Slide by Count started")
                repeat(local.fixedSlideCount) {
                    if (stopRequested.get()) return
                    tapRandomSlideUntilAccepted(local.slideIntervalMs.toLong())
                    sleepCancellable(randomAutoActionIntervalMs(local.slideIntervalMs, local.slideIntervalMaxMs))
                }
                synchronized(lock) { finishedForRound = true }
                Log.i(CookieRunBotApplication.TAG, "[AUTO SLIDE] Slide by Count completed")
                Log.i(CookieRunBotApplication.TAG, "[AUTO SLIDE] Stopped")
            } else {
                Log.i(CookieRunBotApplication.TAG, "[AUTO SLIDE] Auto Slide started")
                while (!stopRequested.get()) {
                    tapRandomSlideUntilAccepted(local.slideIntervalMs.toLong())
                    sleepCancellable(local.slideIntervalMs.toLong())
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            Log.e(CookieRunBotApplication.TAG, "[AUTO SLIDE] Error: ${error.message}", error)
        } finally {
            synchronized(lock) {
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    private fun tapRandomSlideUntilAccepted(notReadyRetryMs: Long) {
        while (!stopRequested.get()) {
            if (tapRandomSlide()) return
            val retryMs = if (BotAccessibilityService.isConnected()) {
                AUTO_ACTION_BUSY_RETRY_MS
            } else {
                notReadyRetryMs
            }
            sleepCancellable(retryMs)
        }
    }

    private fun tapRandomSlide(): Boolean {
        val (width, height) = synchronized(lock) { sourceWidth to sourceHeight }
        if (width <= 0 || height <= 0 || !BotAccessibilityService.isConnected()) return false
        val contentRect = com.example.cookierunbot.capture.ContentGeometryRegistry
            .current(width, height)
            ?.contentRect
            ?: com.example.cookierunbot.capture.FrameNormalizer
                .contentTransform(width, height)
                .contentRect
        val contentScale = contentRect.width.toDouble() /
            com.example.cookierunbot.capture.FrameNormalizer.LOGICAL_WIDTH
        val canonicalRadius = AutomationConfig.AUTO_SLIDE_PHONE_RADIUS / contentScale
        val angle = Random.nextDouble(0.0, 2.0 * PI)
        val distance = canonicalRadius * sqrt(Random.nextDouble())
        val point = LogicalPoint(
            AutomationConfig.AUTO_SLIDE.x + (distance * cos(angle)).roundToInt(),
            AutomationConfig.AUTO_SLIDE.y + (distance * sin(angle)).roundToInt(),
        )
        return actionExecutor.tap(
            "AUTO_SLIDE",
            point,
            width,
            height,
            jitter = 0,
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
