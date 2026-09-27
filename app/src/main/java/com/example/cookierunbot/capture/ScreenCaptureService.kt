package com.example.cookierunbot.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.example.cookierunbot.diagnostics.RobotLog as Log
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.MainActivity
import com.example.cookierunbot.R
import com.example.cookierunbot.automation.BotRuntime
import com.example.cookierunbot.automation.BotControlOverlay
import com.example.cookierunbot.automation.RuntimeDiagnosticsLogger
import com.example.cookierunbot.detection.StateDetector
import com.example.cookierunbot.diagnostics.PersistentTrace
import com.example.cookierunbot.diagnostics.DebugLogStore
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ScreenCaptureService : Service() {
    private var captureManager: ScreenCaptureManager? = null
    private var detector: StateDetector? = null
    private var detectionExecutor: ScheduledExecutorService? = null
    private var botControlOverlay: BotControlOverlay? = null
    private var stopping = false
    private var powerReceiverRegistered = false
    private var lastHealthLogAt = 0L
    private val powerEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED ->
                    runCatching {
                        RuntimeDiagnosticsLogger.logPowerEvent(
                            applicationContext,
                            intent.action.orEmpty(),
                        )
                    }.onFailure { error ->
                        Log.w(
                            CookieRunBotApplication.TAG,
                            "[POWER_EVENT] Diagnostics failed: ${error.message}",
                        )
                    }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerPowerEventReceiver()
        Log.i(CookieRunBotApplication.TAG, "[CAPTURE] Foreground service created")
        PersistentTrace.log("[CAPTURE] foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdownResources()
        unregisterPowerEventReceiver()
        super.onDestroy()
    }

    private fun startCapture(intent: Intent) {
        if (captureManager != null) return
        stopping = false
        Log.i(CookieRunBotApplication.TAG, "[CAPTURE] Start requested; creating new MediaProjection")
        DebugLogStore.log("[CAPTURE] Start requested")
        PersistentTrace.log("[CAPTURE] start requested")
        startAsForeground()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, RESULT_CODE_MISSING)
        val permissionData = projectionPermissionData(intent)
        if (resultCode == RESULT_CODE_MISSING || permissionData == null) {
            LatestFrameProvider.reportError("Missing MediaProjection permission data")
            stopCapture()
            return
        }

        try {
            detector = StateDetector(applicationContext)
            val newCaptureManager = ScreenCaptureManager(applicationContext) {
                stopCapture()
            }
            captureManager = newCaptureManager
            newCaptureManager.start(resultCode, permissionData)
            runCatching { IncidentVideoRecorder.start(applicationContext) }
                .onFailure { error ->
                    Log.w(CookieRunBotApplication.TAG,
                        "[INCIDENT_VIDEO] unavailable type=${error.javaClass.simpleName}")
                }
            RuntimeDiagnosticsLogger.log(applicationContext, "MediaProjection created")
            botControlOverlay = BotControlOverlay(applicationContext).also { overlay ->
                if (!overlay.show()) {
                    Log.w(CookieRunBotApplication.TAG, "[OVERLAY] Permission missing; bubble not shown")
                }
            }
            startDetectionLoop()
        } catch (error: Exception) {
            Log.e(CookieRunBotApplication.TAG, "[CAPTURE] Unable to start MediaProjection", error)
            LatestFrameProvider.reportError(error.message ?: "Unable to start MediaProjection")
            stopCapture()
        }
    }

    private fun startDetectionLoop() {
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mainmenu-detector").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
        detectionExecutor = executor
        executor.scheduleWithFixedDelay({
            val now = SystemClock.elapsedRealtime()
            if (now - lastHealthLogAt >= HEALTH_LOG_INTERVAL_MS) {
                runCatching { RuntimeDiagnosticsLogger.logHealth(applicationContext) }
                    .onFailure { error ->
                        Log.w(
                            CookieRunBotApplication.TAG,
                            "[HEALTH] Diagnostics failed: ${error.message}",
                        )
                    }
                lastHealthLogAt = now
            }
            // The bot owns detection while running. Avoid a second OpenCV pass and UI/log work
            // competing with transient GAME_START/GAME_RELAY checks.
            if (BotRuntime.isRunning()) return@scheduleWithFixedDelay
            val frame = LatestFrameProvider.snapshot() ?: return@scheduleWithFixedDelay
            try {
                val result = detector?.detectMainMenu(frame.mat) ?: return@scheduleWithFixedDelay
                LatestFrameProvider.updateDetection(result)
                Log.i(
                    CookieRunBotApplication.TAG,
                    "[DETECT] MAINMENU score=${String.format(Locale.US, "%.4f", result.score)}",
                )
                if (result.matched) {
                    Log.i(
                        CookieRunBotApplication.TAG,
                        "[DETECT] MAINMENU matched at x=${result.x}, y=${result.y} " +
                            "template=${result.width}x${result.height}",
                    )
                }
            } catch (error: Exception) {
                Log.e(CookieRunBotApplication.TAG, "[DETECT] MAINMENU detection failed", error)
                LatestFrameProvider.reportError(error.message ?: "Detection failed")
            } finally {
                frame.mat.release()
            }
        }, 0L, DETECTION_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopCapture() {
        if (stopping) return
        stopping = true
        DebugLogStore.log("[CAPTURE] Stop requested")
        shutdownResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdownResources() {
        runCatching { IncidentVideoRecorder.stop() }
        botControlOverlay?.dismiss()
        botControlOverlay = null
        BotRuntime.stop()
        detectionExecutor?.shutdownNow()
        detectionExecutor = null
        detector?.close()
        detector = null
        val manager = captureManager
        captureManager = null
        manager?.stop()
        LatestFrameProvider.setCaptureRunning(false)
    }

    private fun registerPowerEventReceiver() {
        if (powerReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(powerEventReceiver, filter)
        }
        powerReceiverRegistered = true
    }

    private fun unregisterPowerEventReceiver() {
        if (!powerReceiverRegistered) return
        runCatching { unregisterReceiver(powerEventReceiver) }
        powerReceiverRegistered = false
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText("Capturing latest frame for MAINMENU detection")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.capture_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun projectionPermissionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PERMISSION_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_PERMISSION_DATA)
        }

    companion object {
        private const val ACTION_START = "com.example.cookierunbot.action.START_CAPTURE"
        private const val ACTION_STOP = "com.example.cookierunbot.action.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_PERMISSION_DATA = "permission_data"
        private const val RESULT_CODE_MISSING = Int.MIN_VALUE
        private const val NOTIFICATION_CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001
        private const val DETECTION_INTERVAL_MS = 750L
        private const val HEALTH_LOG_INTERVAL_MS = 5_000L

        fun start(context: Context, resultCode: Int, permissionData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_PERMISSION_DATA, permissionData)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
