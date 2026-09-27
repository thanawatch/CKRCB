package com.example.cookierunbot

import android.app.Application
import com.example.cookierunbot.diagnostics.RobotLog as Log
import com.example.cookierunbot.automation.BotRuntime
import com.example.cookierunbot.automation.RuntimeDiagnosticsLogger
import com.example.cookierunbot.diagnostics.OvernightLogArchive
import com.example.cookierunbot.diagnostics.IncidentManager
import com.example.cookierunbot.diagnostics.PersistentTrace
import com.example.cookierunbot.diagnostics.DebugLogStore
import com.example.cookierunbot.notifications.TelegramCommandReceiver
import org.opencv.android.OpenCVLoader

class CookieRunBotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { OvernightLogArchive.initialize(this) }
        runCatching { IncidentManager.initialize(this) }
        PersistentTrace.initialize(this)
        DebugLogStore.log("[APP] RobotRun process started")
        val loaded = OpenCVLoader.initLocal()
        Log.i(TAG, "[OPENCV] initialized=$loaded")
        BotRuntime.initialize(this)
        TelegramCommandReceiver.initialize(this)
        RuntimeDiagnosticsLogger.log(this, "process started")
    }

    companion object {
        const val TAG = "CookieRunBot"
    }
}
