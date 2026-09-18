package com.example.cookierunbot.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.diagnostics.PersistentTrace
import com.example.cookierunbot.diagnostics.DebugLogStore

class BotAccessibilityService : AccessibilityService() {
    @Volatile
    private var connected = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        instance = this
        PersistentTrace.log("[ACCESSIBILITY] connected instance=${System.identityHashCode(this)}")
        Log.i(
            CookieRunBotApplication.TAG,
            "[ACCESSIBILITY] Service connected instance=${System.identityHashCode(this)}",
        )
        Log.i(CookieRunBotApplication.TAG, "[ACCESSIBILITY] Enabled=${isEnabled(this)}")
        RuntimeDiagnosticsLogger.log(this, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        Log.i(CookieRunBotApplication.TAG, "[ACCESSIBILITY] Service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        if (instance === this) instance = null
        PersistentTrace.log("[ACCESSIBILITY] unbound instance=${System.identityHashCode(this)}")
        Log.w(
            CookieRunBotApplication.TAG,
            "[ACCESSIBILITY] Service unbound instance=${System.identityHashCode(this)}",
        )
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        if (instance === this) instance = null
        PersistentTrace.log("[ACCESSIBILITY] destroyed instance=${System.identityHashCode(this)}")
        Log.i(
            CookieRunBotApplication.TAG,
            "[ACCESSIBILITY] Service destroyed instance=${System.identityHashCode(this)}",
        )
        super.onDestroy()
    }

    private fun dispatchCenterTap(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(CookieRunBotApplication.TAG, "[ACCESSIBILITY] Test tap requires Android 7.0+")
            return false
        }

        val metrics = resources.displayMetrics
        return dispatchPhysicalTap(
            metrics.widthPixels / 2,
            metrics.heightPixels / 2,
            "[ACCESSIBILITY] Test tap",
        )
    }

    private fun dispatchPhysicalTap(
        x: Int,
        y: Int,
        logPrefix: String,
        onCompleted: ((completedAtNanos: Long, success: Boolean) -> Unit)? = null,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(CookieRunBotApplication.TAG, "$logPrefix requires Android 7.0+")
            return false
        }

        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onCompleted?.invoke(SystemClock.elapsedRealtimeNanos(), true)
                Log.i(CookieRunBotApplication.TAG, "$logPrefix completed=true")
                DebugLogStore.log(
                    "$logPrefix physical=($x,$y) dispatchCallbackSuccess=true",
                )
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onCompleted?.invoke(SystemClock.elapsedRealtimeNanos(), false)
                Log.w(CookieRunBotApplication.TAG, "$logPrefix completed=false")
                DebugLogStore.log(
                    "$logPrefix physical=($x,$y) dispatchCallbackSuccess=false",
                )
            }
        }, null)
        Log.i(CookieRunBotApplication.TAG, "$logPrefix dispatchGesture success=$accepted")
        PersistentTrace.log("$logPrefix dispatchGesture accepted=$accepted physical=($x,$y)")
        DebugLogStore.log("$logPrefix physical=($x,$y) dispatchAccepted=$accepted")
        return accepted
    }

    private fun dispatchPhysicalSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
        logPrefix: String,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(CookieRunBotApplication.TAG, "$logPrefix completed=true")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(CookieRunBotApplication.TAG, "$logPrefix completed=false")
            }
        }, null)
        Log.i(CookieRunBotApplication.TAG, "$logPrefix dispatchGesture success=$accepted")
        return accepted
    }

    companion object {
        private const val TAP_DURATION_MS = 80L
        @Volatile
        private var instance: BotAccessibilityService? = null

        fun isConnected(): Boolean = instance?.connected == true

        fun hasServiceInstance(): Boolean = instance != null

        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return instance != null
            val expected = ComponentName(context, BotAccessibilityService::class.java)
            return enabledServices.split(':').any { flattened ->
                ComponentName.unflattenFromString(flattened) == expected
            }
        }

        fun testTap(context: Context): Boolean {
            val service = instance
            if (service == null || !service.connected) {
                Log.w(
                    CookieRunBotApplication.TAG,
                    "[ACCESSIBILITY] Test tap fail: service is not connected",
                )
                return false
            }
            return service.dispatchCenterTap()
        }

        fun testTapAt(
            context: Context,
            logicalX: Int,
            logicalY: Int,
            sourceWidth: Int,
            sourceHeight: Int,
        ): Boolean {
            return dispatchLogicalTap(
                logicalX,
                logicalY,
                sourceWidth,
                sourceHeight,
                "[TAP TEST]",
            ).accepted
        }

        fun dispatchLogicalTap(
            logicalX: Int,
            logicalY: Int,
            sourceWidth: Int,
            sourceHeight: Int,
            logPrefix: String,
            calibratedCoordinates: Boolean = false,
            onCompleted: ((completedAtNanos: Long, success: Boolean) -> Unit)? = null,
        ): GestureDispatchResult {
            val requestedAtNanos = SystemClock.elapsedRealtimeNanos()
            val physical = try {
                if (calibratedCoordinates) {
                    CoordinateMapper.calibratedLogicalToPhysical(
                        logicalX, logicalY, sourceWidth, sourceHeight,
                    )
                } else {
                    CoordinateMapper.logicalToPhysical(
                        logicalX, logicalY, sourceWidth, sourceHeight,
                    )
                }
            } catch (error: IllegalArgumentException) {
                Log.w(
                    CookieRunBotApplication.TAG,
                    "$logPrefix coordinate mapping failed: ${error.message}",
                )
                return GestureDispatchResult(false, null, requestedAtNanos, SystemClock.elapsedRealtimeNanos())
            }
            val mapping = if (calibratedCoordinates) "CALIBRATED_PHONE" else "CONTENT_RECT"
            Log.i(CookieRunBotApplication.TAG, "$logPrefix logical=($logicalX,$logicalY) mapping=$mapping")
            Log.i(CookieRunBotApplication.TAG, "$logPrefix physical=(${physical.x},${physical.y})")
            Log.i(
                CookieRunBotApplication.TAG,
                "$logPrefix accessibilityConnected=${isConnected()} " +
                    "serviceInstance=${hasServiceInstance()}",
            )

            val service = instance
            if (service == null || !service.connected) {
                Log.w(
                    CookieRunBotApplication.TAG,
                    "$logPrefix [ACTION] SKIPPED reason=ACCESSIBILITY_SERVICE_DISCONNECTED",
                )
                return GestureDispatchResult(false, physical, requestedAtNanos, SystemClock.elapsedRealtimeNanos())
            }
            Log.i(CookieRunBotApplication.TAG, "$logPrefix dispatch start")
            val accepted = service.dispatchPhysicalTap(
                physical.x,
                physical.y,
                logPrefix,
                onCompleted,
            )
            return GestureDispatchResult(
                accepted,
                physical,
                requestedAtNanos,
                SystemClock.elapsedRealtimeNanos(),
            )
        }

        fun dispatchPhysicalPoint(
            x: Int,
            y: Int,
            logPrefix: String,
        ): GestureDispatchResult {
            val requestedAtNanos = SystemClock.elapsedRealtimeNanos()
            val service = instance
            if (service == null || !service.connected) {
                Log.w(
                    CookieRunBotApplication.TAG,
                    "$logPrefix [ACTION] SKIPPED reason=ACCESSIBILITY_SERVICE_DISCONNECTED",
                )
                return GestureDispatchResult(
                    false,
                    PhysicalPoint(x, y),
                    requestedAtNanos,
                    SystemClock.elapsedRealtimeNanos(),
                )
            }
            Log.i(CookieRunBotApplication.TAG, "$logPrefix dispatch start")
            return GestureDispatchResult(
                service.dispatchPhysicalTap(x, y, logPrefix),
                PhysicalPoint(x, y),
                requestedAtNanos,
                SystemClock.elapsedRealtimeNanos(),
            )
        }

        fun dispatchLogicalSwipe(
            start: LogicalPoint,
            end: LogicalPoint,
            sourceWidth: Int,
            sourceHeight: Int,
            durationMs: Long,
            logPrefix: String,
        ): Boolean {
            val service = instance
            if (service == null || !service.connected) {
                Log.w(
                    CookieRunBotApplication.TAG,
                    "$logPrefix [ACTION] SKIPPED reason=ACCESSIBILITY_SERVICE_DISCONNECTED",
                )
                return false
            }
            val physicalStart = CoordinateMapper.logicalToPhysical(
                start.x, start.y, sourceWidth, sourceHeight,
            )
            val physicalEnd = CoordinateMapper.logicalToPhysical(
                end.x, end.y, sourceWidth, sourceHeight,
            )
            Log.i(
                CookieRunBotApplication.TAG,
                "$logPrefix logical=(${start.x},${start.y})->(${end.x},${end.y}) " +
                    "physical=(${physicalStart.x},${physicalStart.y})->(${physicalEnd.x},${physicalEnd.y})",
            )
            return service.dispatchPhysicalSwipe(
                physicalStart.x,
                physicalStart.y,
                physicalEnd.x,
                physicalEnd.y,
                durationMs,
                logPrefix,
            )
        }
    }
}

data class GestureDispatchResult(
    val accepted: Boolean,
    val physical: PhysicalPoint?,
    val requestedAtNanos: Long,
    val dispatchedAtNanos: Long,
)
