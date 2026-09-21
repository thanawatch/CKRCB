package com.example.cookierunbot.statistics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.diagnostics.DebugLogStore
import org.opencv.core.Mat
import java.util.concurrent.Executors

class RoundResultCollector(context: Context) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "round-result-analysis")
    }
    private val numberDetector = ResultNumberDetector(context.applicationContext)
    private val boxClassifier = MysteryBoxClassifier(context.applicationContext)
    private val lock = Any()
    private val starts = mutableMapOf<Int, Long>()
    private val boxes = mutableMapOf<Int, MutableList<String>>()

    fun beginRound(round: Int, elapsedRealtime: Long = SystemClock.elapsedRealtime()) {
        RoundResultStore.markSessionStarted()
        synchronized(lock) { starts[round] = elapsedRealtime; boxes[round] = mutableListOf() }
        log("[ROUND_START] round=$round elapsedRealtime=$elapsedRealtime")
    }

    fun completeRound(
        round: Int,
        frameSequence: Long,
        settledFrame: Mat,
        screenshotPath: String?,
        completedElapsedRealtime: Long = SystemClock.elapsedRealtime(),
        finishedAt: Long = System.currentTimeMillis(),
    ) {
        val ownedFrame = settledFrame.clone()
        val duration = synchronized(lock) { starts[round]?.let { completedElapsedRealtime - it } }
        log("[ROUND_COMPLETE] round=$round durationMs=${duration ?: "unknown"}")
        log("[RESULT_ANALYSIS_ENQUEUED] round=$round frameSequence=$frameSequence")
        executor.execute {
            try {
                val reading = numberDetector.read(ownedFrame)
                if (reading.coins.value == null) log("[RESULT_READ_FAILED] field=COINS reason=${reading.coins.reason}")
                if (reading.xp.value == null) log("[RESULT_READ_FAILED] field=XP reason=${reading.xp.reason}")
                log(
                    "[RESULT_READ] round=$round coins=${reading.coins.value ?: "unknown"} " +
                        "xp=${reading.xp.value ?: "unknown"} " +
                        "coinsConfidence=${format(reading.coins.confidence)} " +
                        "xpConfidence=${format(reading.xp.confidence)}",
                )
                val roundBoxes = synchronized(lock) { boxes[round]?.toList().orEmpty() }
                RoundResultStore.upsert(
                    RoundResult(
                        round = round,
                        finishedAt = finishedAt,
                        durationMs = duration,
                        coins = reading.coins.value,
                        xp = reading.xp.value,
                        boxes = roundBoxes,
                        boxCount = roundBoxes.size,
                        screenshotPath = screenshotPath,
                        hasScreenshot = screenshotPath != null,
                    ),
                )
                logResult(round)
            } catch (error: Exception) {
                Log.e(CookieRunBotApplication.TAG, "[RESULT_READ_FAILED] round=$round reason=${error.message}", error)
                RoundResultStore.upsert(
                    RoundResult(round, finishedAt, duration, null, null, screenshotPath = screenshotPath),
                )
            } finally { ownedFrame.release() }
        }
    }

    fun completeRoundWithoutFrame(
        round: Int,
        completedElapsedRealtime: Long = SystemClock.elapsedRealtime(),
        finishedAt: Long = System.currentTimeMillis(),
    ) {
        val duration = synchronized(lock) { starts[round]?.let { completedElapsedRealtime - it } }
        val roundBoxes = synchronized(lock) { boxes[round]?.toList().orEmpty() }
        RoundResultStore.upsert(
            RoundResult(round, finishedAt, duration, null, null, roundBoxes, roundBoxes.size),
        )
        log("[RESULT_READ_FAILED] field=FRAME reason=no_latest_frame")
        logResult(round)
    }

    fun classifyMysteryBox(round: Int, index: Int, frame: Mat) {
        val ownedFrame = frame.clone()
        executor.execute {
            val grade = try { boxClassifier.classify(ownedFrame).first } catch (_: Exception) { "unknown" }
            finally { ownedFrame.release() }
            val updated = synchronized(lock) { boxes.getOrPut(round) { mutableListOf() }.apply { add(grade) }.toList() }
            RoundResultStore.updateBoxes(round, updated)
            log("[BOX_RESULT] round=$round index=$index grade=$grade")
            logResult(round)
        }
    }

    private fun logResult(round: Int) {
        val result = RoundResultStore.snapshot().history.firstOrNull { it.round == round } ?: return
        log(
            "[ROUND_RESULT] round=$round coins=${result.coins ?: "unknown"} " +
                "xp=${result.xp ?: "unknown"} boxCount=${result.boxCount} " +
                "boxes=${result.boxes} hasScreenshot=${result.hasScreenshot}",
        )
    }

    private fun log(message: String) {
        Log.i(CookieRunBotApplication.TAG, message)
        DebugLogStore.log(message)
    }

    private fun format(value: Double) = String.format(java.util.Locale.US, "%.4f", value)
}
