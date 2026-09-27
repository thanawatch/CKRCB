package com.example.cookierunbot.automation

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.capture.LatestFrameProvider
import com.example.cookierunbot.statistics.RoundResultStore
import android.util.Log
import android.util.DisplayMetrics
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Draggable control bubble shown above the game while screen capture is active. */
class BotControlOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val root = FrameLayout(context)
    private val bubble = Button(context)
    private val panel = LinearLayout(context)
    private val panelScroll = ScrollView(context)
    private val panelStatus = TextView(context)
    private val statsRounds = TextView(context)
    private val statsCoins = TextView(context)
    private val statsXp = TextView(context)
    private val statsTime = TextView(context)
    private val statsBoxCounts = linkedMapOf<String, TextView>()
    private val fastStart = Switch(context)
    private val redfingerProfile = Switch(context)
    private val cookieRelay = Switch(context)
    private val postRelay = Switch(context)
    private val desiredBoost = Switch(context)
    private val boostGroup = RadioGroup(context)
    private val detectRelic = Switch(context)
    private val sendFriendLife = Switch(context)
    private val friendLifeMinutes = EditText(context)
    private val quickLives = Switch(context)
    private val saveDebugImages = Switch(context)
    private val saveResultImages = Switch(context)
    private val jumpFixed = Switch(context)
    private val jumpContinuous = Switch(context)
    private val fixedCount = EditText(context)
    private val interval = EditText(context)
    private var calibrationTool: CoordinateCalibrationOverlay? = null
    private var attached = false
    private var expanded = false
    private var selectedBoost = BoostChoice.MAGNETIC
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragged = false

    private val layoutParams = WindowManager.LayoutParams(
        collapsedSize(),
        collapsedSize(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        COLLAPSED_FLAGS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(8)
        y = dp(80)
    }

    private val statusUpdater = object : Runnable {
        override fun run() {
            updateStatus()
            if (attached) handler.postDelayed(this, STATUS_UPDATE_MS)
        }
    }

    fun show(): Boolean {
        if (attached) return true
        if (!Settings.canDrawOverlays(context)) return false
        buildViews()
        return try {
            windowManager.addView(root, layoutParams)
            attached = true
            handler.post(statusUpdater)
            Log.i(CookieRunBotApplication.TAG, "[OVERLAY] Bot control bubble shown")
            true
        } catch (error: Exception) {
            Log.e(CookieRunBotApplication.TAG, "[OVERLAY] Unable to show bubble", error)
            false
        }
    }

    fun dismiss() {
        calibrationTool?.close()
        calibrationTool = null
        if (!attached) return
        attached = false
        handler.removeCallbacks(statusUpdater)
        hideKeyboard()
        runCatching { windowManager.removeView(root) }
        Log.i(CookieRunBotApplication.TAG, "[OVERLAY] Bot control bubble removed")
    }

    private fun buildViews() {
        root.removeAllViews()
        panel.removeAllViews()
        bubble.apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(20, 110, 190), dp(28).toFloat())
            setPadding(0, 0, 0, 0)
            setOnTouchListener(dragListener { setExpanded(true) })
        }
        root.addView(bubble, FrameLayout.LayoutParams(collapsedSize(), collapsedSize()))

        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(12), dp(8), dp(12), dp(10))
        panel.background = roundedBackground(Color.argb(245, 30, 30, 34), dp(14).toFloat())

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "RobotRun"
                textSize = 17f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = "×"
                setOnClickListener { setExpanded(false) }
            }, LinearLayout.LayoutParams(dp(52), dp(44)))
            setOnTouchListener(dragListener())
        }
        panel.addView(header, matchWidth())
        panelStatus.setTextColor(Color.WHITE)
        panelStatus.textSize = 14f
        panel.addView(panelStatus, matchWidth())
        panel.addView(buildStatisticsSummary(), matchWidth())

        val options = BotOptionsStore.load(context)
        addSwitch(
            redfingerProfile,
            "Experimental Redfinger Performance Profile",
            options.performanceProfile == PerformanceProfile.REDFINGER_OPTIMIZED,
        )
        addSwitch(fastStart, "Use owned Fast Start", options.useFastStart)
        addSwitch(cookieRelay, "Use owned Cookie Relay", options.useCookieRelay)
        addSwitch(postRelay, "Run action sequence after Cookie Relay", options.postCookieRelaySequence)
        addSwitch(desiredBoost, "Use Desired Random Boost", options.useDesiredRandomBoost)
        selectedBoost = options.desiredBoost
        panel.addView(TextView(context).apply {
            text = "Desired Boost"
            setTextColor(Color.WHITE)
        }, matchWidth())
        boostGroup.orientation = RadioGroup.VERTICAL
        boostGroup.removeAllViews()
        BoostChoice.entries.forEach { choice ->
            boostGroup.addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = choice.displayName
                textSize = 14f
                setTextColor(Color.WHITE)
                isChecked = choice == selectedBoost
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedBoost = choice
                }
            }, matchWidth())
        }
        panel.addView(boostGroup, matchWidth())
        addSwitch(detectRelic, "Detect Relic", options.detectRelic)
        addSwitch(sendFriendLife, "Send Friend Life on a schedule", options.sendFriendLife)
        panel.addView(TextView(context).apply {
            text = "Friend Life interval (minutes, 1-1440)"
            setTextColor(Color.WHITE)
        }, matchWidth())
        friendLifeMinutes.apply {
            hint = "Minutes (default 61)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(options.friendLifeIntervalMinutes.toString())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
        panel.addView(friendLifeMinutes, matchWidth())
        panel.addView(Button(context).apply {
            text = "Run Friend Life Now (Test)"
            setOnClickListener {
                val message = when (BotRuntime.requestFriendLifeTest()) {
                    FriendLifeTestRequest.BOT_STOPPED -> "Start Bot first"
                    FriendLifeTestRequest.QUEUED -> "Friend Life test queued; waiting for MAINMENU"
                    FriendLifeTestRequest.ALREADY_QUEUED -> "Friend Life test already queued"
                    FriendLifeTestRequest.IN_PROGRESS -> "Friend Life workflow already running"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                panelStatus.text = message
            }
        }, matchWidth())
        panel.addView(Button(context).apply {
            text = "Test Watchdog Recovery Now"
            setOnClickListener {
                // Collapse the overlay first so the manual recovery scans the game,
                // not the expanded control panel. The real 15-minute watchdog timer
                // and delivery state are intentionally untouched.
                setExpanded(false)
                handler.postDelayed({
                    val message = when (BotRuntime.requestWatchdogRecoveryTest()) {
                        WatchdogRecoveryTestRequest.STARTED ->
                            "Watchdog recovery test started; 15-minute timer unchanged"
                        WatchdogRecoveryTestRequest.BOT_STOPPED ->
                            "Start Bot first"
                        WatchdogRecoveryTestRequest.CAPTURE_STOPPED ->
                            "Start Screen Capture first"
                        WatchdogRecoveryTestRequest.ALREADY_RUNNING ->
                            "Watchdog recovery test already running"
                        WatchdogRecoveryTestRequest.WATCHDOG_OVERDUE ->
                            "Real watchdog is already overdue; test not started"
                        WatchdogRecoveryTestRequest.UNAVAILABLE ->
                            "Watchdog recovery test unavailable"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }, WATCHDOG_TEST_OVERLAY_SETTLE_MS)
            }
        }, matchWidth())
        panel.addView(Button(context).apply {
            text = "Capture Calibration (Bot stopped)"
            setOnClickListener {
                if (calibrationTool != null) return@setOnClickListener
                val tool = CoordinateCalibrationOverlay(context, root) { calibrationTool = null }
                calibrationTool = tool
                if (!tool.open()) tool.close()
            }
        }, matchWidth())
        addSwitch(quickLives, "Receive/Send Lives after each game", options.receiveSendLivesAfterEachGame)
        addSwitch(saveDebugImages, "Save NO_MATCH Debug Images", options.saveDebugImages)
        addSwitch(saveResultImages, "Save GAME_COMPLETE / MYSTERY_BOX Images", options.saveResultImages)
        val saveImageOptions = android.widget.CompoundButton.OnCheckedChangeListener { _, _ ->
            BotOptionsStore.saveImageOptions(context, saveDebugImages.isChecked, saveResultImages.isChecked)
        }
        saveDebugImages.setOnCheckedChangeListener(saveImageOptions)
        saveResultImages.setOnCheckedChangeListener(saveImageOptions)

        panel.addView(TextView(context).apply {
            text = "Jump mode (both off = no jump)"
            setTextColor(Color.WHITE)
        }, matchWidth())
        addSwitch(jumpFixed, "Jump by Count", options.jumpMode == JumpMode.FIXED_COUNT)
        addSwitch(jumpContinuous, "Auto Jump", options.jumpMode == JumpMode.CONTINUOUS)
        fixedCount.apply {
            hint = "Number of taps"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((options.fixedJumpCount.takeIf { it > 0 } ?: 10).toString())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
        interval.apply {
            hint = "Tap interval 100-1000 ms"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(options.tapIntervalMs.toString())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
        panel.addView(fixedCount, matchWidth())
        panel.addView(interval, matchWidth())

        cookieRelay.setOnCheckedChangeListener { _, checked ->
            postRelay.isEnabled = checked
            if (!checked) postRelay.isChecked = false
        }
        desiredBoost.setOnCheckedChangeListener { _, _ -> updateInputStates() }
        sendFriendLife.setOnCheckedChangeListener { _, _ -> updateInputStates() }
        jumpFixed.setOnCheckedChangeListener { _, checked ->
            if (checked) jumpContinuous.isChecked = false
            updateInputStates()
        }
        jumpContinuous.setOnCheckedChangeListener { _, checked ->
            if (checked) jumpFixed.isChecked = false
            updateInputStates()
        }
        updateInputStates()

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Start Bot"
                setOnClickListener { startBot() }
            }, LinearLayout.LayoutParams(0, dp(50), 1f))
            addView(Button(context).apply {
                text = "Stop Bot"
                setOnClickListener {
                    BotRuntime.stop()
                    updateStatus()
                    setExpanded(false)
                }
            }, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        panel.addView(actions, matchWidth())

        panelScroll.removeAllViews()
        panelScroll.isFillViewport = false
        panelScroll.isVerticalScrollBarEnabled = true
        panelScroll.clipToPadding = false
        panelScroll.setPadding(0, 0, 0, dp(24))
        // Explicit WRAP_CONTENT is important here. Without it, ScrollView may
        // measure the panel at viewport height and the bottom action buttons
        // never become part of the scroll range.
        panelScroll.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        panelScroll.visibility = View.GONE
        root.addView(
            panelScroll,
            FrameLayout.LayoutParams(expandedWidth(), expandedHeight()),
        )
        updateStatus()
    }

    private fun startBot() {
        try {
            val options = readOptions()
            BotOptionsStore.save(context, options)
            if (!LatestFrameProvider.diagnostics().captureRunning) {
                panelStatus.text = "Capture is not running"
                Toast.makeText(context, panelStatus.text, Toast.LENGTH_LONG).show()
                return
            }
            BotRuntime.initialize(context.applicationContext)
            BotRuntime.start(options)
            updateStatus()
            if (BotRuntime.isRunning()) {
                setExpanded(false)
            } else {
                panelStatus.text = "Bot could not start; check permissions"
                Toast.makeText(context, panelStatus.text, Toast.LENGTH_LONG).show()
            }
        } catch (error: IllegalArgumentException) {
            panelStatus.text = error.message ?: "Invalid options"
            Toast.makeText(context, panelStatus.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun readOptions(): BotOptions {
        val mode = when {
            jumpFixed.isChecked -> JumpMode.FIXED_COUNT
            jumpContinuous.isChecked -> JumpMode.CONTINUOUS
            else -> JumpMode.NONE
        }
        val count = fixedCount.text.toString().toIntOrNull() ?: 0
        val tapInterval = interval.text.toString().toIntOrNull() ?: 250
        val friendIntervalMinutes = friendLifeMinutes.text.toString().trim().toIntOrNull()
            ?: throw IllegalArgumentException("Friend Life interval must be 1-1440 minutes")
        return BotOptions(
            performanceProfile = if (redfingerProfile.isChecked) {
                PerformanceProfile.REDFINGER_OPTIMIZED
            } else {
                PerformanceProfile.LEGACY
            },
            useFastStart = fastStart.isChecked,
            useCookieRelay = cookieRelay.isChecked,
            postCookieRelaySequence = cookieRelay.isChecked && postRelay.isChecked,
            useDesiredRandomBoost = desiredBoost.isChecked,
            desiredBoost = selectedBoost,
            detectRelic = detectRelic.isChecked,
            sendFriendLife = sendFriendLife.isChecked,
            friendLifeIntervalMinutes = friendIntervalMinutes,
            receiveSendLivesAfterEachGame = quickLives.isChecked,
            saveDebugImages = saveDebugImages.isChecked,
            saveResultImages = saveResultImages.isChecked,
            jumpMode = mode,
            fixedJumpCount = if (mode == JumpMode.FIXED_COUNT) count else 0,
            tapIntervalMs = if (mode == JumpMode.NONE) 250 else tapInterval,
        )
    }

    private fun addSwitch(view: Switch, label: String, checked: Boolean) {
        view.text = label
        view.textSize = 14f
        view.setTextColor(Color.WHITE)
        view.isChecked = checked
        panel.addView(view, matchWidth())
    }

    private fun updateInputStates() {
        friendLifeMinutes.isEnabled = sendFriendLife.isChecked
        postRelay.isEnabled = cookieRelay.isChecked
        boostGroup.visibility = if (desiredBoost.isChecked) View.VISIBLE else View.GONE
        fixedCount.isEnabled = jumpFixed.isChecked
        interval.isEnabled = jumpFixed.isChecked || jumpContinuous.isChecked
    }

    private fun updateStatus() {
        val running = BotRuntime.isRunning()
        val botDiagnostics = BotRuntime.diagnostics()
        val capture = LatestFrameProvider.diagnostics()
        val accessibilityEnabled = BotAccessibilityService.isEnabled(context)
        val accessibilityConnected = BotAccessibilityService.isConnected()
        val frameReady = capture.captureRunning &&
            capture.frameAgeMs != null &&
            capture.frameAgeMs <= AutomationConfig.MAX_FRAME_AGE_MS
        val ready = accessibilityEnabled && accessibilityConnected && frameReady
        val blockedAction = botDiagnostics.calibrationBlockedAction
        bubble.text = when {
            !running -> "BOT\nOFF"
            blockedAction != null -> "BOT\nWAIT"
            ready -> "BOT\nON"
            else -> "BOT\nWAIT"
        }
        bubble.background = roundedBackground(
            when {
                !running -> Color.rgb(20, 110, 190)
                blockedAction != null -> Color.rgb(210, 125, 20)
                ready -> Color.rgb(20, 150, 70)
                else -> Color.rgb(210, 125, 20)
            },
            dp(28).toFloat(),
        )
        panelStatus.text = when {
            !running -> "Status: Stopped"
            blockedAction != null -> redfingerCalibrationStatusText(blockedAction)
            !capture.captureRunning -> "Waiting: Screen capture"
            !frameReady -> "Waiting: Fresh screen frame"
            !accessibilityEnabled -> "Waiting: Accessibility permission disabled"
            !accessibilityConnected -> "Waiting: Accessibility service disconnected"
            else -> "Status: Running"
        }
        updateStatisticsSummary()
    }

    private fun buildStatisticsSummary() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(7))
        background = roundedBackground(Color.argb(180, 52, 55, 64), dp(8).toFloat())
        addView(TextView(context).apply {
            text = "Session"
            textSize = 14f
            setTextColor(Color.WHITE)
        }, matchWidth())
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(statsRounds.apply { compactStatStyle() }, LinearLayout.LayoutParams(0, dp(22), 1f))
            addView(statsCoins.apply { compactStatStyle() }, LinearLayout.LayoutParams(0, dp(22), 1f))
        }, matchWidth())
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(statsXp.apply { compactStatStyle() }, LinearLayout.LayoutParams(0, dp(22), 1f))
            addView(statsTime.apply { compactStatStyle() }, LinearLayout.LayoutParams(0, dp(22), 1f))
        }, matchWidth())
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("wood", "silver", "gold", "rainbow").forEach { grade ->
                val count = TextView(context).apply { compactStatStyle() }
                statsBoxCounts[grade] = count
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    val bitmap = context.assets.open("templates/BOX_${grade.uppercase()}.png")
                        .use(android.graphics.BitmapFactory::decodeStream)
                    addView(ImageView(context).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        contentDescription = grade
                    }, LinearLayout.LayoutParams(dp(24), dp(24)))
                    addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)))
                }, LinearLayout.LayoutParams(0, dp(26), 1f))
            }
        }, matchWidth())
    }

    private fun TextView.compactStatStyle() {
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun updateStatisticsSummary() {
        val stats = RoundResultStore.snapshot()
        val formatter = NumberFormat.getIntegerInstance(Locale.US)
        statsRounds.text = "Rounds: ${stats.rounds}"
        statsCoins.text = "Coins: ${formatter.format(stats.totalCoins)}"
        statsXp.text = "XP: ${formatter.format(stats.totalXp)}"
        val totalSeconds = stats.sessionRuntimeMs / 1000
        statsTime.text = "Time: %02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
        statsBoxCounts["wood"]?.text = stats.woodBoxes.toString()
        statsBoxCounts["silver"]?.text = stats.silverBoxes.toString()
        statsBoxCounts["gold"]?.text = stats.goldBoxes.toString()
        statsBoxCounts["rainbow"]?.text = stats.rainbowBoxes.toString()
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value || !attached) return
        expanded = value
        hideKeyboard()
        bubble.visibility = if (value) View.GONE else View.VISIBLE
        panelScroll.visibility = if (value) View.VISIBLE else View.GONE
        layoutParams.width = if (value) expandedWidth() else collapsedSize()
        layoutParams.height = if (value) expandedHeight() else collapsedSize()
        layoutParams.flags = if (value) EXPANDED_FLAGS else COLLAPSED_FLAGS
        clampPosition()
        windowManager.updateViewLayout(root, layoutParams)
    }

    private fun dragListener(onClick: (() -> Unit)? = null) = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartX = layoutParams.x
                dragStartY = layoutParams.y
                dragged = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - dragStartRawX).toInt()
                val dy = (event.rawY - dragStartRawY).toInt()
                if (abs(dx) > dp(4) || abs(dy) > dp(4)) dragged = true
                layoutParams.x = dragStartX + dx
                layoutParams.y = dragStartY + dy
                clampPosition()
                if (attached) windowManager.updateViewLayout(root, layoutParams)
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) onClick?.invoke()
                true
            }
            else -> false
        }
    }

    private fun clampPosition() {
        val bounds = screenBounds()
        layoutParams.x = layoutParams.x.coerceIn(0, (bounds.width() - layoutParams.width).coerceAtLeast(0))
        layoutParams.y = layoutParams.y.coerceIn(0, (bounds.height() - layoutParams.height).coerceAtLeast(0))
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
        root.clearFocus()
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun collapsedSize() = dp(58)
    private fun expandedWidth() = dp(310)
    private fun expandedHeight(): Int {
        // Overlay context metrics can remain in the display's natural (portrait)
        // orientation while the game is landscape. Use the short display edge so
        // the window always fits inside the visible screen in both orientations.
        val bounds = screenBounds()
        val available = minOf(bounds.width(), bounds.height())
        return minOf(dp(440), (available * 0.88).toInt())
    }

    @Suppress("DEPRECATION")
    private fun screenBounds(): Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windowManager.currentWindowMetrics.bounds
    } else {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val STATUS_UPDATE_MS = 500L
        private const val WATCHDOG_TEST_OVERLAY_SETTLE_MS = 300L
        private const val COLLAPSED_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        private const val EXPANDED_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    }
}
