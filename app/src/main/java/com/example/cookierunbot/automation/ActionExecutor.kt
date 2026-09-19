package com.example.cookierunbot.automation

import android.content.Context
import android.util.Log
import android.os.SystemClock
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.diagnostics.PersistentTrace
import com.example.cookierunbot.diagnostics.DebugLogStore
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class ActionExecutor(private val context: Context? = null) {
    fun isActionInProgress(): Boolean = GestureArbiter.isBusy()

    /** Releases a physical-tap lease when its accessibility callback times out. */
    fun releaseGestureLease() = GestureArbiter.release()

    fun tap(
        actionName: String,
        point: LogicalPoint,
        sourceWidth: Int,
        sourceHeight: Int,
        jitter: Int = AutomationConfig.DEFAULT_SAFE_JITTER,
        note: String = actionName,
        priority: GesturePriority = GesturePriority.NORMAL,
        onGestureCompleted: ((completedAtNanos: Long, success: Boolean) -> Unit)? = null,
    ): ActionResult = executeTap(
        actionName,
        point.x,
        point.y,
        jitter,
        sourceWidth,
        sourceHeight,
        note,
        priority,
        onGestureCompleted,
    )

    /** Dispatches an already-physical phone coordinate without content mapping or scaling. */
    fun tapPhysical(
        actionName: String,
        point: PhysicalPoint,
        onGestureCompleted: ((completedAtNanos: Long, success: Boolean) -> Unit)? = null,
    ): ActionResult {
        val requestedAt = SystemClock.elapsedRealtimeNanos()
        if (!logActionReadiness(actionName) || !GestureArbiter.acquire(GesturePriority.NORMAL)) {
            return ActionResult(actionName, false, point.x, point.y, point, requestedAt, SystemClock.elapsedRealtimeNanos())
        }
        val dispatch = try {
            BotAccessibilityService.dispatchPhysicalPoint(
                point.x,
                point.y,
                "[ACTION] $actionName",
            ) { completedAt, success ->
                GestureArbiter.release()
                onGestureCompleted?.invoke(completedAt, success)
            }.also { if (!it.accepted) GestureArbiter.release() }
        } catch (error: Exception) {
            GestureArbiter.release()
            throw error
        }
        return ActionResult(actionName, dispatch.accepted, point.x, point.y, dispatch.physical, requestedAt, dispatch.dispatchedAtNanos)
    }

    fun tapDetectedPoint(
        actionName: String,
        point: LogicalPoint,
        sourceWidth: Int,
        sourceHeight: Int,
    ): ActionResult = executeTap(
        actionName,
        point.x,
        point.y,
        0,
        sourceWidth,
        sourceHeight,
        actionName,
        GesturePriority.NORMAL,
        null,
        calibratedCoordinates = false,
    )

    fun tapCalibrated(
        actionName: String,
        point: LogicalPoint,
        sourceWidth: Int,
        sourceHeight: Int,
        priority: GesturePriority = GesturePriority.NORMAL,
    ): ActionResult = executeTap(
        actionName,
        point.x,
        point.y,
        0,
        sourceWidth,
        sourceHeight,
        actionName,
        priority,
        null,
        calibratedCoordinates = true,
    )

    fun tapLogicalCircle(
        actionName: String,
        point: LogicalPoint,
        radius: Double,
        sourceWidth: Int,
        sourceHeight: Int,
    ): ActionResult {
        val angle = Random.nextDouble(0.0, 2.0 * PI)
        val distance = radius * sqrt(Random.nextDouble())
        val varied = LogicalPoint(
            point.x + (distance * cos(angle)).roundToInt(),
            point.y + (distance * sin(angle)).roundToInt(),
        )
        return tap(actionName, varied, sourceWidth, sourceHeight, jitter = 0)
    }

    fun tapPhysicalCircle(
        actionName: String,
        logicalPoint: LogicalPoint,
        physicalRadius: Double,
        sourceWidth: Int,
        sourceHeight: Int,
    ): ActionResult {
        val base = CoordinateMapper.calibratedLogicalToPhysical(
            logicalPoint.x, logicalPoint.y, sourceWidth, sourceHeight,
        )
        val angle = Random.nextDouble(0.0, 2.0 * PI)
        val distance = physicalRadius * sqrt(Random.nextDouble())
        val x = (base.x + distance * cos(angle)).roundToInt().coerceIn(0, sourceWidth - 1)
        val y = (base.y + distance * sin(angle)).roundToInt().coerceIn(0, sourceHeight - 1)
        if (!logActionReadiness(actionName)) {
            return ActionResult(
                actionName,
                false,
                logicalPoint.x,
                logicalPoint.y,
                PhysicalPoint(x, y),
            )
        }
        Log.i(CookieRunBotApplication.TAG, "[ACTION] dispatch start state=$actionName")
        val dispatch = BotAccessibilityService.dispatchPhysicalPoint(x, y, "[ACTION] $actionName")
        return ActionResult(
            actionName,
            dispatch.accepted,
            logicalPoint.x,
            logicalPoint.y,
            dispatch.physical,
            dispatch.requestedAtNanos,
            dispatch.dispatchedAtNanos,
        )
    }

    fun tapDetectedPhysicalCircle(
        actionName: String,
        detectorPoint: LogicalPoint,
        physicalRadius: Double,
        sourceWidth: Int,
        sourceHeight: Int,
    ): ActionResult {
        val base = CoordinateMapper.detectorLogicalToPhysical(
            detectorPoint.x, detectorPoint.y, sourceWidth, sourceHeight,
        )
        val angle = Random.nextDouble(0.0, 2.0 * PI)
        val distance = physicalRadius * sqrt(Random.nextDouble())
        val x = (base.x + distance * cos(angle)).roundToInt().coerceIn(0, sourceWidth - 1)
        val y = (base.y + distance * sin(angle)).roundToInt().coerceIn(0, sourceHeight - 1)
        if (!logActionReadiness(actionName)) {
            return ActionResult(actionName, false, detectorPoint.x, detectorPoint.y, PhysicalPoint(x, y))
        }
        Log.i(CookieRunBotApplication.TAG, "[ACTION] dispatch start state=$actionName mapping=DETECTOR_CONTENT")
        val dispatch = BotAccessibilityService.dispatchPhysicalPoint(x, y, "[ACTION] $actionName")
        return ActionResult(
            actionName,
            dispatch.accepted,
            detectorPoint.x,
            detectorPoint.y,
            dispatch.physical,
            dispatch.requestedAtNanos,
            dispatch.dispatchedAtNanos,
        )
    }

    /** Tap a phone-pixel target that was explicitly calibrated outside logical content mapping. */
    fun tapAbsolutePhysicalCircle(
        actionName: String,
        logicalReference: LogicalPoint,
        physicalPoint: PhysicalPoint,
        physicalRadius: Double,
        sourceWidth: Int,
        sourceHeight: Int,
    ): ActionResult {
        val angle = Random.nextDouble(0.0, 2.0 * PI)
        val distance = physicalRadius * sqrt(Random.nextDouble())
        val x = (physicalPoint.x + distance * cos(angle)).roundToInt().coerceIn(0, sourceWidth - 1)
        val y = (physicalPoint.y + distance * sin(angle)).roundToInt().coerceIn(0, sourceHeight - 1)
        Log.i(
            CookieRunBotApplication.TAG,
            "[ACTION] $actionName absolutePhysicalBase=(${physicalPoint.x},${physicalPoint.y}) " +
                "radius=$physicalRadius selected=($x,$y)",
        )
        if (!logActionReadiness(actionName)) {
            return ActionResult(
                actionName,
                false,
                logicalReference.x,
                logicalReference.y,
                PhysicalPoint(x, y),
            )
        }
        Log.i(CookieRunBotApplication.TAG, "[ACTION] dispatch start state=$actionName")
        val dispatch = BotAccessibilityService.dispatchPhysicalPoint(x, y, "[ACTION] $actionName")
        return ActionResult(
            actionName,
            dispatch.accepted,
            logicalReference.x,
            logicalReference.y,
            dispatch.physical,
            dispatch.requestedAtNanos,
            dispatch.dispatchedAtNanos,
        )
    }

    private fun executeTap(
        actionName: String,
        baseX: Int,
        baseY: Int,
        jitter: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        note: String,
        priority: GesturePriority,
        onGestureCompleted: ((completedAtNanos: Long, success: Boolean) -> Unit)?,
        calibratedCoordinates: Boolean = false,
    ): ActionResult {
        val logicalX = baseX + Random.nextInt(-jitter, jitter + 1)
        val logicalY = baseY + Random.nextInt(-jitter, jitter + 1)
        Log.i(CookieRunBotApplication.TAG, "[ACTION] $actionName action started ($note)")
        Log.i(
            CookieRunBotApplication.TAG,
            "[ACTION] base=($baseX,$baseY) jitter=±$jitter",
        )

        val actionRequestedAtNanos = SystemClock.elapsedRealtimeNanos()
        if (!logActionReadiness(actionName)) {
            return ActionResult(
                actionName,
                false,
                logicalX,
                logicalY,
                null,
                actionRequestedAtNanos,
                SystemClock.elapsedRealtimeNanos(),
            )
        }
        if (!GestureArbiter.acquire(priority)) {
            Log.w(
                CookieRunBotApplication.TAG,
                "[ACTION] SKIPPED state=$actionName reason=ACTION_IN_PROGRESS",
            )
            DebugLogStore.log("[ACTION] state=$actionName SKIPPED reason=ACTION_IN_PROGRESS")
            return ActionResult(
                actionName,
                false,
                logicalX,
                logicalY,
                null,
                actionRequestedAtNanos,
                SystemClock.elapsedRealtimeNanos(),
            )
        }
        Log.i(CookieRunBotApplication.TAG, "[ACTION] dispatch start state=$actionName")
        DebugLogStore.log("[ACTION] state=$actionName dispatch=start")
        val dispatch = try {
            BotAccessibilityService.dispatchLogicalTap(
                logicalX = logicalX,
                logicalY = logicalY,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                logPrefix = "[ACTION]",
                calibratedCoordinates = calibratedCoordinates,
                onCompleted = { completedAtNanos, success ->
                    GestureArbiter.release()
                    onGestureCompleted?.invoke(completedAtNanos, success)
                },
            ).also {
                if (!it.accepted) GestureArbiter.release()
            }
        } catch (error: Exception) {
            GestureArbiter.release()
            throw error
        }
        return ActionResult(
            actionName = actionName,
            accepted = dispatch.accepted,
            logicalX = logicalX,
            logicalY = logicalY,
            physical = dispatch.physical,
            actionRequestedAtNanos = actionRequestedAtNanos,
            gestureDispatchedAtNanos = dispatch.dispatchedAtNanos,
        )
    }

    private fun logActionReadiness(actionName: String): Boolean {
        val enabled = context?.let(BotAccessibilityService::isEnabled)
            ?: BotAccessibilityService.isConnected()
        val connected = BotAccessibilityService.isConnected()
        val instancePresent = BotAccessibilityService.hasServiceInstance()
        PersistentTrace.log(
            "[ACTION] requested=$actionName enabled=$enabled connected=$connected instance=$instancePresent",
        )
        Log.i(CookieRunBotApplication.TAG, "[ACTION] requested state=$actionName")
        Log.i(
            CookieRunBotApplication.TAG,
            "[ACTION] accessibilityEnabled=$enabled " +
                "accessibilityConnected=$connected serviceInstance=$instancePresent",
        )
        if (!enabled) {
            Log.w(
                CookieRunBotApplication.TAG,
                "[ACTION] SKIPPED state=$actionName reason=ACCESSIBILITY_PERMISSION_DISABLED",
            )
            PersistentTrace.log("[ACTION] skipped=$actionName reason=ACCESSIBILITY_PERMISSION_DISABLED")
            DebugLogStore.log(
                "[ACTION] state=$actionName SKIPPED reason=ACCESSIBILITY_PERMISSION_DISABLED",
            )
            return false
        }
        if (!connected || !instancePresent) {
            Log.w(
                CookieRunBotApplication.TAG,
                "[ACTION] SKIPPED state=$actionName reason=ACCESSIBILITY_SERVICE_DISCONNECTED",
            )
            PersistentTrace.log("[ACTION] skipped=$actionName reason=ACCESSIBILITY_SERVICE_DISCONNECTED")
            DebugLogStore.log(
                "[ACTION] state=$actionName SKIPPED reason=ACCESSIBILITY_SERVICE_DISCONNECTED",
            )
            return false
        }
        return true
    }
}

data class ActionResult(
    val actionName: String,
    val accepted: Boolean,
    val logicalX: Int,
    val logicalY: Int,
    val physical: PhysicalPoint?,
    val actionRequestedAtNanos: Long = 0L,
    val gestureDispatchedAtNanos: Long = 0L,
)

enum class GesturePriority { NORMAL, BACKGROUND, CRITICAL }

/** Gives state-transition taps precedence over the background jump worker. */
private object GestureArbiter {
    private const val MAX_GESTURE_LEASE_MS = 500L
    private const val CRITICAL_WAIT_MS = 300L
    private val monitor = Object()
    private var busy = false
    private var busySinceNanos = 0L
    private var criticalWaiting = false

    fun acquire(priority: GesturePriority): Boolean = synchronized(monitor) {
        expireStaleLease()
        if (priority == GesturePriority.BACKGROUND) {
            if (busy || criticalWaiting) return false
            markBusy()
            return true
        }

        if (priority == GesturePriority.CRITICAL) criticalWaiting = true
        try {
            val deadline = SystemClock.elapsedRealtimeNanos() + CRITICAL_WAIT_MS * 1_000_000L
            while (busy) {
                val remaining = deadline - SystemClock.elapsedRealtimeNanos()
                if (remaining <= 0L) return false
                monitor.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                expireStaleLease()
            }
            markBusy()
            true
        } finally {
            if (priority == GesturePriority.CRITICAL) criticalWaiting = false
        }
    }

    fun release() = synchronized(monitor) {
        busy = false
        busySinceNanos = 0L
        monitor.notifyAll()
    }

    fun isBusy(): Boolean = synchronized(monitor) {
        expireStaleLease()
        busy
    }

    private fun markBusy() {
        busy = true
        busySinceNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun expireStaleLease() {
        if (busy && SystemClock.elapsedRealtimeNanos() - busySinceNanos > MAX_GESTURE_LEASE_MS * 1_000_000L) {
            busy = false
            busySinceNanos = 0L
            monitor.notifyAll()
        }
    }
}
