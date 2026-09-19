package com.example.cookierunbot.automation

import android.content.Context
import android.os.SystemClock
import android.os.Process
import android.util.Log
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.capture.ContentGeometryRegistry
import com.example.cookierunbot.capture.FrameNormalizer
import com.example.cookierunbot.capture.LatestFrameProvider
import com.example.cookierunbot.capture.FrameSnapshot
import com.example.cookierunbot.detection.DetectionResult
import com.example.cookierunbot.detection.StateDetector
import com.example.cookierunbot.detection.TemplateMatch
import com.example.cookierunbot.diagnostics.PersistentTrace
import com.example.cookierunbot.diagnostics.DebugLogStore
import org.opencv.core.Mat
import org.opencv.core.Rect
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

private const val PARTY_RUN_SEASON_REWARDS_CALLBACK_TIMEOUT_MS = 3_000L

enum class DetectionGroup { PRE_GAME, IN_GAME, POST_GAME }

class BotController(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onRunningChanged(running: Boolean)
        fun onStateChanged(state: String)
        fun onGroupChanged(group: String)
        fun onDetection(stage: BotStage, result: DetectionResult)
        fun onAction(result: ActionResult)
    }

    private val lock = Any()
    private val actionExecutor = ActionExecutor(context.applicationContext)
    private val autoTapper = GameAutoTapper(actionExecutor)
    private val resultSaver = ResultImageSaver(context.applicationContext)
    private val noMatchDebugFrameSaver = NoMatchDebugFrameSaver(context.applicationContext)
    private var executor: ExecutorService? = null
    private var running = false
    @Volatile private var workerAlive = false
    @Volatile private var lastProcessedFrameSequence = -1L
    @Volatile private var lastDetectionAttemptAt = 0L
    private var options = BotOptions()
    @Volatile private var group = DetectionGroup.PRE_GAME
    private var lastStage: BotStage? = null
    private var lastDetectionAt = 0L
    private var lastLogAt = 0L
    private var lastSafetyLogAt = 0L
    private var lastTraceHeartbeatAt = 0L
    private var lastDetectFrameLogAt = 0L
    private var isFirstGame = true
    private var roundNumber = 0
    private var gameStartDetectedForRound = false
    private var fastStartPollDeadlineNanos = 0L
    private var postRelaySequenceDone = false
    private var gameCompleteImageSaved = true
    private var mysteryBoxImageSaved = true
    private var mysteryBoxCount = 0
    private var livesDoneForRound = true
    private var livesPendingAfterGame = false
    private var sendFriendCheckedForMainMenu = false
    private var noMatchStartedAt = 0L
    private var lastNoMatchObservedAt = 0L
    private var lastNoMatchDiagnosticAt = 0L
    private var noMatchFrameSaved = false
    private var noMatchDebugFrameRequested = false
    private var lastRewardPopupAction: BotStage? = null
    private var consecutiveRewardPopupActions = 0
    private var minimumFreshFrameGeneration = -1L
    private var pendingDailyNewTapGeneration = -1L
    private var pendingPartyRunSeasonRewardsGesture: GestureCancellation? = null
    private var partyRunSeasonRewardsCallbackFailures = 0
    /** Invalidates late accessibility callbacks and delayed timeout runnables across runs. */
    private var botRunGeneration = 0L

    fun isRunning(): Boolean = synchronized(lock) { running }

    fun diagnostics(): BotControllerDiagnostics = synchronized(lock) {
        val worker = executor
        BotControllerDiagnostics(
            running = running,
            jobActive = worker != null && !worker.isShutdown && !worker.isTerminated,
            actionInProgress = actionExecutor.isActionInProgress(),
            workerAlive = workerAlive,
            lastProcessedFrameSequence = lastProcessedFrameSequence,
            lastDetectionAttemptAgeMs = lastDetectionAttemptAt.takeIf { it > 0L }?.let {
                (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)
            },
            group = group.name,
        )
    }

    fun start(options: BotOptions = BotOptions()) {
        synchronized(lock) {
            if (running) return
            this.options = options
            botRunGeneration += 1L
            running = true
            group = DetectionGroup.PRE_GAME
            lastStage = null
            lastDetectionAt = SystemClock.elapsedRealtime()
            isFirstGame = true
            roundNumber = 0
            gameStartDetectedForRound = false
            fastStartPollDeadlineNanos = 0L
            postRelaySequenceDone = false
            gameCompleteImageSaved = true
            mysteryBoxImageSaved = true
            mysteryBoxCount = 0
            livesDoneForRound = true
            livesPendingAfterGame = false
            sendFriendCheckedForMainMenu = false
            workerAlive = false
            lastProcessedFrameSequence = -1L
            lastDetectionAttemptAt = 0L
            lastDetectFrameLogAt = 0L
            resetRewardPopupGuard()
            minimumFreshFrameGeneration = -1L
            pendingDailyNewTapGeneration = -1L
            pendingPartyRunSeasonRewardsTapGeneration = -1L
            pendingPartyRunSeasonRewardsGesture = null
            partyRunSeasonRewardsCallbackFailures = 0
            resetNoMatchEpisode()
            executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "cookierun-bot-loop").apply { priority = Thread.NORM_PRIORITY - 1 }
            }.also { it.submit(::runLoop) }
        }
        Log.i(CookieRunBotApplication.TAG, "[BOT] Started")
        DebugLogStore.log("[BOT] Started")
        PersistentTrace.log("[BOT] started options=$options")
        Log.i(CookieRunBotApplication.TAG, "[BOT] Options=$options")
        listener.onRunningChanged(true)
        updateGroup(DetectionGroup.PRE_GAME)
        listener.onStateChanged("WAITING_FOR_READINESS")
        RuntimeDiagnosticsLogger.log(context, "Start Bot requested")
    }

    fun stop() {
        val stoppedPartyGesture: GestureCancellation?
        val worker = synchronized(lock) {
            if (!running && executor == null) return
            running = false
            botRunGeneration += 1L
            val currentExecutor = executor
            executor = null
            pendingPartyRunSeasonRewardsTapGeneration = -1L
            stoppedPartyGesture = pendingPartyRunSeasonRewardsGesture
            pendingPartyRunSeasonRewardsGesture = null
            currentExecutor
        }
        stoppedPartyGesture?.cancel()
        autoTapper.stop("BOT_EXIT")
        worker?.shutdownNow()
        Log.i(CookieRunBotApplication.TAG, "[BOT] Stopped")
        DebugLogStore.log("[BOT] Stopped")
        PersistentTrace.log("[BOT] stopped")
        listener.onRunningChanged(false)
        listener.onStateChanged("STOPPED")
        listener.onGroupChanged("—")
    }

    private fun runLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            workerAlive = true
            if (!waitForReadiness()) return
            StateDetector(context.applicationContext).use { detector ->
                var lastFrameGeneration = -1L
                while (isRunning()) {
                    val currentGroup = group
                    val frame = if (currentGroup == DetectionGroup.IN_GAME) {
                        LatestFrameProvider.awaitNextSnapshot(lastFrameGeneration, IN_GAME_FRAME_WAIT_MS)
                    } else {
                        LatestFrameProvider.snapshot()
                    }
                    if (frame != null) {
                        lastFrameGeneration = frame.generation
                        tick(detector, frame)
                    }
                    // IN_GAME is driven directly by the latest-frame signal. Other groups retain
                    // their established polling cadence and workflow timing.
                    if (currentGroup != DetectionGroup.IN_GAME) {
                        Thread.sleep(AutomationConfig.POLL_INTERVAL_MS)
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (isRunning()) {
                Log.e(CookieRunBotApplication.TAG, "[BOT] Loop failed: ${error.message}", error)
                listener.onStateChanged("ERROR")
            }
        } finally {
            workerAlive = false
            autoTapper.stop("BOT_LOOP_END")
        }
    }

    private fun waitForReadiness(): Boolean {
        var lastState: String? = null
        var lastReadinessLogAt = 0L
        val initialFrameSequence = LatestFrameProvider.currentGeneration()
        while (isRunning()) {
            val capture = LatestFrameProvider.diagnostics()
            val accessibilityEnabled = BotAccessibilityService.isEnabled(context)
            val accessibilityConnected = BotAccessibilityService.isConnected()
            val instancePresent = BotAccessibilityService.hasServiceInstance()
            val freshFrame = capture.captureRunning &&
                capture.normalizedWidth > 0 &&
                capture.normalizedHeight > 0 &&
                capture.frameAgeMs != null &&
                capture.frameAgeMs <= AutomationConfig.MAX_FRAME_AGE_MS
            val frameSequenceAdvancing = capture.frameSequence > initialFrameSequence
            val state = when {
                !capture.captureRunning -> "WAITING_FOR_CAPTURE"
                !freshFrame -> "WAITING_FOR_FRESH_FRAME"
                !frameSequenceAdvancing -> "WAITING_FOR_FRAME_SEQUENCE"
                !accessibilityEnabled -> "WAITING_FOR_ACCESSIBILITY_PERMISSION"
                !accessibilityConnected || !instancePresent -> "WAITING_FOR_ACCESSIBILITY_SERVICE"
                !workerAlive -> "WAITING_FOR_DETECTION_WORKER"
                else -> "READY"
            }
            if (state == "READY") {
                Log.i(CookieRunBotApplication.TAG, "[BOT] readiness complete")
                DebugLogStore.log("[BOT] Readiness=READY")
                PersistentTrace.log("[BOT] readiness=READY")
                listener.onStateChanged("WAITING_FOR_STATE")
                return true
            }
            if (state != lastState) {
                listener.onStateChanged(state)
                DebugLogStore.log("[BOT] Readiness=$state")
                PersistentTrace.log(
                    "[BOT] readiness=$state captureReady=$freshFrame accessibilityEnabled=$accessibilityEnabled " +
                        "accessibilityConnected=$accessibilityConnected instance=$instancePresent " +
                        "frameSequence=${capture.frameSequence} sequenceAdvancing=$frameSequenceAdvancing " +
                        "workerAlive=$workerAlive frameAge=${capture.frameAgeMs ?: "none"}ms",
                )
                lastState = state
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastReadinessLogAt >= READINESS_LOG_INTERVAL_MS) {
                Log.i(
                    CookieRunBotApplication.TAG,
                    "[BOT] readiness state=$state captureReady=$freshFrame " +
                        "accessibilityEnabled=$accessibilityEnabled " +
                        "accessibilityConnected=$accessibilityConnected " +
                        "serviceInstance=$instancePresent " +
                        "frameSequence=${capture.frameSequence} sequenceAdvancing=$frameSequenceAdvancing " +
                        "workerAlive=$workerAlive " +
                        "latestFrameAge=${capture.frameAgeMs ?: "none"}ms",
                )
                lastReadinessLogAt = now
            }
            Thread.sleep(READINESS_POLL_INTERVAL_MS)
        }
        return false
    }

    private fun tick(detector: StateDetector, frame: FrameSnapshot) {
        if (!isRunning()) {
            frame.mat.release()
            return
        }
        val diagnostics = LatestFrameProvider.diagnostics()
        if (!diagnostics.captureRunning) {
            logSafety("[BOT] Waiting: MediaProjection is not running")
            lastStage = null
            frame.mat.release()
            return
        }
        if (!BotAccessibilityService.isEnabled(context)) {
            logSafety("[BOT] Waiting: Accessibility permission is disabled")
            listener.onStateChanged("WAITING_FOR_ACCESSIBILITY_PERMISSION")
            lastStage = null
            frame.mat.release()
            return
        }
        if (!BotAccessibilityService.isConnected()) {
            logSafety("[BOT] Waiting: Accessibility service is not connected")
            listener.onStateChanged("WAITING_FOR_ACCESSIBILITY_SERVICE")
            lastStage = null
            frame.mat.release()
            return
        }
        if (diagnostics.frameAgeMs == null || diagnostics.frameAgeMs > AutomationConfig.MAX_FRAME_AGE_MS) {
            logSafety("[BOT] Waiting: latest frame is stale or unavailable")
            frame.mat.release()
            return
        }
        if (frame.generation <= minimumFreshFrameGeneration) {
            listener.onStateChanged("WAITING_FOR_FRESH_POPUP_FRAME")
            frame.mat.release()
            return
        }
        if (minimumFreshFrameGeneration >= 0L) {
            Log.i(
                CookieRunBotApplication.TAG,
                "[POPUP] Fresh frame accepted generation=${frame.generation} " +
                    "after=$minimumFreshFrameGeneration",
            )
            minimumFreshFrameGeneration = -1L
        }

        try {
            autoTapper.updateDisplay(frame.sourceWidth, frame.sourceHeight)
            lastProcessedFrameSequence = frame.generation
            lastDetectionAttemptAt = SystemClock.elapsedRealtime()
            logDetectorFrame(frame)
            val detectionStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            var result = detector.detectFirst(stagesFor(group), frame.mat)
            var detectionMatchedAtNanos = SystemClock.elapsedRealtimeNanos()
            val now = SystemClock.elapsedRealtime()
            if (result == null && now - lastDetectionAt >= recoveryInterval(group)) {
                result = detector.detectFirst(ALL_STAGE_ORDER, frame.mat)
                detectionMatchedAtNanos = SystemClock.elapsedRealtimeNanos()
                lastDetectionAt = now
                Log.i(CookieRunBotApplication.TAG, "[RECOVERY] Full stage scan group=$group")
            }
            logDailyNewPostTapResult(detector, frame, result)
            logPartyRunSeasonRewardsPostTapResult(detector, frame, result)
            // DAILY_NEW diagnostics when in PRE_GAME
            if (group == DetectionGroup.PRE_GAME) {
                val dailyNewResult = detector.detect(BotStage.DAILY_NEW, frame.mat)
                if (!dailyNewResult.matched && dailyNewResult.score >= 0) {
                    Log.i(
                        CookieRunBotApplication.TAG,
                        "[DAILY_NEW_DIAG] frameSequence=${frame.generation} " +
                            "score=${format(dailyNewResult.score)} " +
                            "matched=${dailyNewResult.matched} " +
                            "roi=(432,12,849,78) " +
                            "templateSize=${dailyNewResult.width}x${dailyNewResult.height} " +
                            "roiSize=417x66"
                    )
                }
                val partyRunRewardsResult = detector.detect(BotStage.PARTY_RUN_SEASON_REWARDS, frame.mat)
                if (!partyRunRewardsResult.matched && partyRunRewardsResult.score >= 0) {
                    Log.i(
                        CookieRunBotApplication.TAG,
                        "[PARTY_RUN_SEASON_REWARDS_DIAG] frameSequence=${frame.generation} " +
                            "score=${format(partyRunRewardsResult.score)} " +
                            "matched=${partyRunRewardsResult.matched} " +
                            "roi=(350,0,940,140) " +
                            "templateSize=${partyRunRewardsResult.width}x${partyRunRewardsResult.height} " +
                            "roiSize=590x140"
                    )
                }
            }
            // Check if DAILY_NEW was detected in recovery scan but group is not PRE_GAME
            if (result != null && result.state == BotStage.DAILY_NEW && group != DetectionGroup.PRE_GAME) {
                logDiagnostic("[DAILY_NEW_SKIP] reason=wrong_detection_group group=${group.name}")
            }
            // Check if PARTY_RUN_SEASON_REWARDS was detected in recovery scan but group is not PRE_GAME
            if (result != null && result.state == BotStage.PARTY_RUN_SEASON_REWARDS && group != DetectionGroup.PRE_GAME) {
                logDiagnostic("[PARTY_RUN_SEASON_REWARDS_SKIP] reason=wrong_detection_group group=${group.name}")
            }
            if (result == null) {
                handleNoMatchDiagnostics(detector, frame)
                if (now - lastTraceHeartbeatAt >= 5000L) {
                    PersistentTrace.log(
                        "[BOT] detection heartbeat group=$group generation=${frame.generation} " +
                            "frameAge=${diagnostics.frameAgeMs ?: "none"}ms result=NONE",
                    )
                    lastTraceHeartbeatAt = now
                }
                listener.onStateChanged("WAITING_FOR_${group.name}")
                if (group == DetectionGroup.IN_GAME && gameStartDetectedForRound) autoTapper.start()
                return
            }
            resetNoMatchEpisode()
            lastDetectionAt = now
            if (result.state == lastStage) {
                if (result.state == BotStage.DAILY_NEW) {
                    logDiagnostic("[DAILY_NEW_SKIP] reason=same_state_lastStage")
                }
                if (result.state == BotStage.PARTY_RUN_SEASON_REWARDS) {
                    logDiagnostic("[PARTY_RUN_SEASON_REWARDS_SKIP] reason=same_state_lastStage")
                }
                return
            }
            if (result.state !in REWARD_PRE_GAME_POPUPS) resetRewardPopupGuard()
            val previousStage = lastStage
            LatestFrameProvider.updateDetection(result)
            listener.onDetection(result.state, result)
            logDetection(result)
            if (result.state != BotStage.MAINMENU) sendFriendCheckedForMainMenu = false
            lastStage = result.state
            DebugLogStore.log(
                "[STATE] ${previousStage?.name ?: "NONE"} -> ${result.state.name}",
            )
            listener.onStateChanged(result.state.name)
            handleStage(
                detector,
                result,
                frame.sourceWidth,
                frame.sourceHeight,
                frame.mat,
                frame.generation,
                FastPathTiming(
                    frameCapturedAtNanos = frame.capturedAtNanos,
                    latestFramePublishedAtNanos = frame.publishedAtNanos,
                    detectionStartedAtNanos = detectionStartedAtNanos,
                    detectionMatchedAtNanos = detectionMatchedAtNanos,
                ),
            )
        } finally {
            frame.mat.release()
        }
    }

    private fun handleStage(
        detector: StateDetector,
        result: DetectionResult,
        sourceWidth: Int,
        sourceHeight: Int,
        frame: Mat,
        frameGeneration: Long,
        timing: FastPathTiming,
    ) {
        when (result.state) {
            BotStage.MAINMENU -> handleMainMenu(detector, sourceWidth, sourceHeight)
            BotStage.PURCHASE_ITEM -> handlePurchaseItem(detector, sourceWidth, sourceHeight)
            BotStage.GAME_START -> handleGameStart(result, frame, sourceWidth, sourceHeight, timing)
            BotStage.GAME_RELAY -> handleGameRelay(result, frame, sourceWidth, sourceHeight, timing)
            BotStage.GAME_COMPLETE -> handleGameComplete(sourceWidth, sourceHeight)
            BotStage.MYSTERY_BOX -> handleMysteryBox(sourceWidth, sourceHeight)
            BotStage.CONGRATULATIONS -> rewardPopup(result.state, "CONGRATULATIONS", AutomationConfig.CONGRATULATIONS_ACCEPT, sourceWidth, sourceHeight, frameGeneration)
            BotStage.LEVEL_UP -> rewardPopup(result.state, "LEVEL_UP", AutomationConfig.LEVEL_UP_ACCEPT, sourceWidth, sourceHeight, frameGeneration)
            BotStage.DAILY_CHECKIN -> rewardPopup(result.state, "DAILY_CHECKIN", AutomationConfig.DAILY_CHECKIN_ACCEPT, sourceWidth, sourceHeight, frameGeneration)
            BotStage.DAILY_CHECKIN_BOOST_SET -> rewardPopup(result.state, "DAILY_CHECKIN_BOOST_SET", AutomationConfig.DAILY_BOOST_ACCEPT, sourceWidth, sourceHeight, frameGeneration)
            BotStage.DAILY_TREASURE -> rewardPopup(result.state, "DAILY_TREASURE", AutomationConfig.DAILY_TREASURE_ACCEPT, sourceWidth, sourceHeight, frameGeneration)
            BotStage.DAILY_NEW -> {
                logDiagnostic("[DAILY_NEW_DIAG] matched=true score=${format(result.score)}")
                logDiagnostic("[DAILY_NEW_ACTION] requested logical=(1128,74)")
                rewardPopup(
                    result.state,
                    "DAILY_NEW",
                    AutomationConfig.DAILY_NEW_ACCEPT,
                    sourceWidth,
                    sourceHeight,
                    frameGeneration,
                    jitter = 0,
                )
            }
            BotStage.ENTER_LEAGUE -> rewardPopup(result.state, "ENTER_LEAGUE", AutomationConfig.ENTER_LEAGUE_ACCEPT, sourceWidth, sourceHeight, frameGeneration)
            BotStage.LEAGUE_RESULTS -> rewardPopup(result.state, "LEAGUE_RESULTS", AutomationConfig.LEAGUE_RESULTS_ACCEPT, sourceWidth, sourceHeight, frameGeneration)
            BotStage.PREVIOUS_RANK_RESULTS -> rewardPopup(result.state, "PREVIOUS_RANK_RESULTS", AutomationConfig.PREVIOUS_RANK_ACCEPT, sourceWidth, sourceHeight, frameGeneration)
            BotStage.OVERTAKE_BREAK_SCORE -> popup("OVERTAKE_BREAK_SCORE", AutomationConfig.OVERTAKE_ACCEPT, sourceWidth, sourceHeight, DetectionGroup.POST_GAME, true)
            BotStage.TOO_MANY_TREASURES -> rewardPopup(result.state, "TOO_MANY_TREASURES", AutomationConfig.TOO_MANY_TREASURES_ACCEPT, sourceWidth, sourceHeight, frameGeneration)
            BotStage.RELIC_COMPLETE -> if (options.detectRelic) rewardPopup(result.state, "RELIC_COMPLETE", AutomationConfig.RELIC_COMPLETE, sourceWidth, sourceHeight, frameGeneration)
            BotStage.RELIC_CLAIM -> if (options.detectRelic) handleRelicClaim(sourceWidth, sourceHeight, frameGeneration)
            BotStage.ANTI_BOT -> handleAntiBot(detector, frame, sourceWidth, sourceHeight)
            BotStage.CONNECTION_LOST -> handleConnectionLost(sourceWidth, sourceHeight)
            BotStage.INACTIVE -> handleInactive(sourceWidth, sourceHeight)
            BotStage.PARTY_RUN -> rewardPopup(result.state, "PARTY_RUN", AutomationConfig.PARTY_RUN_CLOSE, sourceWidth, sourceHeight, frameGeneration)
            BotStage.PARTY_RUN_SEASON_REWARDS -> handlePartyRunSeasonRewards(result, sourceWidth, sourceHeight, frameGeneration)
            BotStage.GAME_SETTINGS -> rewardPopup(result.state, "GAME_SETTINGS", AutomationConfig.GAME_SETTINGS_CLOSE, sourceWidth, sourceHeight, frameGeneration)
            BotStage.IN_GAME -> Unit
        }
    }

    private fun handlePartyRunSeasonRewards(
        result: DetectionResult,
        sourceWidth: Int,
        sourceHeight: Int,
        frameGeneration: Long,
    ) {
        if (synchronized(lock) { pendingPartyRunSeasonRewardsGesture?.isCompleted == false }) {
            logDiagnostic("[PARTY_RUN_SEASON_REWARDS_SKIP] reason=callback_pending")
            return
        }
        logDiagnostic("[STATE_MATCH] state=PARTY_RUN_SEASON_REWARDS score=${format(result.score)}")
        logDiagnostic("[ACTION] state=PARTY_RUN_SEASON_REWARDS action=CONFIRM")
        logDiagnostic(
            "[ACTION_MAP]\n" +
                "state=PARTY_RUN_SEASON_REWARDS\n" +
                "coordinateType=PHYSICAL\n" +
                "physical=(1260,980)"
        )
        val physicalPoint = PhysicalPoint(1260, 980)
        if (partyRunSeasonRewardsCallbackFailures >= MAX_CONSECUTIVE_REWARD_POPUP_ACTIONS) {
            logDiagnostic("[PARTY_RUN_SEASON_REWARDS_SKIP] reason=callback_retry_limit")
            return
        }
        
        // Check popup guard first
        if (!allowRewardPopupAction(BotStage.PARTY_RUN_SEASON_REWARDS)) {
            logDiagnostic("[POPUP_RETRY] state=PARTY_RUN_SEASON_REWARDS retry=blocked_by_guard")
            return
        }

        val runGeneration = synchronized(lock) { botRunGeneration }
        val action = performPhysicalTap(
            "PARTY_RUN_SEASON_REWARDS",
            physicalPoint,
            sourceWidth,
            sourceHeight,
            frameGeneration,
            callbackTimeoutMs = PARTY_RUN_SEASON_REWARDS_CALLBACK_TIMEOUT_MS,
            onCallback = { success, timedOut ->
                handlePartyRunSeasonRewardsCallback(runGeneration, success, timedOut)
            },
        )
        val accepted = action.accepted
        logDiagnostic("[GESTURE] state=PARTY_RUN_SEASON_REWARDS dispatchAccepted=$accepted")
        if (!accepted) {
            // dispatch rejected - re-arm state, don't record action
            minimumFreshFrameGeneration = frameGeneration
            lastStage = null
            logDiagnostic(
                "[PARTY_RUN_SEASON_REWARDS] dispatch rejected; re-armed and waiting for fresh frame " +
                    "generation>$frameGeneration"
            )
        } else {
            // dispatch accepted - arm pending generation for post-tap diagnostic
            synchronized(lock) {
                if (running && botRunGeneration == runGeneration && action.pendingGesture?.isCompleted == false) {
                    pendingPartyRunSeasonRewardsTapGeneration = frameGeneration
                    pendingPartyRunSeasonRewardsGesture = action.pendingGesture
                }
            }
        }
    }

    private fun handlePartyRunSeasonRewardsCallback(runGeneration: Long, success: Boolean, timedOut: Boolean) {
        if (!synchronized(lock) { running && botRunGeneration == runGeneration }) return
        synchronized(lock) { pendingPartyRunSeasonRewardsGesture = null }
        if (timedOut) {
            logDiagnostic("[GESTURE] state=PARTY_RUN_SEASON_REWARDS callbackTimeout=true")
        }
        if (!success) {
            // callback success=false - re-arm state, don't record success, retry
            logDiagnostic(
                "[PARTY_RUN_SEASON_REWARDS] callback failed; re-arming state"
            )
            partyRunSeasonRewardsCallbackFailures += 1
            lastStage = null
            pendingPartyRunSeasonRewardsTapGeneration = -1L
            minimumFreshFrameGeneration = LatestFrameProvider.currentGeneration()
            updateGroup(DetectionGroup.PRE_GAME)
            return
        }

        // callback success=true
        partyRunSeasonRewardsCallbackFailures = 0
        // Use current/latest frame generation as freshness baseline (NOT detection-time frame generation)
        val postCallbackBaseline = LatestFrameProvider.currentGeneration()
        logDiagnostic("[POPUP] state=PARTY_RUN_SEASON_REWARDS postCallbackBaselineGeneration=$postCallbackBaseline")

        // Record popup action
        recordRewardPopupAction(BotStage.PARTY_RUN_SEASON_REWARDS)

        // Wait for genuinely fresh frame (generation > postCallbackBaseline)
        val fresh = LatestFrameProvider.awaitNextSnapshot(
            postCallbackBaseline,
            POPUP_FRESH_FRAME_TIMEOUT_MS,
        )
        if (fresh != null) {
            try {
                logDiagnostic(
                    "[STATE_REDETECT]\n" +
                        "previous=PARTY_RUN_SEASON_REWARDS\n" +
                        "baselineGeneration=$postCallbackBaseline\n" +
                        "freshFrameSequence=${fresh.generation}"
                )
                minimumFreshFrameGeneration = -1L
                // Generic PRE_GAME redetection - screen is authoritative
                updateGroup(DetectionGroup.PRE_GAME)
                lastStage = null
            } finally {
                fresh.mat.release()
            }
        } else {
            Log.w(
                CookieRunBotApplication.TAG,
                "[PARTY_RUN_SEASON_REWARDS] Waiting for fresh frame after callback generation>$postCallbackBaseline"
            )
            minimumFreshFrameGeneration = postCallbackBaseline
        }
        pendingPartyRunSeasonRewardsTapGeneration = -1L
    }

    private var pendingPartyRunSeasonRewardsTapGeneration = -1L

    private fun performPhysicalTap(
        name: String,
        physicalPoint: PhysicalPoint,
        sourceWidth: Int,
        sourceHeight: Int,
        frameGeneration: Long,
        callbackTimeoutMs: Long = 0L,
        onCallback: ((Boolean, Boolean) -> Unit)? = null,
    ): ActionResult {
        logBotActionRequest(name)
        val result = synchronized(lock) {
            if (!running) {
                actionSkippedBotStopped(name)
                ActionResult(name, false, 0, 0, null)
            } else actionExecutor.tapAbsolutePhysicalCircle(
                name,
                LogicalPoint(0, 0), // logical reference not used for physical tap
                physicalPoint,
                0.0, // no radius jitter - tap exact physical coordinate
                sourceWidth,
                sourceHeight,
                callbackTimeoutMs,
                onGestureCompleted = if (name == "PARTY_RUN_SEASON_REWARDS") {
                    { _, success, timedOut ->
                        logDiagnostic("[GESTURE] state=PARTY_RUN_SEASON_REWARDS callbackSuccess=$success")
                        onCallback?.invoke(success, timedOut)
                    }
                } else {
                    null
                },
            )
        }
        if (name == "PARTY_RUN_SEASON_REWARDS") {
            logDiagnostic("[PARTY_RUN_SEASON_REWARDS] dispatchAccepted=${result.accepted}")
        }
        reportActionResult(result)
        return result
    }

    private fun handleMainMenu(detector: StateDetector, sourceWidth: Int, sourceHeight: Int) {
        Log.i(CookieRunBotApplication.TAG, "[BOT] MAINMENU reached; waiting 5 seconds for screen refresh")
        delayOrStop(5000L)
        if (options.sendFriendLife && !sendFriendCheckedForMainMenu) {
            sendFriendCheckedForMainMenu = true
            handleMainMenuFriendLife(detector, sourceWidth, sourceHeight)
        }
        if (options.receiveSendLivesAfterEachGame && livesPendingAfterGame && !livesDoneForRound) {
            val wait = Random.nextLong(5000L, 6001L)
            Log.i(CookieRunBotApplication.TAG, "[LIVES] MAINMENU reached")
            Log.i(CookieRunBotApplication.TAG, "[LIVES] Waiting ${wait / 1000.0}s before opening Mail Box")
            delayOrStop(wait)
            handleQuickLives(detector, sourceWidth, sourceHeight)
            livesDoneForRound = true
            livesPendingAfterGame = false
            Log.i(CookieRunBotApplication.TAG, "[LIVES] Workflow completed")
        }
        if (!isFirstGame) {
            val wait = randomMs(2000, 3000)
            Log.i(CookieRunBotApplication.TAG, "[BOT] Waiting ${wait / 1000.0}s before next game")
            delayOrStop(wait)
        }
        isFirstGame = false
        livesDoneForRound = false
        livesPendingAfterGame = false
        if (!performTap("MAINMENU_START", LogicalPoint(955, 650), sourceWidth, sourceHeight)) {
            return
        }
        delayOrStop(randomMs(800, 1400))
        updateGroup(DetectionGroup.PRE_GAME)
    }

    private fun handlePurchaseItem(detector: StateDetector, sourceWidth: Int, sourceHeight: Int) {
        roundNumber += 1
        postRelaySequenceDone = false
        gameStartDetectedForRound = false
        fastStartPollDeadlineNanos = 0L
        gameCompleteImageSaved = false
        mysteryBoxImageSaved = false
        mysteryBoxCount = 0
        autoTapper.resetForRound(options, sourceWidth, sourceHeight)
        Log.i(CookieRunBotApplication.TAG, "[BOT] beginRound=$roundNumber")
        if (options.useDesiredRandomBoost) {
            val ready = purchaseDesiredBoost(detector, sourceWidth, sourceHeight)
            Log.i(
                CookieRunBotApplication.TAG,
                "[BOOST_FLOW] desired=${boostLogName()} ready=$ready next=PLAY_GAME",
            )
            DebugLogStore.log(
                "[BOOST_FLOW] desired=${boostLogName()} ready=$ready next=PLAY_GAME",
            )
        }
        performTap("PLAY_GAME", LogicalPoint(895, 620), sourceWidth, sourceHeight)
        if (options.useFastStart) {
            // Python uses a dedicated short GAME_START poll immediately after Play.
            // Give that transient state first priority until detected or timed out.
            fastStartPollDeadlineNanos =
                SystemClock.elapsedRealtimeNanos() + FAST_START_PRIORITY_WINDOW_NANOS
        }
        updateGroup(DetectionGroup.IN_GAME)
        lastStage = null
    }

    private fun purchaseDesiredBoost(
        detector: StateDetector,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Boolean {
        if (!performTap("RANDOM_BOOST_ITEM", AutomationConfig.RANDOM_BOOST_ITEM, sourceWidth, sourceHeight)) {
            return false
        }
        delayOrStop(randomMs(800, 1400))
        if (!performTap("MULTI_PURCHASE", AutomationConfig.MULTI_PURCHASE, sourceWidth, sourceHeight)) {
            return false
        }
        delayOrStop(randomMs(1000, 2000))
        if (!performTap("MULTI_BUY", AutomationConfig.MULTI_BUY, sourceWidth, sourceHeight)) {
            return false
        }
        // Do not accept a frame captured before MULTI_BUY. The wait below starts
        // strictly after this generation and consumes only the latest frame.
        var lastSequence = LatestFrameProvider.currentGeneration()
        delayOrStop(randomMs(800, 1400))
        // Frames captured during the UI settle delay are also excluded, ensuring
        // the first inspected frame is genuinely newer than the settled screen.
        lastSequence = maxOf(lastSequence, LatestFrameProvider.currentGeneration())

        val desired = boostLogName()
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + DESIRED_BOOST_TIMEOUT_MS
        var bestScore = -1.0
        var lastProgressLogAt = 0L
        while (isRunning()) {
            val now = SystemClock.elapsedRealtime()
            val remaining = deadline - now
            if (remaining <= 0L) break
            val snapshot = LatestFrameProvider.awaitNextSnapshot(
                lastSequence,
                minOf(remaining, BOOST_FRAME_WAIT_TIMEOUT_MS),
            ) ?: continue
            lastSequence = snapshot.generation
            try {
                val match = detector.detectTemplate(
                    snapshot.mat,
                    options.desiredBoost.assetName,
                    DESIRED_BOOST_REGION,
                )
                bestScore = maxOf(bestScore, match.score)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (match.matched || now - lastProgressLogAt >= BOOST_PROGRESS_LOG_INTERVAL_MS) {
                    logBoost(
                        "[BOOST_WAIT] desired=$desired frameSequence=${snapshot.generation}",
                    )
                    logBoost(
                        "[BOOST_MATCH] desired=$desired score=${format(match.score)} matched=${match.matched}",
                    )
                    lastProgressLogAt = now
                }
                if (match.matched) {
                    logBoost("[BOOST_READY] desired=$desired elapsedMs=$elapsed")
                    return true
                }
            } finally {
                snapshot.mat.release()
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val message =
            "[BOOST_TIMEOUT] desired=$desired elapsedMs=$elapsed bestScore=${format(bestScore)} " +
                "continuing=PLAY_GAME"
        Log.w(CookieRunBotApplication.TAG, message)
        DebugLogStore.log(message)
        PersistentTrace.log(message)
        // Python parity: timeout skips the desired-boost wait and the caller
        // continues with the normal PLAY_GAME action.
        return false
    }

    private fun boostLogName(): String =
        options.desiredBoost.assetName.removeSuffix("_1.png").removeSuffix(".png")

    private fun logBoost(message: String) {
        Log.i(CookieRunBotApplication.TAG, message)
        DebugLogStore.log(message)
        PersistentTrace.log(message)
    }

    private fun handleGameStart(
        result: DetectionResult,
        frame: Mat,
        sourceWidth: Int,
        sourceHeight: Int,
        timing: FastPathTiming,
    ) {
        if (options.useFastStart) {
            val accepted = performCriticalTap(
                BotStage.GAME_START,
                "USE_FAST_START",
                AutomationConfig.FAST_START_USE,
                sourceWidth,
                sourceHeight,
                timing,
            )
            if (!accepted) return
            delayOrStop(randomMs(800, 1200))
        }
        gameStartDetectedForRound = true
        fastStartPollDeadlineNanos = 0L
        Log.i(CookieRunBotApplication.TAG, "[AUTO TAP] Trigger: GAME_START")
        autoTapper.start()
        updateGroup(DetectionGroup.IN_GAME)
    }

    private fun handleGameRelay(
        result: DetectionResult,
        frame: Mat,
        sourceWidth: Int,
        sourceHeight: Int,
        timing: FastPathTiming,
    ) {
        if (options.useCookieRelay) {
            val accepted = performCriticalTap(
                BotStage.GAME_RELAY,
                "USE_COOKIE_RELAY",
                AutomationConfig.COOKIE_RELAY_USE,
                sourceWidth,
                sourceHeight,
                timing,
            )
            if (!accepted) return
            Log.i(CookieRunBotApplication.TAG, "[COOKIE RELAY] Cookie Relay used")
            if (options.postCookieRelaySequence && !postRelaySequenceDone) {
                postRelaySequenceDone = true
                postRelaySequence(sourceWidth, sourceHeight)
            } else delayOrStop(randomMs(800, 1200))
        }
        updateGroup(DetectionGroup.IN_GAME)
    }

    private fun postRelaySequence(sourceWidth: Int, sourceHeight: Int) {
        val waits = longArrayOf(randomMs(2000, 4000), randomMs(600, 2000), randomMs(600, 2000))
        val points = arrayOf(
            AutomationConfig.POST_RELAY_1 to AutomationConfig.POST_RELAY_1_PHYSICAL,
            AutomationConfig.POST_RELAY_2 to AutomationConfig.POST_RELAY_2_PHYSICAL,
            AutomationConfig.POST_RELAY_3 to AutomationConfig.POST_RELAY_3_PHYSICAL,
        )
        points.forEachIndexed { index, (logicalReference, physicalTarget) ->
            Log.i(CookieRunBotApplication.TAG, "[COOKIE RELAY SEQUENCE] Waiting ${waits[index] / 1000.0}s")
            delayOrStop(waits[index])
            performPostRelayTap(
                "POST_RELAY_${index + 1}",
                logicalReference,
                physicalTarget,
                sourceWidth,
                sourceHeight,
            )
        }
        Log.i(CookieRunBotApplication.TAG, "[COOKIE RELAY SEQUENCE] Completed")
    }

    private fun handleGameComplete(sourceWidth: Int, sourceHeight: Int) {
        autoTapper.stop("GAME_COMPLETE")
        Log.i(CookieRunBotApplication.TAG, "[RESULT] GAME_COMPLETE detected")
        if (!gameCompleteImageSaved) {
            val waitMs = randomMs(9000, 11000)
            Log.i(
                CookieRunBotApplication.TAG,
                "[RESULT] Waiting ${waitMs / 1000.0}s before save",
            )
            delayOrStop(waitMs)
            saveLatestResult("game_complete")
            gameCompleteImageSaved = true
        }
        performTap("GAME_COMPLETE_FINISH", AutomationConfig.COMPLETE_FINISH, sourceWidth, sourceHeight)
        delayOrStop(randomMs(800, 1400))
        livesPendingAfterGame = options.receiveSendLivesAfterEachGame
        updateGroup(DetectionGroup.POST_GAME)
    }

    private fun handleMysteryBox(sourceWidth: Int, sourceHeight: Int) {
        mysteryBoxCount += 1
        Log.i(CookieRunBotApplication.TAG, "[RESULT] MYSTERY_BOX count=$mysteryBoxCount")
        if (mysteryBoxCount == 2 && !mysteryBoxImageSaved) {
            delayOrStop(3000L)
            saveLatestResult("mystery_box")
            mysteryBoxImageSaved = true
        }
        performTap("MYSTERY_BOX_$mysteryBoxCount", AutomationConfig.MYSTERY_BOX_ACCEPT, sourceWidth, sourceHeight)
        delayOrStop(randomMs(800, 1400))
        delayOrStop(3000L)
        updateGroup(DetectionGroup.POST_GAME)
        lastStage = null
    }

    private fun handleRelicClaim(sourceWidth: Int, sourceHeight: Int, frameGeneration: Long) {
        if (!allowRewardPopupAction(BotStage.RELIC_CLAIM)) return
        if (!performTap("RELIC_CLAIM", AutomationConfig.RELIC_CLAIM, sourceWidth, sourceHeight)) return
        delayOrStop(randomMs(800, 1400))
        if (!performTap("RELIC_CLOSE", AutomationConfig.RELIC_CLOSE, sourceWidth, sourceHeight)) return
        recordRewardPopupAction(BotStage.RELIC_CLAIM)
        delayOrStop(randomMs(10_000, 15_000))
        finishRewardPopupAction(BotStage.RELIC_CLAIM, frameGeneration)
    }

    private fun handleConnectionLost(sourceWidth: Int, sourceHeight: Int) {
        autoTapper.stop("CONNECTION_LOST")
        performTap("CONNECTION_LOST_RELOAD", AutomationConfig.RELOAD, sourceWidth, sourceHeight)
        delayOrStop(randomMs(3000, 5000))
        lastStage = null
    }

    private fun handleInactive(sourceWidth: Int, sourceHeight: Int) {
        performTap("INACTIVE_RELOAD", AutomationConfig.RELOAD, sourceWidth, sourceHeight)
        delayOrStop(randomMs(10_000, 15_000))
        lastStage = null
    }

    private fun handleAntiBot(detector: StateDetector, frame: Mat, sourceWidth: Int, sourceHeight: Int) {
        val cards = listOf(
            LogicalPoint(346, 179), LogicalPoint(542, 179), LogicalPoint(738, 179),
            LogicalPoint(346, 435), LogicalPoint(542, 435), LogicalPoint(738, 435),
        )
        val odd = detector.detectAntiBotOddCards(frame)
        Log.i(CookieRunBotApplication.TAG, "[RECOVERY] Anti-bot odd cards=${odd.map { it + 1 }}")
        for (index in odd) {
            val origin = cards[index]
            val target = LogicalPoint(
                Random.nextInt(origin.x + 20, origin.x + 163),
                Random.nextInt(origin.y + 20, origin.y + 221),
            )
            performDetectedTap("ANTI_BOT_CARD_${index + 1}", target, sourceWidth, sourceHeight)
            delayOrStop(randomMs(10_000, 15_000))
        }
        delayOrStop(randomMs(800, 1400))
        lastStage = null
    }

    private fun handleMainMenuFriendLife(detector: StateDetector, sourceWidth: Int, sourceHeight: Int) {
        Log.i(CookieRunBotApplication.TAG, "[SEND FRIEND LIFE] MAINMENU reached")
        var completed = 0
        var pendingMatch: TemplateMatch? = null
        while (isRunning()) {
            // After the third tap the screen can be in an intermediate animation
            // state.  Keep the next match in memory so the frame used for the
            // explicit re-scan is also the frame used by the next sequence.
            val match = pendingMatch ?: detectCurrentFriendSendLife(detector)
            pendingMatch = null
            if (match == null) {
                delayOrStop(120L)
                continue
            }
            if (!match.matched) {
                Log.i(CookieRunBotApplication.TAG, "[SEND FRIEND LIFE] no more buttons score=${format(match.score)}")
                delayOrStop(3000L)
                return
            }
            val center = LogicalPoint(match.x + match.width / 2, match.y + match.height / 2)
            performDetectedPhysicalCircleTap("FRIEND_TEMPLATE", center, sourceWidth, sourceHeight)
            delayOrStop(randomMs(1000, 2000))
            performPhysicalCircleTap("FRIEND_CONFIRM_1", AutomationConfig.MAINMENU_FRIEND_CONFIRM_1, sourceWidth, sourceHeight)
            delayOrStop(randomMs(1000, 2000))
            val generationBeforeThirdTap = LatestFrameProvider.currentGeneration()
            performPhysicalCircleTap("FRIEND_CONFIRM_2", AutomationConfig.MAINMENU_FRIEND_CONFIRM_2, sourceWidth, sourceHeight)
            completed += 1
            Log.i(
                CookieRunBotApplication.TAG,
                "[SEND FRIEND LIFE] Re-scanning after third tap (completed=$completed)",
            )
            // Do not immediately reuse the frame captured before the third tap.
            // Wait for the gesture/UI transition and explicitly run a fresh
            // template search.  A single transient no-match must not terminate
            // the sequence while the page is still animating.
            delayOrStop(FRIEND_POST_TAP_SETTLE_MS)
            val refreshed = awaitFriendSendLifeAfter(detector, generationBeforeThirdTap)
            if (refreshed == null) {
                Log.w(CookieRunBotApplication.TAG, "[SEND FRIEND LIFE] Fresh frame timeout; no more buttons")
                delayOrStop(3000L)
                return
            }
            if (!refreshed.matched) {
                Log.i(CookieRunBotApplication.TAG, "[SEND FRIEND LIFE] no more buttons after re-scan score=${format(refreshed.score)}")
                delayOrStop(3000L)
                return
            }
            Log.i(
                CookieRunBotApplication.TAG,
                "[SEND FRIEND LIFE] Fresh template match ready score=${format(refreshed.score)}; continuing",
            )
            pendingMatch = refreshed
        }
    }

    /** Detect the friend-life button on the current latest frame. */
    private fun detectCurrentFriendSendLife(detector: StateDetector): TemplateMatch? {
        val snapshot = LatestFrameProvider.snapshot() ?: return null
        return detectFriendSendLife(detector, snapshot)
    }

    /**
     * Wait for a frame newer than [afterGeneration] and scan every new frame
     * until the UI settles or the bounded re-scan window expires.
     */
    private fun awaitFriendSendLifeAfter(
        detector: StateDetector,
        afterGeneration: Long,
    ): TemplateMatch? {
        var generation = afterGeneration
        var lastMatch: TemplateMatch? = null
        val deadline = SystemClock.elapsedRealtime() + FRIEND_RESCAN_TIMEOUT_MS
        while (isRunning()) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            val snapshot = LatestFrameProvider.awaitNextSnapshot(generation, remaining) ?: break
            generation = snapshot.generation
            val match = detectFriendSendLife(detector, snapshot)
            lastMatch = match
            if (match.matched) return match
        }
        return lastMatch
    }

    private fun detectFriendSendLife(detector: StateDetector, snapshot: FrameSnapshot): TemplateMatch {
        val match = try {
            detector.detectTemplate(snapshot.mat, "FRIEND_SEND_LIFE_1.png", Rect(580, 250, 180, 400))
        } finally {
            snapshot.mat.release()
        }
        Log.i(
            CookieRunBotApplication.TAG,
            "[SEND FRIEND LIFE] Searching FRIEND_SEND_LIFE_1.png " +
                "generation=${snapshot.generation} score=${format(match.score)} matched=${match.matched}",
        )
        return match
    }

    private fun handleQuickLives(detector: StateDetector, sourceWidth: Int, sourceHeight: Int) {
        Log.i(CookieRunBotApplication.TAG, "[LIVES] Safe tap jitter=10 canonical")
        performTap("MAIL_BOX", AutomationConfig.MAIL_BOX, sourceWidth, sourceHeight, AutomationConfig.LIVES_JITTER)
        delayOrStop(randomMs(800, 1400))
        performTap("MAIL_LIVES_TAB", AutomationConfig.MAIL_LIVES_TAB, sourceWidth, sourceHeight, AutomationConfig.LIVES_JITTER)
        delayOrStop(randomMs(800, 1400))
        var snapshot = LatestFrameProvider.snapshot()
        if (snapshot != null) {
            val noLives = try {
                detector.detectTemplate(snapshot.mat, "NO_LIVES_TO_RECEIVE_1.png", Rect(454, 223, 370, 352)).matched
            } finally { snapshot.mat.release() }
            if (noLives) {
                performTap("MAIL_CLOSE", AutomationConfig.MAIL_CLOSE, sourceWidth, sourceHeight, AutomationConfig.LIVES_JITTER)
                return
            }
        }
        performTap("QUICK_RECEIVE_SEND", AutomationConfig.QUICK_LIVES, sourceWidth, sourceHeight, AutomationConfig.LIVES_JITTER)
        delayOrStop(randomMs(800, 1400))
        while (isRunning()) {
            snapshot = LatestFrameProvider.snapshot()
            if (snapshot == null) { delayOrStop(120L); continue }
            val allDone: Boolean
            val confirm: Boolean
            try {
                allDone = detector.detectTemplate(snapshot.mat, "ALL_LIVES_RECEIVED_AND_SENT_1.png", Rect(396, 267, 486, 79)).matched
                confirm = detector.detectTemplate(snapshot.mat, "CONFIRM_SEND_LIFE_1.png", Rect(640, 402, 304, 113)).matched
            } finally { snapshot.mat.release() }
            if (allDone) {
                performTap("ALL_LIVES_CONFIRM", AutomationConfig.ALL_LIVES_CONFIRM, sourceWidth, sourceHeight, AutomationConfig.LIVES_JITTER)
                delayOrStop(randomMs(800, 1400))
                performTap("MAIL_CLOSE", AutomationConfig.MAIL_CLOSE, sourceWidth, sourceHeight, AutomationConfig.LIVES_JITTER)
                delayOrStop(randomMs(800, 1400))
                return
            }
            if (confirm) {
                performTap("SEND_LIFE_CONFIRM", AutomationConfig.SEND_LIFE_CONFIRM, sourceWidth, sourceHeight, AutomationConfig.LIVES_JITTER)
                delayOrStop(randomMs(800, 1400))
            } else delayOrStop(120L)
        }
    }

    private fun popup(name: String, point: LogicalPoint, sourceWidth: Int, sourceHeight: Int, nextGroup: DetectionGroup, rearm: Boolean = false) {
        performTap(name, point, sourceWidth, sourceHeight)
        delayOrStop(randomMs(800, 1400))
        updateGroup(nextGroup)
        if (rearm) lastStage = null
    }

    /** Generic order-independent handler for reward and pre-game popup states. */
    private fun rewardPopup(
        stage: BotStage,
        name: String,
        point: LogicalPoint,
        sourceWidth: Int,
        sourceHeight: Int,
        frameGeneration: Long,
        jitter: Int = AutomationConfig.DEFAULT_SAFE_JITTER,
    ) {
        if (!allowRewardPopupAction(stage)) {
            if (stage == BotStage.DAILY_NEW) {
                logDiagnostic("[DAILY_NEW_SKIP] reason=popup_retry_guard")
            }
            return
        }
        if (!performTap(name, point, sourceWidth, sourceHeight, jitter)) {
            if (stage == BotStage.DAILY_NEW) {
                minimumFreshFrameGeneration = frameGeneration
                lastStage = null
                logDiagnostic(
                    "[DAILY_NEW] dispatch rejected; re-armed and waiting for fresh frame " +
                        "generation>$frameGeneration",
                )
            }
            return
        }
        if (stage == BotStage.DAILY_NEW) pendingDailyNewTapGeneration = frameGeneration
        recordRewardPopupAction(stage)
        delayOrStop(randomMs(800, 1400))
        finishRewardPopupAction(stage, frameGeneration)
    }

    private fun allowRewardPopupAction(stage: BotStage): Boolean {
        val nextCount = if (lastRewardPopupAction == stage) {
            consecutiveRewardPopupActions + 1
        } else {
            1
        }
        if (nextCount > MAX_CONSECUTIVE_REWARD_POPUP_ACTIONS) {
            val message =
                "[POPUP] Retry limit reached state=$stage count=$consecutiveRewardPopupActions; " +
                    "waiting for screen state to change"
            Log.w(CookieRunBotApplication.TAG, message)
            DebugLogStore.log(message)
            if (stage == BotStage.DAILY_NEW) {
                logDiagnostic("[DAILY_NEW_SKIP] reason=popup_retry_guard")
            }
            if (stage == BotStage.PARTY_RUN_SEASON_REWARDS) {
                logDiagnostic("[PARTY_RUN_SEASON_REWARDS_SKIP] reason=popup_retry_guard")
            }
            return false
        }
        Log.i(
            CookieRunBotApplication.TAG,
            "[POPUP] Handling state=$stage consecutiveAttempt=$nextCount",
        )
        return true
    }

    private fun recordRewardPopupAction(stage: BotStage) {
        consecutiveRewardPopupActions = if (lastRewardPopupAction == stage) {
            consecutiveRewardPopupActions + 1
        } else {
            1
        }
        lastRewardPopupAction = stage
    }

    private fun finishRewardPopupAction(stage: BotStage, detectedFrameGeneration: Long) {
        // Every reward action returns to the same generic PRE_GAME scanner. No
        // expected-next-popup ordering is encoded here; the screen is authoritative.
        updateGroup(DetectionGroup.PRE_GAME)
        minimumFreshFrameGeneration = detectedFrameGeneration
        val fresh = LatestFrameProvider.awaitNextSnapshot(
            detectedFrameGeneration,
            POPUP_FRESH_FRAME_TIMEOUT_MS,
        )
        if (fresh != null) {
            try {
                minimumFreshFrameGeneration = -1L
                Log.i(
                    CookieRunBotApplication.TAG,
                    "[POPUP] Fresh frame ready after=$stage generation=${fresh.generation}; generic PRE_GAME redetect",
                )
                DebugLogStore.log(
                    "[POPUP] state=$stage freshFrame=${fresh.generation} next=GENERIC_PRE_GAME_SCAN",
                )
            } finally {
                fresh.mat.release()
            }
        } else {
            Log.w(
                CookieRunBotApplication.TAG,
                "[POPUP] Waiting for fresh frame after=$stage generation>$detectedFrameGeneration",
            )
        }
        // Re-arm even for the same popup type. If it is genuinely still visible
        // on a newer frame it may be handled again, subject to the retry guard.
        lastStage = null
    }

    private fun resetRewardPopupGuard() {
        lastRewardPopupAction = null
        consecutiveRewardPopupActions = 0
    }

    private fun saveLatestResult(stage: String) {
        val snapshot = LatestFrameProvider.snapshot() ?: return
        try {
            val file = resultSaver.save(snapshot.mat, roundNumber, stage)
            Log.i(CookieRunBotApplication.TAG, "[RESULT] Saved $file")
        } finally { snapshot.mat.release() }
    }

    private fun performTap(
        name: String,
        point: LogicalPoint,
        sourceWidth: Int,
        sourceHeight: Int,
        jitter: Int = AutomationConfig.DEFAULT_SAFE_JITTER,
    ): Boolean {
        logBotActionRequest(name)
        if (name == "DAILY_NEW") {
            val contentRect = ContentGeometryRegistry.current(sourceWidth, sourceHeight)?.contentRect
                ?: FrameNormalizer.contentTransform(sourceWidth, sourceHeight).contentRect
            val mapped = CoordinateMapper.logicalToPhysical(
                point.x,
                point.y,
                sourceWidth,
                sourceHeight,
            )
            logDiagnostic("[DAILY_NEW] logicalTarget=(${point.x},${point.y})")
            logDiagnostic("[DAILY_NEW] jitter=$jitter")
            logDiagnostic("[DAILY_NEW] sourceSize=${sourceWidth}x${sourceHeight}")
            logDiagnostic("[DAILY_NEW] contentRect=${rectText(contentRect)}")
            logDiagnostic("[DAILY_NEW] mappedPhysical=(${mapped.x},${mapped.y})")
            logDiagnostic(
                "[DAILY_NEW_ACTION_MAP] " +
                    "logical=(${point.x},${point.y}) " +
                    "contentRect=${rectText(contentRect)} " +
                    "physical=(${mapped.x},${mapped.y})"
            )
        }
        val result = synchronized(lock) {
            if (!running) return actionSkippedBotStopped(name)
            actionExecutor.tap(
                name,
                point,
                sourceWidth,
                sourceHeight,
                jitter,
                onGestureCompleted = if (name == "DAILY_NEW") {
                    { _, success ->
                        // result will be available after this lambda is created
                        logDiagnostic("[DAILY_NEW_GESTURE] callbackSuccess=$success")
                    }
                } else {
                    null
                },
            )
        }
        if (name == "DAILY_NEW") {
            logDiagnostic("[DAILY_NEW] dispatchAccepted=${result.accepted}")
            logDiagnostic("[DAILY_NEW_GESTURE] accepted=${result.accepted}")
        }
        return reportActionResult(result)
    }

    private fun logDailyNewPostTapResult(
        detector: StateDetector,
        frame: FrameSnapshot,
        detected: DetectionResult?,
    ) {
        val tappedGeneration = pendingDailyNewTapGeneration
        if (tappedGeneration < 0L || frame.generation <= tappedGeneration) return

        if (detected?.state == BotStage.DAILY_NEW) {
            logDiagnostic(
                "[DAILY_NEW] dispatchAccepted=true popupStillPresent=true " +
                    "score=${format(detected.score)} generation=${frame.generation}",
            )
        } else {
            val dailyNew = detector.detect(BotStage.DAILY_NEW, frame.mat)
            logDiagnostic(
                "[DAILY_NEW] dispatchAccepted=true popupGone=${!dailyNew.matched} " +
                    "postTapScore=${format(dailyNew.score)} " +
                    "nextState=${detected?.state?.name ?: "NO_MATCH"} generation=${frame.generation}",
            )
        }
        pendingDailyNewTapGeneration = -1L
    }

    private fun logPartyRunSeasonRewardsPostTapResult(
        detector: StateDetector,
        frame: FrameSnapshot,
        detected: DetectionResult?,
    ) {
        val tappedGeneration = pendingPartyRunSeasonRewardsTapGeneration
        if (tappedGeneration < 0L || frame.generation <= tappedGeneration) return

        if (detected?.state == BotStage.PARTY_RUN_SEASON_REWARDS) {
            logDiagnostic(
                "[PARTY_RUN_SEASON_REWARDS] dispatchAccepted=true popupStillPresent=true " +
                    "score=${format(detected.score)} generation=${frame.generation}",
            )
        } else {
            val rewards = detector.detect(BotStage.PARTY_RUN_SEASON_REWARDS, frame.mat)
            logDiagnostic(
                "[PARTY_RUN_SEASON_REWARDS] dispatchAccepted=true popupGone=${!rewards.matched} " +
                    "postTapScore=${format(rewards.score)} " +
                    "nextState=${detected?.state?.name ?: "NO_MATCH"} generation=${frame.generation}",
            )
        }
        pendingPartyRunSeasonRewardsTapGeneration = -1L
    }

    private fun performDetectedTap(
        name: String,
        point: LogicalPoint,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Boolean {
        logBotActionRequest(name)
        val result = synchronized(lock) {
            if (!running) return actionSkippedBotStopped(name)
            actionExecutor.tapDetectedPoint(name, point, sourceWidth, sourceHeight)
        }
        return reportActionResult(result)
    }

    private fun performCriticalTap(
        stage: BotStage,
        name: String,
        point: LogicalPoint,
        sourceWidth: Int,
        sourceHeight: Int,
        timing: FastPathTiming,
    ): Boolean {
        logBotActionRequest(name)
        val actionRequestedAtNanos = SystemClock.elapsedRealtimeNanos()
        val dispatchTime = java.util.concurrent.atomic.AtomicLong(actionRequestedAtNanos)
        val result = synchronized(lock) {
            if (!running) return actionSkippedBotStopped(name)
            actionExecutor.tap(
                name,
                point,
                sourceWidth,
                sourceHeight,
                priority = GesturePriority.CRITICAL,
                onGestureCompleted = { completedAtNanos, success ->
                    Log.i(
                        CookieRunBotApplication.TAG,
                            "[FAST_PATH] $stage callback_completed=$success " +
                            "dispatch_to_callback=${ms(completedAtNanos - dispatchTime.get())}ms " +
                            "capture_to_callback=${ms(completedAtNanos - timing.frameCapturedAtNanos)}ms",
                    )
                },
            )
        }
        dispatchTime.set(result.gestureDispatchedAtNanos)
        reportActionResult(result)
        val dispatchedAtNanos = result.gestureDispatchedAtNanos
        Log.i(
            CookieRunBotApplication.TAG,
            "[FAST_PATH] $stage " +
                "frame_age=${ms(actionRequestedAtNanos - timing.frameCapturedAtNanos)}ms " +
                "capture_to_publish=${ms(timing.latestFramePublishedAtNanos - timing.frameCapturedAtNanos)}ms " +
                "capture_to_detect=${ms(timing.detectionStartedAtNanos - timing.frameCapturedAtNanos)}ms " +
                "publish_to_detect=${ms(timing.detectionStartedAtNanos - timing.latestFramePublishedAtNanos)}ms " +
                "detect_time=${ms(timing.detectionMatchedAtNanos - timing.detectionStartedAtNanos)}ms " +
                "detect_to_action=${ms(actionRequestedAtNanos - timing.detectionMatchedAtNanos)}ms " +
                "action_to_dispatch=${ms(dispatchedAtNanos - actionRequestedAtNanos)}ms " +
                "total=${ms(dispatchedAtNanos - timing.frameCapturedAtNanos)}ms " +
                "accepted=${result.accepted}",
        )
        return result.accepted
    }

    private fun performCircleTap(name: String, point: LogicalPoint, sourceWidth: Int, sourceHeight: Int): Boolean {
        logBotActionRequest(name)
        val result = synchronized(lock) {
            if (!running) return actionSkippedBotStopped(name)
            actionExecutor.tapLogicalCircle(name, point, AutomationConfig.POST_RELAY_RADIUS, sourceWidth, sourceHeight)
        }
        return reportActionResult(result)
    }

    private fun performPostRelayTap(
        name: String,
        logicalReference: LogicalPoint,
        physicalTarget: PhysicalPoint,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Boolean {
        logBotActionRequest(name)
        val result = synchronized(lock) {
            if (!running) return actionSkippedBotStopped(name)
            actionExecutor.tapAbsolutePhysicalCircle(
                name,
                logicalReference,
                physicalTarget,
                AutomationConfig.POST_RELAY_RADIUS,
                sourceWidth,
                sourceHeight,
            )
        }
        return reportActionResult(result)
    }

    private fun performPhysicalCircleTap(name: String, point: LogicalPoint, sourceWidth: Int, sourceHeight: Int): Boolean {
        logBotActionRequest(name)
        val result = synchronized(lock) {
            if (!running) return actionSkippedBotStopped(name)
            actionExecutor.tapPhysicalCircle(name, point, AutomationConfig.FRIEND_PHYSICAL_RADIUS, sourceWidth, sourceHeight)
        }
        return reportActionResult(result)
    }

    private fun performDetectedPhysicalCircleTap(
        name: String,
        point: LogicalPoint,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Boolean {
        logBotActionRequest(name)
        val result = synchronized(lock) {
            if (!running) return actionSkippedBotStopped(name)
            actionExecutor.tapDetectedPhysicalCircle(
                name,
                point,
                AutomationConfig.FRIEND_PHYSICAL_RADIUS,
                sourceWidth,
                sourceHeight,
            )
        }
        return reportActionResult(result)
    }

    private fun reportActionResult(result: ActionResult): Boolean {
        DebugLogStore.log(
            "[ACTION] state=${result.actionName} logical=(${result.logicalX},${result.logicalY}) " +
                "physical=${result.physical?.let { "(${it.x},${it.y})" } ?: "unavailable"} " +
                "success=${result.accepted}",
        )
        PersistentTrace.log(
            "[ACTION] result=${result.actionName} accepted=${result.accepted} " +
                "logical=(${result.logicalX},${result.logicalY}) physical=${result.physical}",
        )
        listener.onAction(result)
        if (!result.accepted) {
            // A detection must be eligible again after Accessibility reconnects;
            // otherwise lastStage would suppress the same state forever.
            lastStage = null
            listener.onStateChanged("WAITING_FOR_ACTION_READINESS")
            Log.w(
                CookieRunBotApplication.TAG,
                "[ACTION] SKIPPED state=${result.actionName} reason=DISPATCH_NOT_ACCEPTED; state re-armed",
            )
            DebugLogStore.log(
                "[ACTION] state=${result.actionName} SKIPPED reason=DISPATCH_NOT_ACCEPTED",
            )
            if (result.actionName == "DAILY_NEW") {
                logDiagnostic("[DAILY_NEW_SKIP] reason=dispatch_not_accepted")
            }
            if (result.actionName == "PARTY_RUN_SEASON_REWARDS") {
                logDiagnostic("[PARTY_RUN_SEASON_REWARDS_SKIP] reason=dispatch_not_accepted")
            }
        }
        return result.accepted
    }

    private fun actionSkippedBotStopped(name: String): Boolean {
        Log.w(CookieRunBotApplication.TAG, "[ACTION] SKIPPED state=$name reason=BOT_NOT_RUNNING")
        DebugLogStore.log("[ACTION] state=$name SKIPPED reason=BOT_NOT_RUNNING")
        if (name == "DAILY_NEW") {
            logDiagnostic("[DAILY_NEW_SKIP] reason=bot_not_running")
        }
        if (name == "PARTY_RUN_SEASON_REWARDS") {
            logDiagnostic("[PARTY_RUN_SEASON_REWARDS_SKIP] reason=bot_not_running")
        }
        return false
    }

    private fun logBotActionRequest(name: String) {
        Log.i(
            CookieRunBotApplication.TAG,
            "[ACTION] requested state=$name botRunning=${isRunning()} " +
                "actionInProgress=${actionExecutor.isActionInProgress()}",
        )
    }

    private fun updateGroup(next: DetectionGroup) {
        group = next
        resetNoMatchEpisode()
        listener.onGroupChanged(next.name)
        Log.i(CookieRunBotApplication.TAG, "[BOT] detection_group=${next.name}")
        DebugLogStore.log("[STATE] detection_group=${next.name}")
    }

    private fun stagesFor(group: DetectionGroup): List<BotStage> = when (group) {
        DetectionGroup.PRE_GAME -> ALWAYS + PRE_GAME
        DetectionGroup.IN_GAME -> {
            if (
                options.useFastStart &&
                !gameStartDetectedForRound &&
                SystemClock.elapsedRealtimeNanos() < fastStartPollDeadlineNanos
            ) {
                FAST_START_PRIORITY + ALWAYS
            } else {
                IN_GAME + ALWAYS
            }
        }
        DetectionGroup.POST_GAME -> ALWAYS + POST_GAME
    }.filterNot { !options.detectRelic && it in RELIC_STAGES }

    private fun recoveryInterval(group: DetectionGroup): Long = when (group) {
        DetectionGroup.PRE_GAME -> 60_000L
        DetectionGroup.IN_GAME -> 300_000L
        DetectionGroup.POST_GAME -> 30_000L
    }

    private fun delayOrStop(ms: Long) {
        if (!isRunning()) throw InterruptedException()
        Thread.sleep(ms)
        if (!isRunning()) throw InterruptedException()
    }

    private fun randomMs(min: Int, max: Int): Long = Random.nextLong(min.toLong(), max.toLong() + 1L)

    private fun logDetection(result: DetectionResult) {
        DebugLogStore.log(
            "[DETECT] state=${result.state} score=${format(result.score)} matched=${result.matched}",
        )
        PersistentTrace.log(
            "[DETECT] state=${result.state} score=${format(result.score)} matched=${result.matched}",
        )
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogAt >= 1000L) {
            Log.i(CookieRunBotApplication.TAG, "[DETECT] state=${result.state} score=${format(result.score)} matched=${result.matched}")
            lastLogAt = now
        }
    }

    private fun logSafety(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSafetyLogAt >= 1000L) {
            Log.w(CookieRunBotApplication.TAG, message)
            DebugLogStore.log(message)
            lastSafetyLogAt = now
        }
    }

    private fun handleNoMatchDiagnostics(detector: StateDetector, frame: FrameSnapshot) {
        if (group != DetectionGroup.PRE_GAME) {
            resetNoMatchEpisode()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (
            noMatchStartedAt == 0L ||
            now - lastNoMatchObservedAt > NO_MATCH_CONTINUITY_GAP_MS
        ) {
            noMatchStartedAt = now
            lastNoMatchDiagnosticAt = 0L
            noMatchFrameSaved = false
            noMatchDebugFrameRequested = false
        }
        lastNoMatchObservedAt = now

        if (now - lastNoMatchDiagnosticAt >= NO_MATCH_DIAGNOSTIC_INTERVAL_MS) {
            val scores = detector.scoreStages(PRE_GAME_DIAGNOSTIC_STAGES, frame.mat)
            val best = scores.maxByOrNull(DetectionResult::score)
            scores.firstOrNull { it.state == BotStage.DAILY_NEW }?.let { dailyNew ->
                logDiagnostic(
                    "[DAILY_NEW] noMatch score=${format(dailyNew.score)} " +
                        "threshold=${format(AutomationConfig.MATCH_THRESHOLD)}",
                )
            }
            val scoreText = scores.joinToString(" ") { result ->
                "${result.state.name}=${format(result.score)}"
            }
            logDiagnostic(
                "[DETECT_NO_MATCH] group=${group.name} " +
                    "frameSequence=${frame.generation} " +
                    "frameSize=${frame.mat.cols()}x${frame.mat.rows()} " +
                    "bestCandidate=${best?.state?.name ?: "NONE"} " +
                    "bestScore=${best?.let { format(it.score) } ?: "none"} " +
                    scoreText,
            )
            lastNoMatchDiagnosticAt = now
        }

        if (
            options.saveDebugImages &&
            !noMatchFrameSaved &&
            now - noMatchStartedAt >= NO_MATCH_FRAME_DELAY_MS
        ) {
            if (!noMatchDebugFrameRequested) {
                // Ask the capture producer to copy one raw+normalized pair. Raw frames
                // are deliberately not copied on every video frame.
                LatestFrameProvider.requestDebugFramePair(frame.generation)
                noMatchDebugFrameRequested = true
                return
            }
            val pair = LatestFrameProvider.consumeDebugFramePair() ?: return
            // Mark first so a storage failure cannot cause an encode attempt on every frame.
            noMatchFrameSaved = true
            try {
                runCatching { noMatchDebugFrameSaver.save(pair) }
                    .onSuccess { paths ->
                        logDiagnostic(
                            "[DEBUG_FRAME] rawSaved=${paths.raw} normalizedSaved=${paths.normalized} " +
                                "sourceSize=${pair.rawMat.cols()}x${pair.rawMat.rows()} " +
                                "contentRect=${rectText(pair.contentTransform.contentRect)} " +
                                "normalizedSize=${pair.normalizedMat.cols()}x${pair.normalizedMat.rows()}",
                        )
                    }
                    .onFailure { error ->
                        logDiagnostic(
                            "[DEBUG_FRAME] saveFailed=${error.message} " +
                                "sourceSize=${pair.rawMat.cols()}x${pair.rawMat.rows()} " +
                                "contentRect=${rectText(pair.contentTransform.contentRect)} " +
                                "normalizedSize=${pair.normalizedMat.cols()}x${pair.normalizedMat.rows()}",
                        )
                    }
            } finally {
                pair.release()
            }
        }
    }

    private fun resetNoMatchEpisode() {
        noMatchStartedAt = 0L
        lastNoMatchObservedAt = 0L
        lastNoMatchDiagnosticAt = 0L
        noMatchFrameSaved = false
        noMatchDebugFrameRequested = false
        LatestFrameProvider.cancelDebugFrameRequest()
    }

    private fun logDetectorFrame(frame: FrameSnapshot) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDetectFrameLogAt < DETECT_FRAME_LOG_INTERVAL_MS) return
        logDiagnostic(
            "[DETECT_FRAME] sourceSize=${frame.sourceWidth}x${frame.sourceHeight} " +
                "contentRect=${rectText(frame.contentTransform.contentRect)} " +
                "normalizedSize=${frame.mat.cols()}x${frame.mat.rows()}",
        )
        lastDetectFrameLogAt = now
    }

    private fun rectText(rect: Rect): String =
        "(${rect.x},${rect.y},${rect.x + rect.width},${rect.y + rect.height})"

    private fun logDiagnostic(message: String) {
        Log.i(CookieRunBotApplication.TAG, message)
        DebugLogStore.log(message)
        PersistentTrace.log(message)
    }

    private fun format(value: Double) = String.format(Locale.US, "%.4f", value)
    private fun ms(nanos: Long): String =
        String.format(Locale.US, "%.2f", nanos.coerceAtLeast(0L) / 1_000_000.0)

    companion object {
        private val ALWAYS = listOf(BotStage.CONNECTION_LOST, BotStage.INACTIVE)
        private val RELIC_STAGES = setOf(BotStage.RELIC_COMPLETE, BotStage.RELIC_CLAIM)
        private val REWARD_PRE_GAME_POPUPS = setOf(
            BotStage.CONGRATULATIONS,
            BotStage.LEVEL_UP,
            BotStage.DAILY_CHECKIN,
            BotStage.DAILY_CHECKIN_BOOST_SET,
            BotStage.DAILY_TREASURE,
            BotStage.DAILY_NEW,
            BotStage.ENTER_LEAGUE,
            BotStage.LEAGUE_RESULTS,
            BotStage.PREVIOUS_RANK_RESULTS,
            BotStage.TOO_MANY_TREASURES,
            BotStage.RELIC_COMPLETE,
            BotStage.RELIC_CLAIM,
            BotStage.PARTY_RUN,
            BotStage.PARTY_RUN_SEASON_REWARDS,
            BotStage.GAME_SETTINGS,
        )
        private val PRE_GAME = listOf(
            BotStage.CONGRATULATIONS, BotStage.LEVEL_UP, BotStage.RELIC_COMPLETE,
            BotStage.RELIC_CLAIM, BotStage.DAILY_CHECKIN, BotStage.DAILY_CHECKIN_BOOST_SET,
            BotStage.DAILY_TREASURE, BotStage.DAILY_NEW, BotStage.ENTER_LEAGUE,
            BotStage.LEAGUE_RESULTS, BotStage.PREVIOUS_RANK_RESULTS,
            BotStage.TOO_MANY_TREASURES, BotStage.PARTY_RUN, BotStage.PARTY_RUN_SEASON_REWARDS, BotStage.GAME_SETTINGS,
            BotStage.MAINMENU, BotStage.PURCHASE_ITEM,
        )
        private val IN_GAME = listOf(BotStage.GAME_RELAY, BotStage.GAME_COMPLETE, BotStage.GAME_START)
        private val FAST_START_PRIORITY = listOf(BotStage.GAME_START)
        private const val IN_GAME_FRAME_WAIT_MS = 500L
        private const val DESIRED_BOOST_TIMEOUT_MS = 30_000L
        private const val BOOST_FRAME_WAIT_TIMEOUT_MS = 500L
        private const val BOOST_PROGRESS_LOG_INTERVAL_MS = 1_000L
        // Logical normalized 1280x720 coordinates: (left, top, right, bottom).
        private val DESIRED_BOOST_REGION = Rect(701, 505, 1091 - 701, 578 - 505)
        private const val FAST_START_PRIORITY_WINDOW_NANOS = 8_000_000_000L
        private const val FRIEND_RESCAN_TIMEOUT_MS = 2_500L
        private const val FRIEND_POST_TAP_SETTLE_MS = 300L
        private const val READINESS_POLL_INTERVAL_MS = 100L
        private const val READINESS_LOG_INTERVAL_MS = 1_000L
        private const val NO_MATCH_DIAGNOSTIC_INTERVAL_MS = 1_500L
        private const val NO_MATCH_FRAME_DELAY_MS = 3_000L
        private const val DETECT_FRAME_LOG_INTERVAL_MS = 2_000L
        private const val NO_MATCH_CONTINUITY_GAP_MS = 1_000L
        private const val POPUP_FRESH_FRAME_TIMEOUT_MS = 2_500L
        private const val MAX_CONSECUTIVE_REWARD_POPUP_ACTIONS = 3
        private val PRE_GAME_DIAGNOSTIC_STAGES = listOf(
            BotStage.MAINMENU,
            BotStage.PURCHASE_ITEM,
            BotStage.PARTY_RUN,
            BotStage.PARTY_RUN_SEASON_REWARDS,
            BotStage.GAME_SETTINGS,
            BotStage.DAILY_CHECKIN,
            BotStage.DAILY_CHECKIN_BOOST_SET,
            BotStage.DAILY_TREASURE,
            BotStage.DAILY_NEW,
            BotStage.CONGRATULATIONS,
            BotStage.LEVEL_UP,
            BotStage.RELIC_COMPLETE,
            BotStage.RELIC_CLAIM,
            BotStage.CONNECTION_LOST,
            BotStage.INACTIVE,
        )
        private val POST_GAME = listOf(BotStage.ANTI_BOT, BotStage.OVERTAKE_BREAK_SCORE, BotStage.MYSTERY_BOX, BotStage.MAINMENU)
        private val ALL_STAGE_ORDER = listOf(
            BotStage.GAME_START, BotStage.GAME_RELAY, BotStage.ANTI_BOT,
            BotStage.CONNECTION_LOST, BotStage.INACTIVE, BotStage.GAME_COMPLETE,
            BotStage.OVERTAKE_BREAK_SCORE, BotStage.MYSTERY_BOX, BotStage.CONGRATULATIONS,
            BotStage.RELIC_COMPLETE, BotStage.RELIC_CLAIM, BotStage.TOO_MANY_TREASURES,
            BotStage.MAINMENU, BotStage.PARTY_RUN, BotStage.PARTY_RUN_SEASON_REWARDS, BotStage.GAME_SETTINGS,
            BotStage.PURCHASE_ITEM, BotStage.LEVEL_UP, BotStage.DAILY_CHECKIN,
            BotStage.DAILY_CHECKIN_BOOST_SET, BotStage.DAILY_TREASURE,
            BotStage.DAILY_NEW, BotStage.ENTER_LEAGUE, BotStage.LEAGUE_RESULTS,
            BotStage.PREVIOUS_RANK_RESULTS,
        )
    }
}

data class BotControllerDiagnostics(
    val running: Boolean,
    val jobActive: Boolean,
    val actionInProgress: Boolean,
    val workerAlive: Boolean,
    val lastProcessedFrameSequence: Long,
    val lastDetectionAttemptAgeMs: Long?,
    val group: String,
)

private data class FastPathTiming(
    val frameCapturedAtNanos: Long,
    val latestFramePublishedAtNanos: Long,
    val detectionStartedAtNanos: Long,
    val detectionMatchedAtNanos: Long,
)
