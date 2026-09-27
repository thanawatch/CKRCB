package com.example.cookierunbot.automation

import android.content.Context
import com.example.cookierunbot.detection.DetectionResult

/** Process-level owner so automation is not tied to MainActivity lifecycle. */
object BotRuntime : BotController.Listener {
    private val lock = Any()
    private var controller: BotController? = null
    @Volatile private var listener: BotController.Listener? = null

    fun initialize(context: Context) = synchronized(lock) {
        if (controller == null) controller = BotController(context.applicationContext, this)
    }

    fun attach(listener: BotController.Listener) {
        this.listener = listener
        listener.onRunningChanged(isRunning())
    }

    fun detach(listener: BotController.Listener) {
        if (this.listener === listener) this.listener = null
    }

    fun start(options: BotOptions) = controller?.start(options) ?: Unit
    fun stop() = controller?.stop() ?: Unit
    fun isRunning(): Boolean = controller?.isRunning() == true
    fun requestFriendLifeTest(): FriendLifeTestRequest =
        controller?.requestFriendLifeTest() ?: FriendLifeTestRequest.BOT_STOPPED
    fun requestWatchdogRecoveryTest(): WatchdogRecoveryTestRequest =
        if (controller?.isRunning() == true) {
            GameProgressWatchdogManager.requestRecoveryTest()
        } else {
            WatchdogRecoveryTestRequest.BOT_STOPPED
        }
    fun friendLifeStatus(): FriendLifeDashboardStatus? = controller?.friendLifeStatus()
    fun diagnostics(): BotControllerDiagnostics = controller?.diagnostics()
        ?: BotControllerDiagnostics(
            running = false,
            jobActive = false,
            actionInProgress = false,
            workerAlive = false,
            lastProcessedFrameSequence = -1L,
            lastDetectionAttemptAgeMs = null,
            group = "NONE",
            currentHandler = "NONE",
            minimumFreshFrameGeneration = -1L,
            gestureBusy = false,
            pendingPopupAction = "NONE",
            calibrationBlockedAction = null,
            friendLifeWorkflowActive = false,
            friendLifeLastProgressAgeMs = null,
            roundNumber = 0,
        )

    override fun onRunningChanged(running: Boolean) = listener?.onRunningChanged(running) ?: Unit
    override fun onStateChanged(state: String) = listener?.onStateChanged(state) ?: Unit
    override fun onGroupChanged(group: String) = listener?.onGroupChanged(group) ?: Unit
    override fun onDetection(stage: BotStage, result: DetectionResult) =
        listener?.onDetection(stage, result) ?: Unit
    override fun onAction(result: ActionResult) = listener?.onAction(result) ?: Unit
}
