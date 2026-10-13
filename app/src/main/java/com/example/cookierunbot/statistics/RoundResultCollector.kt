package com.example.cookierunbot.statistics

import android.content.Context
import android.os.SystemClock
import com.example.cookierunbot.diagnostics.RobotLog as Log
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.diagnostics.DebugLogStore
import org.opencv.core.Mat
import java.util.concurrent.Executors

class RoundResultCollector(
    context: Context,
    private val onMysteryBoxScan: (round: Int, frame: Mat, scan: BoxScanResult) -> Unit = { _, _, _ -> },
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "round-result-analysis")
    }
    private val numberDetector = ResultNumberDetector(context.applicationContext)
    private val boxClassifier = MysteryBoxClassifier(context.applicationContext)
    private val lock = Any()
    private val starts = mutableMapOf<Int, Long>()
    private val boxes = mutableMapOf<Int, MutableList<String>>()
    private var sessionGeneration = 0L

    fun startNewSession() {
        val generation = RoundResultStore.startNewSession()
        synchronized(lock) {
            starts.clear()
            boxes.clear()
            sessionGeneration = generation
        }
        log("[STATS_SESSION_START] generation=$generation")
    }

    fun beginRound(round: Int, elapsedRealtime: Long = SystemClock.elapsedRealtime()) {
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
        val (analysisGeneration, duration) = synchronized(lock) {
            sessionGeneration to starts[round]?.let { completedElapsedRealtime - it }
        }
        log("[ROUND_COMPLETE] round=$round durationMs=${duration ?: "unknown"}")
        log("[RESULT_ANALYSIS_ENQUEUED] round=$round frameSequence=$frameSequence")
        executor.execute {
            try {
                val reading = numberDetector.read(ownedFrame)
                if (!RoundResultStore.isCurrentGeneration(analysisGeneration)) {
                    logStaleResult(round, analysisGeneration)
                    return@execute
                }
                if (reading.coins.value == null) log("[RESULT_READ_FAILED] field=COINS reason=${reading.coins.reason}")
                if (reading.xp.value == null) log("[RESULT_READ_FAILED] field=XP reason=${reading.xp.reason}")
                log(
                    "[RESULT_READ] round=$round coins=${reading.coins.value ?: "unknown"} " +
                        "xp=${reading.xp.value ?: "unknown"} " +
                        "coinsConfidence=${format(reading.coins.confidence)} " +
                        "xpConfidence=${format(reading.xp.confidence)}",
                )
                val roundBoxes = synchronized(lock) {
                    if (sessionGeneration == analysisGeneration) boxes[round]?.toList().orEmpty()
                    else emptyList()
                }
                val stored = RoundResultStore.upsert(
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
                    ), analysisGeneration,
                )
                if (stored) logResult(round) else logStaleResult(round, analysisGeneration)
            } catch (error: Exception) {
                Log.e(CookieRunBotApplication.TAG, "[RESULT_READ_FAILED] round=$round reason=${error.message}", error)
                val stored = RoundResultStore.upsert(
                    RoundResult(round, finishedAt, duration, null, null, screenshotPath = screenshotPath),
                    analysisGeneration,
                )
                if (!stored) logStaleResult(round, analysisGeneration)
            } finally { ownedFrame.release() }
        }
    }

    fun completeRoundWithoutFrame(
        round: Int,
        completedElapsedRealtime: Long = SystemClock.elapsedRealtime(),
        finishedAt: Long = System.currentTimeMillis(),
    ) {
        val (generation, state) = synchronized(lock) {
            sessionGeneration to Pair(
                starts[round]?.let { completedElapsedRealtime - it },
                boxes[round]?.toList().orEmpty(),
            )
        }
        val stored = RoundResultStore.upsert(
            RoundResult(round, finishedAt, state.first, null, null, state.second, state.second.size),
            generation,
        )
        if (!stored) { logStaleResult(round, generation); return }
        log("[RESULT_READ_FAILED] field=FRAME reason=no_latest_frame")
        logResult(round)
    }

    fun classifyMysteryBoxes(round: Int, frame: Mat) {
        val ownedFrame = frame.clone()
        val analysisGeneration = synchronized(lock) { sessionGeneration }
        executor.execute {
            try {
                val scan = try { boxClassifier.scan(ownedFrame) } catch (error: Exception) {
                    Log.e(CookieRunBotApplication.TAG, "[BOX_SCAN] round=$round phase=1 failed", error)
                    BoxScanResult(0, emptyList())
                }
                val detections = scan.detections
                log(
                    "[BOX_PERF] round=$round phase=1 " +
                        "fastCandidates=${scan.fastPathCandidateCount} " +
                        "fastAccepted=${scan.fastPathAcceptedCount} " +
                        "usedFallback=${scan.usedFallback} " +
                        "near=${scan.usedNearFallback} " +
                        "farProbe=${scan.usedFarProbe} " +
                        "extreme=${scan.usedExtremeFallback} " +
                        "fastMs=${format(scan.fastPathDurationMs)} " +
                        "fallbackMs=${format(scan.fallbackDurationMs)} " +
                        "totalMs=${format(scan.totalDurationMs)}",
                )
                val scoreSummary = listOf("wood", "silver", "gold", "rainbow").joinToString(" ") { grade ->
                    val score = scan.bestScoreByGrade[grade]
                    val scale = scan.bestScaleByGrade[grade]
                    "$grade=${score?.let(::format) ?: "none"}@${scale?.let(::format) ?: "none"}"
                }
                log("[BOX_SCORE] round=$round phase=1 $scoreSummary")
                scan.rawCandidates.take(MAX_BOX_CANDIDATE_LOGS).forEachIndexed { index, candidate ->
                    val selected = detections.contains(candidate)
                    val bridge = if (selected) false else
                        MysteryBoxClassifier.isBridgeCandidate(candidate, scan.rawCandidates)
                    val clusteredWith = if (selected) null else detections.firstOrNull {
                        MysteryBoxClassifier.samePhysicalBox(it.rect, candidate.rect)
                    }
                    val reason = when {
                        selected -> "SELECTED"
                        bridge -> "BRIDGE"
                        clusteredWith != null -> "DEDUP"
                        else -> "LIMIT_OR_LOWER_PRIORITY"
                    }
                    log(
                        "[BOX_CANDIDATE] round=$round rawIndex=${index + 1} " +
                            "grade=${candidate.grade} score=${format(candidate.score)} " +
                            "x=${candidate.rect.x} y=${candidate.rect.y} " +
                            "width=${candidate.rect.width} height=${candidate.rect.height} " +
                            "selected=$selected reason=$reason" +
                            (clusteredWith?.let {
                                " keptGrade=${it.grade} keptScore=${format(it.score)} " +
                                    "keptX=${it.rect.x} keptY=${it.rect.y}"
                            } ?: ""),
                    )
                }
                if (scan.rawCandidates.size > MAX_BOX_CANDIDATE_LOGS) {
                    log(
                        "[BOX_CANDIDATE] round=$round truncated=true " +
                            "logged=$MAX_BOX_CANDIDATE_LOGS total=${scan.rawCandidates.size}",
                    )
                }
                log("[BOX_SCAN] round=$round phase=1 candidateCount=${scan.candidateCount} acceptedCount=${detections.size}")
                detections.forEachIndexed { index, detection ->
                    log(
                        "[BOX_DETECTION] round=$round index=${index + 1} grade=${detection.grade} " +
                            "score=${format(detection.score)} x=${detection.rect.x} y=${detection.rect.y} " +
                            "width=${detection.rect.width} height=${detection.rect.height}",
                    )
                }
                if (!RoundResultStore.isCurrentGeneration(analysisGeneration)) {
                    logStaleBox(round, 1, analysisGeneration)
                    return@execute
                }
                val grades = detections.map { it.grade }
                val updated = synchronized(lock) {
                    if (sessionGeneration != analysisGeneration) null
                    else grades.also { boxes[round] = it.toMutableList() }
                }
                if (updated == null || !RoundResultStore.updateBoxes(round, updated, analysisGeneration)) {
                    logStaleBox(round, 1, analysisGeneration)
                    return@execute
                }
                runCatching { onMysteryBoxScan(round, ownedFrame, scan) }
                    .onFailure { error ->
                        Log.e(
                            CookieRunBotApplication.TAG,
                            "[TELEGRAM_BOX_RESULT] image preparation failed round=$round",
                            error,
                        )
                    }
                log("[BOX_RESULT] round=$round boxes=$grades boxCount=${grades.size}")
                logResult(round)
            } finally { ownedFrame.release() }
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

    private fun logStaleResult(round: Int, analysisGeneration: Long) = log(
        "[RESULT_DISCARDED] reason=STALE_SESSION round=$round " +
            "analysisGeneration=$analysisGeneration currentGeneration=${RoundResultStore.currentGeneration()}",
    )

    private fun logStaleBox(round: Int, index: Int, analysisGeneration: Long) = log(
        "[BOX_RESULT_DISCARDED] reason=STALE_SESSION round=$round index=$index " +
            "analysisGeneration=$analysisGeneration currentGeneration=${RoundResultStore.currentGeneration()}",
    )

    private fun log(message: String) {
        Log.i(CookieRunBotApplication.TAG, message)
        DebugLogStore.log(message)
    }

    private fun format(value: Double) = String.format(java.util.Locale.US, "%.4f", value)

    companion object {
        private const val MAX_BOX_CANDIDATE_LOGS = 32
    }
}
