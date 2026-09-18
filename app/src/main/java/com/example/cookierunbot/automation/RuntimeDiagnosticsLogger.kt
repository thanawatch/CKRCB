package com.example.cookierunbot.automation

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.capture.LatestFrameProvider
import com.example.cookierunbot.diagnostics.DebugLogStore
import com.example.cookierunbot.diagnostics.PersistentTrace
import java.util.Locale

/** One-shot lifecycle diagnostics used to compare warm and cold process starts. */
object RuntimeDiagnosticsLogger {
    fun log(context: Context, event: String) {
        val appContext = context.applicationContext
        val capture = LatestFrameProvider.diagnostics()
        val bot = BotRuntime.diagnostics()
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        val batteryManager = appContext.getSystemService(BatteryManager::class.java)
        val summary = "$event process=${Process.myPid()} " +
            "accessibilityEnabled=${BotAccessibilityService.isEnabled(appContext)} " +
            "accessibilityConnected=${BotAccessibilityService.isConnected()} " +
            "instance=${BotAccessibilityService.hasServiceInstance()} " +
            "captureRunning=${capture.captureRunning} frameAge=${capture.frameAgeMs ?: "none"}ms " +
            "botRunning=${bot.running} jobActive=${bot.jobActive} actionInProgress=${bot.actionInProgress} " +
            "powerSave=${powerManager?.isPowerSaveMode == true} charging=${batteryManager?.isCharging == true} " +
            "batteryExempt=${powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true}"
        PersistentTrace.log("[RUNTIME] $summary")

        Log.i(
            CookieRunBotApplication.TAG,
            "[COLDSTART] $event process=${Process.myPid()}",
        )
        Log.i(
            CookieRunBotApplication.TAG,
            "[ACCESSIBILITY] enabled=${BotAccessibilityService.isEnabled(appContext)} " +
                "connected=${BotAccessibilityService.isConnected()} " +
                "instance=${BotAccessibilityService.hasServiceInstance()}",
        )
        Log.i(
            CookieRunBotApplication.TAG,
            "[CAPTURE] running=${capture.captureRunning} " +
                "latestFrameAge=${capture.frameAgeMs ?: "none"}ms " +
                "frame=${capture.sourceWidth}x${capture.sourceHeight} " +
                "normalized=${capture.normalizedWidth}x${capture.normalizedHeight}",
        )
        Log.i(
            CookieRunBotApplication.TAG,
            "[BOT] jobActive=${bot.jobActive} actionInProgress=${bot.actionInProgress} " +
                "running=${bot.running}",
        )
        logHealth(appContext)
    }

    fun logPowerEvent(context: Context, action: String) {
        emit("[POWER_EVENT] ${action.substringAfterLast('.')}")
        logHealth(context)
    }

    fun logHealth(context: Context) {
        val appContext = context.applicationContext
        val capture = LatestFrameProvider.diagnostics()
        val bot = BotRuntime.diagnostics()
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        val batteryManager = appContext.getSystemService(BatteryManager::class.java)
        val batteryPercent = batteryManager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeUnless { it == Int.MIN_VALUE }
            ?.toString()
            ?: "unknown"
        val standbyBucket = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                appContext.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket?.toString()
                    ?: "unknown"
            } else {
                "unsupported"
            }
        }.getOrDefault("unavailable")

        emit(
            "[POWER] charging=${batteryManager?.isCharging == true} " +
                "powerSaveMode=${powerManager?.isPowerSaveMode == true} " +
                "isIgnoringBatteryOptimizations=" +
                "${powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true} " +
                "batteryPercent=$batteryPercent appStandbyBucket=$standbyBucket",
        )
        emit(
            "[CAPTURE_HEALTH] running=${capture.captureRunning} " +
                "frameSequence=${capture.frameSequence} " +
                "latestFrameAgeMs=${formatAge(capture.frameAgeMs)} " +
                "frameSize=${capture.sourceWidth}x${capture.sourceHeight}",
        )
        emit(
            "[DETECT_HEALTH] workerAlive=${bot.workerAlive} " +
                "lastProcessedFrameSequence=${bot.lastProcessedFrameSequence} " +
                "lastDetectionAttemptAgeMs=${bot.lastDetectionAttemptAgeMs ?: "none"} " +
                "group=${bot.group}",
        )
    }

    private fun emit(message: String) {
        Log.i(CookieRunBotApplication.TAG, message)
        DebugLogStore.log(message)
        PersistentTrace.log(message)
    }

    private fun formatAge(value: Double?): String =
        value?.let { String.format(Locale.US, "%.1f", it) } ?: "none"
}
