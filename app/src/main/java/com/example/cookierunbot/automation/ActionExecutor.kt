package com.example.cookierunbot.automation

import android.content.Context
import com.example.cookierunbot.diagnostics.RobotLog as Log
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

    fun swipeLogical(
        actionName: String,
        start: LogicalPoint,
        end: LogicalPoint,
        sourceWidth: Int,
        sourceHeight: Int,
        durationMs: Long,
        callbackTimeoutMs: Long,
        onGestureCompleted: (success: Boolean, timedOut: Boolean) -> Unit,
    ): ActionResult {
        if (!logActionReadiness(actionName)) {
            return ActionResult(actionName, false, start.x, start.y, null)
        }
        val lease = GestureArbiter.acquire(GesturePriority.NORMAL)
            ?: return ActionResult(actionName, false, start.x, start.y, null)
        val completion = GestureCompletionContext(
            lease,
            callbackTimeoutMs,
        ) { _, success, timedOut -> onGestureCompleted(success, timedOut) }
        completion.armTimeout(actionName)
        val accepted = try {
            BotAccessibilityService.dispatchLogicalSwipe(
                start,
                end,
                sourceWidth,
                sourceHeight,
                durationMs,
                "[ACTION] $actionName",
            ) { completedAt, success -> completion.complete(completedAt, success) }
        } catch (error: Exception) {
            completion.cancel()
            throw error
        }
        if (!accepted) completion.cancel()
        return ActionResult(
            actionName,
            accepted,
            start.x,
            start.y,
            CoordinateMapper.logicalToPhysical(start.x, start.y, sourceWidth, sourceHeight),
            pendingGesture = completion,
        )
    }

    fun swipeLogicalPath(
        actionName: String,
        points: List<LogicalPoint>,
        sourceWidth: Int,
        sourceHeight: Int,
        durationMs: Long,
        callbackTimeoutMs: Long,
        onGestureCompleted: (success: Boolean, timedOut: Boolean) -> Unit,
    ): ActionResult {
        require(points.size >= 2) { "Swipe path requires at least two points" }
        val start = points.first()
        if (!logActionReadiness(actionName)) {
            return ActionResult(actionName, false, start.x, start.y, null)
        }
        val lease = GestureArbiter.acquire(GesturePriority.NORMAL)
            ?: return ActionResult(actionName, false, start.x, start.y, null)
        val completion = GestureCompletionContext(
            lease,
            callbackTimeoutMs,
        ) { _, success, timedOut -> onGestureCompleted(success, timedOut) }
        completion.armTimeout(actionName)
        val accepted = try {
            BotAccessibilityService.dispatchLogicalPath(
                points,
                sourceWidth,
                sourceHeight,
                durationMs,
                "[ACTION] $actionName",
            ) { completedAt, success -> completion.complete(completedAt, success) }
        } catch (error: Exception) {
            completion.cancel()
            throw error
        }
        if (!accepted) completion.cancel()
        return ActionResult(
            actionName,
            accepted,
            start.x,
            start.y,
            CoordinateMapper.logicalToPhysical(start.x, start.y, sourceWidth, sourceHeight),
            pendingGesture = completion,
        )
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
        callbackTimeoutMs: Long = 0L,
        onGestureCompleted: ((success: Boolean, timedOut: Boolean) -> Unit)? = null,
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
        val lease = GestureArbiter.acquire(GesturePriority.NORMAL)
            ?: return ActionResult(actionName, false, logicalPoint.x, logicalPoint.y, PhysicalPoint(x, y))
        Log.i(CookieRunBotApplication.TAG, "[ACTION] dispatch start state=$actionName")
        val completion = GestureCompletionContext(lease, callbackTimeoutMs) { _, success, timedOut ->
            onGestureCompleted?.invoke(success, timedOut)
        }
        completion.armTimeout(actionName)
        val dispatch = BotAccessibilityService.dispatchPhysicalPoint(
            x,
            y,
            "[ACTION] $actionName",
        ) { completedAt, success -> completion.complete(completedAt, success) }.also {
            if (!it.accepted) completion.cancel()
        }
        return ActionResult(
            actionName,
            dispatch.accepted,
            logicalPoint.x,
            logicalPoint.y,
            dispatch.physical,
            dispatch.requestedAtNanos,
            dispatch.dispatchedAtNanos,
            completion,
        )
    }

    fun tapDetectedPhysicalCircle(
        actionName: String,
        detectorPoint: LogicalPoint,
        physicalRadius: Double,
        sourceWidth: Int,
        sourceHeight: Int,
        callbackTimeoutMs: Long = 0L,
        onGestureCompleted: ((success: Boolean, timedOut: Boolean) -> Unit)? = null,
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
        val lease = GestureArbiter.acquire(GesturePriority.NORMAL)
            ?: return ActionResult(actionName, false, detectorPoint.x, detectorPoint.y, PhysicalPoint(x, y))
        Log.i(CookieRunBotApplication.TAG, "[ACTION] dispatch start state=$actionName mapping=DETECTOR_CONTENT")
        val completion = GestureCompletionContext(lease, callbackTimeoutMs) { _, success, timedOut ->
            onGestureCompleted?.invoke(success, timedOut)
        }
        completion.armTimeout(actionName)
        val dispatch = BotAccessibilityService.dispatchPhysicalPoint(
            x,
            y,
            "[ACTION] $actionName",
        ) { completedAt, success -> completion.complete(completedAt, success) }.also {
            if (!it.accepted) completion.cancel()
        }
        return ActionResult(
            actionName,
            dispatch.accepted,
            detectorPoint.x,
            detectorPoint.y,
            dispatch.physical,
            dispatch.requestedAtNanos,
            dispatch.dispatchedAtNanos,
            completion,
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
        callbackTimeoutMs: Long = 0L,
        onGestureCompleted: ((completedAtNanos: Long, success: Boolean, timedOut: Boolean) -> Unit)? = null,
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
        val lease = GestureArbiter.acquire(GesturePriority.NORMAL) ?: run {
            Log.w(
                CookieRunBotApplication.TAG,
                "[ACTION] SKIPPED state=$actionName reason=ACTION_IN_PROGRESS",
            )
            DebugLogStore.log("[ACTION] state=$actionName SKIPPED reason=ACTION_IN_PROGRESS")
            if (actionName == "DAILY_NEW") {
                Log.i(
                    CookieRunBotApplication.TAG,
                    "[DAILY_NEW_SKIP] reason=action_in_progress"
                )
                DebugLogStore.log("[DAILY_NEW_SKIP] reason=action_in_progress")
            }
            if (actionName == "PARTY_RUN_SEASON_REWARDS") {
                Log.i(
                    CookieRunBotApplication.TAG,
                    "[PARTY_RUN_SEASON_REWARDS_SKIP] reason=action_in_progress"
                )
                DebugLogStore.log("[PARTY_RUN_SEASON_REWARDS_SKIP] reason=action_in_progress")
            }
            return ActionResult(
                actionName,
                false,
                logicalReference.x,
                logicalReference.y,
                PhysicalPoint(x, y),
            )
        }
        Log.i(CookieRunBotApplication.TAG, "[ACTION] dispatch start state=$actionName")
        DebugLogStore.log("[ACTION] state=$actionName dispatch=start")

        val completion = GestureCompletionContext(lease, callbackTimeoutMs, onGestureCompleted)
        completion.armTimeout(actionName)

        val dispatch = try {
            BotAccessibilityService.dispatchPhysicalPoint(
                x,
                y,
                "[ACTION]",
                onCompleted = { completedAtNanos, success ->
                    completion.complete(completedAtNanos, success)
                },
            ).also {
                if (!it.accepted) {
                    completion.cancel()
                }
            }
        } catch (error: Exception) {
            completion.cancel()
            throw error
        }
        return ActionResult(
            actionName,
            dispatch.accepted,
            logicalReference.x,
            logicalReference.y,
            dispatch.physical,
            dispatch.requestedAtNanos,
            dispatch.dispatchedAtNanos,
            completion,
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
        val lease = GestureArbiter.acquire(priority) ?: run {
            Log.w(
                CookieRunBotApplication.TAG,
                "[ACTION] SKIPPED state=$actionName reason=ACTION_IN_PROGRESS",
            )
            DebugLogStore.log("[ACTION] state=$actionName SKIPPED reason=ACTION_IN_PROGRESS")
            if (actionName == "DAILY_NEW") {
                Log.i(
                    CookieRunBotApplication.TAG,
                    "[DAILY_NEW_SKIP] reason=action_in_progress"
                )
                DebugLogStore.log("[DAILY_NEW_SKIP] reason=action_in_progress")
            }
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
                    GestureArbiter.release(lease)
                    onGestureCompleted?.invoke(completedAtNanos, success)
                },
            ).also {
                if (!it.accepted) GestureArbiter.release(lease)
            }
        } catch (error: Exception) {
            GestureArbiter.release(lease)
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
            if (actionName == "DAILY_NEW") {
                Log.i(
                    CookieRunBotApplication.TAG,
                    "[DAILY_NEW_SKIP] reason=accessibility_permission_disabled"
                )
                DebugLogStore.log("[DAILY_NEW_SKIP] reason=accessibility_permission_disabled")
            }
            if (actionName == "PARTY_RUN_SEASON_REWARDS") {
                Log.i(
                    CookieRunBotApplication.TAG,
                    "[PARTY_RUN_SEASON_REWARDS_SKIP] reason=accessibility_permission_disabled"
                )
                DebugLogStore.log("[PARTY_RUN_SEASON_REWARDS_SKIP] reason=accessibility_permission_disabled")
            }
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
            if (actionName == "DAILY_NEW") {
                Log.i(
                    CookieRunBotApplication.TAG,
                    "[DAILY_NEW_SKIP] reason=accessibility_service_disconnected"
                )
                DebugLogStore.log("[DAILY_NEW_SKIP] reason=accessibility_service_disconnected")
            }
            if (actionName == "PARTY_RUN_SEASON_REWARDS") {
                Log.i(
                    CookieRunBotApplication.TAG,
                    "[PARTY_RUN_SEASON_REWARDS_SKIP] reason=accessibility_service_disconnected"
                )
                DebugLogStore.log("[PARTY_RUN_SEASON_REWARDS_SKIP] reason=accessibility_service_disconnected")
            }
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
    val pendingGesture: GestureCancellation? = null,
)

/** A pending callback/timeout pair that a controller may invalidate on stop. */
interface GestureCancellation {
    val isCompleted: Boolean
    fun cancel()
}

private class GestureCompletionContext(
    private val lease: GestureLease,
    private val timeoutMs: Long,
    private val callback: ((Long, Boolean, Boolean) -> Unit)?,
) : GestureCancellation {
    private val completed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val handler = if (timeoutMs > 0L) android.os.Handler(android.os.Looper.getMainLooper()) else null
    private var timeoutRunnable: Runnable? = null

    override val isCompleted: Boolean get() = completed.get()

    fun armTimeout(actionName: String) {
        if (timeoutMs <= 0L) return
        val runnable = Runnable {
            if (completed.compareAndSet(false, true)) {
                GestureArbiter.release(lease)
                Log.i(CookieRunBotApplication.TAG, "[ACTION] $actionName callback timeout; releasing own lease")
                callback?.invoke(SystemClock.elapsedRealtimeNanos(), false, true)
            }
        }
        timeoutRunnable = runnable
        handler?.postDelayed(runnable, timeoutMs)
    }

    fun complete(completedAtNanos: Long, success: Boolean) {
        if (!completed.compareAndSet(false, true)) return
        timeoutRunnable?.let { handler?.removeCallbacks(it) }
        GestureArbiter.release(lease)
        callback?.invoke(completedAtNanos, success, false)
    }

    override fun cancel() {
        if (!completed.compareAndSet(false, true)) return
        timeoutRunnable?.let { handler?.removeCallbacks(it) }
        GestureArbiter.release(lease)
    }
}

enum class GesturePriority { NORMAL, BACKGROUND, CRITICAL }

/** Gives state-transition taps precedence over the background jump worker. */
internal object GestureArbiter {
    private const val MAX_GESTURE_LEASE_MS = 500L
    private const val CRITICAL_WAIT_MS = 300L
    private val monitor = Object()
    private var activeLease: GestureLease? = null
    private var busySinceNanos = 0L
    private var criticalWaiting = false
    private var nextLeaseId = 1L

    fun acquire(priority: GesturePriority): GestureLease? = synchronized(monitor) {
        expireStaleLease()
        if (priority == GesturePriority.BACKGROUND) {
            if (activeLease != null || criticalWaiting) return null
            return markBusy()
        }

        if (priority == GesturePriority.CRITICAL) criticalWaiting = true
        try {
            val deadline = SystemClock.elapsedRealtimeNanos() + CRITICAL_WAIT_MS * 1_000_000L
            while (activeLease != null) {
                val remaining = deadline - SystemClock.elapsedRealtimeNanos()
                if (remaining <= 0L) return null
                monitor.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                expireStaleLease()
            }
            markBusy()
        } finally {
            if (priority == GesturePriority.CRITICAL) criticalWaiting = false
        }
    }

    /** Only the current owner may release a lease. Stale callbacks are harmless. */
    fun release(lease: GestureLease): Boolean = synchronized(monitor) {
        if (activeLease != lease) return false
        activeLease = null
        busySinceNanos = 0L
        monitor.notifyAll()
        true
    }

    fun isBusy(): Boolean = synchronized(monitor) {
        expireStaleLease()
        activeLease != null
    }

    private fun markBusy(): GestureLease {
        val lease = GestureLease(nextLeaseId++)
        activeLease = lease
        busySinceNanos = SystemClock.elapsedRealtimeNanos()
        return lease
    }

    private fun expireStaleLease() {
        if (activeLease != null && SystemClock.elapsedRealtimeNanos() - busySinceNanos > MAX_GESTURE_LEASE_MS * 1_000_000L) {
            activeLease = null
            busySinceNanos = 0L
            monitor.notifyAll()
        }
    }
}

internal class GestureLease internal constructor(val id: Long)
