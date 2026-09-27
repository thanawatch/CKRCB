package com.example.cookierunbot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.cookierunbot.automation.ActionResult
import com.example.cookierunbot.automation.BotController
import com.example.cookierunbot.automation.BotAccessibilityService
import com.example.cookierunbot.automation.BotStage
import com.example.cookierunbot.automation.BotRuntime
import com.example.cookierunbot.automation.CalibrationBlockState
import com.example.cookierunbot.capture.LatestFrameProvider
import com.example.cookierunbot.capture.ScreenCaptureService
import com.example.cookierunbot.detection.DetectionResult
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var captureStatus: TextView
    private lateinit var frameSize: TextView
    private lateinit var normalizedSize: TextView
    private lateinit var orientation: TextView
    private lateinit var frameAge: TextView
    private lateinit var mainMenuScore: TextView
    private lateinit var mainMenuDetected: TextView
    private lateinit var matchPosition: TextView
    private lateinit var accessibilityPermissionStatus: TextView
    private lateinit var accessibilityServiceStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var batteryOptimizationStatus: TextView
    private lateinit var botStatus: TextView
    private lateinit var botState: TextView
    private lateinit var botLastDetection: TextView
    private lateinit var botLastAction: TextView
    private lateinit var botDetectionGroup: TextView
    private lateinit var botLastTemplate: TextView
    private lateinit var botLastScore: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var botRunningValue = false
    private var botStateValue = "STOPPED"
    private var botLastDetectionValue = "—"
    private var botLastActionValue = "—"
    private var botDetectionGroupValue = "—"
    private var botLastTemplateValue = "—"
    private var botLastScoreValue = "—"
    private val runtimeListener = object : BotController.Listener {
        override fun onRunningChanged(running: Boolean) {
            botRunningValue = running
            postUiUpdate()
        }

        override fun onStateChanged(state: String) {
            botStateValue = state
            postUiUpdate()
        }

        override fun onGroupChanged(group: String) {
            botDetectionGroupValue = group
            postUiUpdate()
        }

        override fun onDetection(stage: BotStage, result: DetectionResult) {
            botLastDetectionValue = String.format(
                Locale.US,
                "%s score=%.4f matched=%s",
                stage.name,
                result.score,
                result.matched,
            )
            botLastTemplateValue = stage.name
            botLastScoreValue = String.format(Locale.US, "%.4f", result.score)
            postUiUpdate()
        }

        override fun onAction(result: ActionResult) {
            botLastActionValue = if (result.physical != null) {
                "${result.actionName} logical=(${result.logicalX},${result.logicalY}) " +
                    "physical=(${result.physical.x},${result.physical.y}) " +
                    "success=${result.accepted}"
            } else {
                "${result.actionName} logical=(${result.logicalX},${result.logicalY}) success=false"
            }
            postUiUpdate()
        }
    }
    private val refreshUi = object : Runnable {
        override fun run() {
            renderDiagnostics()
            uiHandler.postDelayed(this, UI_REFRESH_MS)
        }
    }

    private val capturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ScreenCaptureService.start(this, result.resultCode, data)
            } else {
                LatestFrameProvider.reportError("Screen capture permission was denied")
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestScreenCapturePermission()
        }

    private val legacyStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val batteryOptimizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            renderDiagnostics()
            if (isBatteryOptimizationExempt()) {
                Toast.makeText(this, "Background operation allowed", Toast.LENGTH_SHORT).show()
                startCapture()
            } else {
                Toast.makeText(
                    this,
                    "RobotRun needs Unrestricted battery access to run after reboot while unplugged",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        BotRuntime.initialize(applicationContext)
        BotRuntime.attach(runtimeListener)
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    override fun onStart() {
        super.onStart()
        uiHandler.post(refreshUi)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(refreshUi)
        super.onStop()
    }

    override fun onDestroy() {
        BotRuntime.detach(runtimeListener)
        super.onDestroy()
    }

    private fun startCapture() {
        if (!isBatteryOptimizationExempt()) {
            requestBatteryOptimizationExemption()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Allow display over other apps, then tap Start Screen Capture again",
                Toast.LENGTH_LONG,
            ).show()
            openOverlaySettings()
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestScreenCapturePermission()
        }
    }

    private fun requestScreenCapturePermission() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capturePermissionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun isBatteryOptimizationExempt(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimizationExemption() {
        val packageUri = Uri.parse("package:$packageName")
        val directRequest = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        try {
            batteryOptimizationLauncher.launch(directRequest)
        } catch (_: Exception) {
            batteryOptimizationLauncher.launch(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }
    }

    private fun buildContentView(): ScrollView {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
        }

        content.addView(TextView(this).apply {
            text = "RobotRun — Native Android"
            textSize = 22f
            gravity = Gravity.CENTER
        }, matchWidth())

        content.addView(Button(this).apply {
            text = "Start Screen Capture"
            setOnClickListener { startCapture() }
        }, matchWidth())

        content.addView(Button(this).apply {
            text = "Stop Screen Capture"
            setOnClickListener { ScreenCaptureService.stop(this@MainActivity) }
        }, matchWidth())

        content.addView(TextView(this).apply {
            text = "Floating bot controls"
            textSize = 19f
            setPadding(0, padding, 0, 0)
        }, matchWidth())

        overlayStatus = diagnosticLine(content)
        content.addView(Button(this).apply {
            text = "Enable Floating Bot Controls"
            setOnClickListener { openOverlaySettings() }
        }, matchWidth())

        content.addView(TextView(this).apply {
            text = "Background operation"
            textSize = 19f
            setPadding(0, padding, 0, 0)
        }, matchWidth())

        batteryOptimizationStatus = diagnosticLine(content)
        content.addView(Button(this).apply {
            text = "Allow Background Operation"
            setOnClickListener { requestBatteryOptimizationExemption() }
        }, matchWidth())

        content.addView(TextView(this).apply {
            text = "Accessibility"
            textSize = 19f
            setPadding(0, padding, 0, 0)
        }, matchWidth())

        accessibilityPermissionStatus = diagnosticLine(content)
        accessibilityServiceStatus = diagnosticLine(content)

        content.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, matchWidth())

        content.addView(Button(this).apply {
            text = "Test Tap"
            setOnClickListener { BotAccessibilityService.testTap(this@MainActivity) }
        }, matchWidth())

        content.addView(Button(this).apply {
            text = "Open Debug Log"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, DebugLogActivity::class.java))
            }
        }, matchWidth())

        content.addView(Button(this).apply {
            text = "Statistics / Round History"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, StatisticsActivity::class.java))
            }
        }, matchWidth())

        content.addView(Button(this).apply {
            text = "Telegram Notifications"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, TelegramSettingsActivity::class.java))
            }
        }, matchWidth())

        botStatus = diagnosticLine(content)
        botState = diagnosticLine(content)
        botDetectionGroup = diagnosticLine(content)
        botLastTemplate = diagnosticLine(content)
        botLastScore = diagnosticLine(content)
        botLastDetection = diagnosticLine(content)
        botLastAction = diagnosticLine(content)

        content.addView(TextView(this).apply {
            text = "Diagnostics"
            textSize = 19f
            setPadding(0, padding, 0, 0)
        }, matchWidth())

        captureStatus = diagnosticLine(content)
        frameSize = diagnosticLine(content)
        normalizedSize = diagnosticLine(content)
        orientation = diagnosticLine(content)
        frameAge = diagnosticLine(content)
        mainMenuScore = diagnosticLine(content)
        mainMenuDetected = diagnosticLine(content)
        matchPosition = diagnosticLine(content)

        content.addView(TextView(this).apply {
            text = "Start Screen Capture, open the game, then tap the BOT bubble for options and Start/Stop."
            textSize = 14f
            setPadding(0, padding, 0, 0)
        }, matchWidth())

        return ScrollView(this).apply { addView(content) }
    }

    private fun diagnosticLine(parent: LinearLayout): TextView =
        TextView(this).also { view ->
            view.textSize = 17f
            view.setPadding(0, 8, 0, 8)
            parent.addView(view, matchWidth())
        }

    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun renderDiagnostics() {
        val snapshot = LatestFrameProvider.diagnostics()
        val botDiagnostics = BotRuntime.diagnostics()
        accessibilityPermissionStatus.text = "Accessibility permission: " +
            if (BotAccessibilityService.isEnabled(this)) "Enabled" else "Disabled"
        accessibilityServiceStatus.text = "Accessibility service: " +
            if (BotAccessibilityService.isConnected()) "Connected" else "Disconnected"
        overlayStatus.text = "Floating controls: " +
            if (Settings.canDrawOverlays(this)) "Enabled" else "Permission required"
        batteryOptimizationStatus.text = "Battery access: " +
            if (isBatteryOptimizationExempt()) "Unrestricted" else "Restricted"
        captureStatus.text = "Capture: ${if (snapshot.captureRunning) "Running" else "Stopped"}"
        frameSize.text = if (snapshot.sourceWidth > 0) {
            "Frame size: ${snapshot.sourceWidth}x${snapshot.sourceHeight}"
        } else {
            "Frame size: —"
        }
        normalizedSize.text = if (snapshot.normalizedWidth > 0) {
            "Normalized frame: ${snapshot.normalizedWidth}x${snapshot.normalizedHeight}"
        } else {
            "Normalized frame: —"
        }
        orientation.text = if (snapshot.sourceWidth > 0) {
            "Orientation: ${if (snapshot.sourceWidth >= snapshot.sourceHeight) "Landscape" else "Portrait"}"
        } else {
            "Orientation: —"
        }
        frameAge.text = snapshot.frameAgeMs?.let {
            "Latest frame age: ${format(it)} ms"
        } ?: "Latest frame age: —"
        mainMenuScore.text = snapshot.detection?.let {
            "${it.state.name} score: ${String.format(Locale.US, "%.4f", it.score)}"
        } ?: "MAINMENU score: —"
        mainMenuDetected.text = snapshot.detection?.let {
            "${it.state.name} detected: ${it.matched}"
        } ?: "MAINMENU detected: false"
        matchPosition.text = snapshot.detection?.let {
            "Match position: ${it.x},${it.y}  template=${it.width}x${it.height}"
        } ?: "Match position: —"
        botStatus.text = "Bot: ${if (botRunningValue) "Running" else "Stopped"}"
        botState.text = "Current State: " + if (botDiagnostics.calibrationBlockedAction != null) {
            "${CalibrationBlockState.WAITING_FOR_REDFINGER_CALIBRATION} " +
                "(${botDiagnostics.calibrationBlockedAction}; clear popup manually)"
        } else {
            botStateValue
        }
        botDetectionGroup.text = "Detection Group: $botDetectionGroupValue"
        botLastTemplate.text = "Last Detected Template: $botLastTemplateValue"
        botLastScore.text = "Last Score: $botLastScoreValue"
        botLastDetection.text = "Last Detection: $botLastDetectionValue"
        botLastAction.text = "Last Action: $botLastActionValue"

        if (snapshot.error != null && !snapshot.captureRunning) {
            captureStatus.text = "Capture: Stopped (${snapshot.error})"
        }
    }

    private fun format(value: Double) = String.format(Locale.US, "%.1f", value)

    private fun postUiUpdate() {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) renderDiagnostics()
        }
    }

    companion object {
        private const val UI_REFRESH_MS = 500L
    }
}
