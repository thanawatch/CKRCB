package com.example.cookierunbot.automation

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.WindowInsets
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.cookierunbot.capture.ContentTransform
import com.example.cookierunbot.capture.LatestFrameProvider
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.roundToInt

/** A separate, non-gameplay calibration window. Never dispatches an Accessibility gesture. */
internal class CoordinateCalibrationOverlay(
    sourceContext: Context,
    private val bubbleRoot: View,
    private val onClosed: () -> Unit,
) {
    private val displayManager =
        sourceContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    // An Application Context can retain portrait metrics while an overlay is landscape.
    // A window-specific context tracks the display configuration used by this overlay.
    private val context: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.let { display ->
            sourceContext.createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null,
            )
        } ?: sourceContext
    } else sourceContext
    private val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "coordinate-calibration") }
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(28, 31, 39))
    }
    private val scroller = ScrollView(context)
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(12))
        setBackgroundColor(Color.rgb(28, 31, 39))
    }
    private val status = label("Capture pending")
    private val coordinates = label("Tap a point on the captured 1280 x 720 image").apply { textSize = 18f }
    private val image = CalibrationImageView(context) { point -> updateCoordinates(point) }
    private val name = EditText(context).apply {
        setSingleLine(true)
        setText("POST_RELAY_1")
        hint = "Point name (e.g. POST_RELAY_1)"
        setTextColor(Color.WHITE)
        setHintTextColor(Color.LTGRAY)
    }
    private var currentBitmap: Bitmap? = null
    private var currentTransform: ContentTransform? = null
    private var frameGeneration = -1L
    private var windowAdded = false
    private var closed = false
    private var requestId = 0L
    private var captureInProgress = false
    private var panelParams: WindowManager.LayoutParams? = null
    private var layoutListenersRegistered = false
    private val reflowPanel = Runnable { resizePanelForCurrentDisplay() }
    private val configurationListener = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = schedulePanelReflow()
        override fun onLowMemory() = Unit
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = schedulePanelReflow()
    }

    private fun registerLayoutListeners() {
        if (layoutListenersRegistered) return
        context.registerComponentCallbacks(configurationListener)
        displayManager.registerDisplayListener(displayListener, ui)
        layoutListenersRegistered = true
    }

    private fun schedulePanelReflow() {
        if (closed) return
        ui.removeCallbacks(reflowPanel)
        // Recheck after rotation/insets settle as some devices notify before their metrics update.
        ui.post(reflowPanel)
        ui.postDelayed(reflowPanel, 200L)
    }

    fun open(): Boolean {
        if (closed || BotRuntime.isRunning() || !LatestFrameProvider.diagnostics().captureRunning) {
            Toast.makeText(context, "Stop Bot and start Screen Capture before calibration", Toast.LENGTH_LONG).show()
            return false
        }
        buildUi()
        registerLayoutListeners()
        capture()
        return true
    }

    private fun buildUi() {
        // Persistent header stays outside the scroll area, including in portrait.
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button("✕ Close") { close() }, LinearLayout.LayoutParams(dp(96), dp(48)))
            addView(label("Coordinate Calibration"), LinearLayout.LayoutParams(0, dp(48), 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        root.addView(scroller, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        scroller.addView(content)
        content.addView(status)
        content.addView(label("Pinch or use +/- to zoom. Drag to pan. Tap a button on the still image."))
        content.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Zoom -") { image.zoomBy(1f / 1.5f) }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(button("Zoom +") { image.zoomBy(1.5f) }, LinearLayout.LayoutParams(0, dp(44), 1f))
        })
        content.addView(coordinates)
        content.addView(name)
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Save X,Y") { saveSelectedPoint() }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(button("Export PNG") { exportMarkedImage() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        })
        content.addView(button("Load saved X,Y") { loadSavedPoint() })
        content.addView(button("Capture new clean frame") { capture() })
        content.addView(label("Saved points are calibration notes only; no bot taps or game settings are changed."))
    }

    private fun capture() {
        if (captureInProgress || closed) return
        if (BotRuntime.isRunning() || !LatestFrameProvider.diagnostics().captureRunning) {
            Toast.makeText(context, "Stop Bot and keep Screen Capture running", Toast.LENGTH_LONG).show()
            return
        }
        captureInProgress = true
        val request = ++requestId
        if (windowAdded) {
            windows.removeViewImmediate(root)
            windowAdded = false
        }
        bubbleRoot.visibility = View.INVISIBLE // Hide the complete RobotRun window, not merely its button.
        val hiddenAt = SystemClock.elapsedRealtimeNanos()
        ui.postDelayed({
            if (closed || request != requestId) return@postDelayed
            val baseline = LatestFrameProvider.currentGeneration() // Must be taken AFTER overlay settle.
            try {
                worker.execute {
                    var frameBitmap: Bitmap? = null
                    var transform: ContentTransform? = null
                    var generation = -1L
                    var failure: String? = null
                    val frame = LatestFrameProvider.awaitNextSnapshot(baseline, FRAME_TIMEOUT_MS)
                    try {
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (frame == null || !CalibrationCoordinatePolicy.freshAfterHidden(
                                baseline, frame.generation, hiddenAt, frame.capturedAtNanos,
                                now - frame.capturedAtNanos,
                            ) || BotRuntime.isRunning()
                        ) {
                            failure = "No clean fresh frame received. Try again with capture running."
                        } else {
                            frameBitmap = Bitmap.createBitmap(
                                CalibrationCoordinatePolicy.WIDTH,
                                CalibrationCoordinatePolicy.HEIGHT,
                                Bitmap.Config.ARGB_8888,
                            )
                            Utils.matToBitmap(frame.mat, frameBitmap)
                            transform = frame.contentTransform
                            generation = frame.generation
                        }
                    } catch (error: Exception) {
                        failure = "Capture failed: ${error.javaClass.simpleName}"
                    } finally {
                        frame?.mat?.release()
                    }
                    val resultBitmap = frameBitmap
                    val resultTransform = transform
                    val resultGeneration = generation
                    val resultFailure = failure
                    ui.post {
                        if (closed || request != requestId) {
                            resultBitmap?.recycle()
                            return@post
                        }
                        captureInProgress = false
                        if (resultBitmap != null && resultTransform != null) {
                            currentBitmap?.recycle()
                            currentBitmap = resultBitmap
                            currentTransform = resultTransform
                            frameGeneration = resultGeneration
                            image.showFrame(resultBitmap)
                            coordinates.text = "Tap a point (logical X,Y 0..1279, 0..719)"
                            val rect = resultTransform.contentRect
                            status.text = "Frame #$resultGeneration  • source ${resultTransform.sourceWidth}x${resultTransform.sourceHeight}  • contentRect (${rect.x},${rect.y},${rect.width},${rect.height})"
                        } else {
                            resultBitmap?.recycle()
                            status.text = resultFailure ?: "Unable to capture"
                            if (currentBitmap == null) {
                                close()
                                Toast.makeText(context, status.text, Toast.LENGTH_LONG).show()
                                return@post
                            }
                        }
                        showPanel()
                    }
                }
            } catch (_: RejectedExecutionException) {
                captureInProgress = false
                close()
            }
        }, OVERLAY_SETTLE_MS)
    }

    private fun showPanel() {
        if (closed || windowAdded) return
        val bounds = displayBounds()
        val size = CalibrationPanelLayoutPolicy.fit(bounds.width(), bounds.height(), dp(620))
        val params = WindowManager.LayoutParams(
            size.width,
            size.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Do not lay this dialog beneath/through the status and navigation bars.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        try {
            windows.addView(root, params)
            panelParams = params
            windowAdded = true
            schedulePanelReflow()
        } catch (error: Exception) {
            Toast.makeText(context, "Cannot open calibration window", Toast.LENGTH_LONG).show()
            close()
        }
    }

    private fun resizePanelForCurrentDisplay() {
        if (closed || !windowAdded) return
        val params = panelParams ?: return
        val bounds = displayBounds()
        val size = CalibrationPanelLayoutPolicy.fit(bounds.width(), bounds.height(), dp(620))
        if (params.width == size.width && params.height == size.height) return
        params.width = size.width
        params.height = size.height
        if (currentTransform != null) {
            status.text = "Display changed: this still image uses the ORIGINAL contentRect. " +
                "Capture new clean frame for current physical X/Y."
        }
        try {
            windows.updateViewLayout(root, params)
        } catch (_: Exception) {
            close() // Never leave the bubble hidden behind an unusable calibration dialog.
        }
    }

    private fun updateCoordinates(point: CalibrationPoint) {
        val transform = currentTransform ?: return
        val r = transform.contentRect
        val x = (r.x + point.x.toDouble() * r.width / CalibrationCoordinatePolicy.WIDTH).roundToInt()
            .coerceIn(0, transform.sourceWidth - 1)
        val y = (r.y + point.y.toDouble() * r.height / CalibrationCoordinatePolicy.HEIGHT).roundToInt()
            .coerceIn(0, transform.sourceHeight - 1)
        coordinates.text = "Logical X=${point.x}  Y=${point.y}  | physical for THIS frame X=$x Y=$y"
    }

    private fun saveSelectedPoint() {
        val point = image.selectedPoint() ?: return showMessage("Tap a point on the image first")
        val transform = currentTransform ?: return
        val key = validatedName() ?: return
        val r = transform.contentRect
        context.getSharedPreferences("calibration_points", Context.MODE_PRIVATE).edit()
            .putInt("$key.x", point.x)
            .putInt("$key.y", point.y)
            .putString("$key.frame", "${transform.sourceWidth}x${transform.sourceHeight};rect=${r.x},${r.y},${r.width},${r.height};generation=$frameGeneration")
            .apply()
        showMessage("Saved $key = (${point.x},${point.y}); gameplay unchanged")
    }

    private fun loadSavedPoint() {
        val key = validatedName() ?: return
        val prefs = context.getSharedPreferences("calibration_points", Context.MODE_PRIVATE)
        if (!prefs.contains("$key.x") || !prefs.contains("$key.y")) {
            showMessage("No saved point for $key")
            return
        }
        image.select(CalibrationPoint(prefs.getInt("$key.x", 0), prefs.getInt("$key.y", 0)))
    }

    private fun exportMarkedImage() {
        val point = image.selectedPoint() ?: return showMessage("Tap a point on the image first")
        val original = currentBitmap ?: return
        val key = validatedName() ?: return
        val annotated = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 4f }
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 17f, paint)
        canvas.drawLine((point.x - 24).toFloat(), point.y.toFloat(), (point.x + 24).toFloat(), point.y.toFloat(), paint)
        canvas.drawLine(point.x.toFloat(), (point.y - 24).toFloat(), point.x.toFloat(), (point.y + 24).toFloat(), paint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; textSize = 25f; style = Paint.Style.FILL; isFakeBoldText = true }
        val caption = "$key (${point.x}, ${point.y})"
        val labelWidth = textPaint.measureText(caption)
        val textX = point.x.toFloat().coerceIn(4f, (CalibrationCoordinatePolicy.WIDTH - labelWidth - 4f).coerceAtLeast(4f))
        val textY = if (point.y > 65) point.y - 27f else point.y + 48f
        canvas.drawText(caption, textX, textY, textPaint)
        try {
            worker.execute {
                val mat = Mat()
                try {
                    Utils.bitmapToMat(annotated, mat)
                    val path = PublicImageStore(context).savePng(mat, "calibration", "${key}_${System.currentTimeMillis()}.png")
                    ui.post { if (!closed) showMessage("Exported: $path") }
                } catch (error: Exception) {
                    ui.post { if (!closed) showMessage("Export failed: ${error.javaClass.simpleName}") }
                } finally {
                    mat.release()
                    annotated.recycle()
                }
            }
        } catch (_: RejectedExecutionException) { annotated.recycle() }
    }

    private fun validatedName(): String? {
        val label = name.text.toString().trim().uppercase(java.util.Locale.US)
        if (!label.matches(Regex("[A-Z0-9_]{1,48}"))) {
            showMessage("Use a name containing A-Z, 0-9 and _ only")
            return null
        }
        return label
    }

    private fun showMessage(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    private fun label(message: String) = TextView(context).apply {
        text = message; textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(3), dp(6), dp(3), dp(6))
    }
    private fun button(message: String, action: () -> Unit) = Button(context).apply {
        text = message; setOnClickListener { action() }
    }
    private fun dp(pixels: Int): Int = (pixels * context.resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    private fun displayBounds(): Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val metrics = windows.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        Rect(
            0, 0,
            (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(1),
            (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1),
        )
    } else {
        val metrics = android.util.DisplayMetrics()
        windows.defaultDisplay.getMetrics(metrics)
        Rect(0, 0, metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
    }

    fun close() {
        if (closed) return
        closed = true
        requestId++
        worker.shutdownNow()
        ui.removeCallbacks(reflowPanel)
        if (layoutListenersRegistered) {
            context.unregisterComponentCallbacks(configurationListener)
            displayManager.unregisterDisplayListener(displayListener)
            layoutListenersRegistered = false
        }
        if (windowAdded) {
            windowAdded = false
            runCatching { windows.removeViewImmediate(root) }
        }
        panelParams = null
        currentBitmap?.recycle()
        currentBitmap = null
        if (bubbleRoot.parent != null) bubbleRoot.visibility = View.VISIBLE
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
        onClosed()
    }

    companion object {
        private const val OVERLAY_SETTLE_MS = 500L
        private const val FRAME_TIMEOUT_MS = 3_500L
    }
}
