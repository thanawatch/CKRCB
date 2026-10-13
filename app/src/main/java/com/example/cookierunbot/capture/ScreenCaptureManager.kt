package com.example.cookierunbot.capture

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.example.cookierunbot.diagnostics.RobotLog as Log
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.WindowInsets
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.automation.PerformanceProfile
import com.example.cookierunbot.diagnostics.DebugLogStore
import com.example.cookierunbot.diagnostics.PersistentTrace
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer

class ScreenCaptureManager(
    private val context: Context,
    private val onStopped: () -> Unit,
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var expectedWidth = 0
    private var expectedHeight = 0
    private var densityDpi = 0
    private var activeTransform: ContentTransform? = null

    private val sourceRgba = Mat()
    private val normalizedRgba = Mat()
    private var tightRgbaBytes = ByteArray(0)
    private var frameCount = 0L
    private var fpsWindowStartNanos = 0L
    private var currentFps = 0.0
    private var lastCaptureLogNanos = 0L
    private var lastPersistentTraceNanos = 0L
    private var lastFrameArrivalNanos = 0L
    private var appliedCapturePriority: PerformanceProfile? = null

    fun start(resultCode: Int, permissionData: Intent) {
        check(mediaProjection == null) { "MediaProjection is already running" }

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = requireNotNull(manager.getMediaProjection(resultCode, permissionData)) {
            "MediaProjection permission token could not be opened"
        }
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(CookieRunBotApplication.TAG, "[CAPTURE] MediaProjection stopped by system")
                onStopped()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                captureHandler?.post {
                    createCaptureResources(width, height, "captured content resize")
                }
            }
        }
        projection.registerCallback(callback, Handler(context.mainLooper))

        val (displayWidth, displayHeight) = displaySize()
        val width = displayWidth
        val height = displayHeight
        densityDpi = context.resources.displayMetrics.densityDpi

        // MediaProjection callbacks feed the detector in real time. Keep the default
        // priority for ordinary sessions; the Redfinger profile raises this thread
        // only while that profile is active (see applyCaptureThreadPriority below).
        val thread = HandlerThread("screen-capture").apply { start() }
        val handler = Handler(thread.looper)

        mediaProjection = projection
        captureThread = thread
        captureHandler = handler
        fpsWindowStartNanos = SystemClock.elapsedRealtimeNanos()
        lastFrameArrivalNanos = 0L
        appliedCapturePriority = null
        createCaptureResources(width, height, "started")

        val managerDisplay = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                    captureHandler?.post {
                        val (newWidth, newHeight) = displaySize()
                        createCaptureResources(newWidth, newHeight, "display changed")
                    }
                }
            }
        }
        displayManager = managerDisplay
        displayListener = listener
        managerDisplay.registerDisplayListener(listener, handler)

        LatestFrameProvider.setCaptureRunning(true)
        Log.i(CookieRunBotApplication.TAG, "[CAPTURE] MediaProjection started: ${width}x$height")
    }

    fun stop() {
        displayListener?.let { listener ->
            runCatching { displayManager?.unregisterDisplayListener(listener) }
        }
        displayListener = null
        displayManager = null
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        captureHandler = null
        val thread = captureThread
        thread?.quitSafely()
        if (thread != null && Thread.currentThread() !== thread) {
            runCatching { thread.join(CAPTURE_THREAD_JOIN_TIMEOUT_MS) }
        }
        captureThread = null
        expectedWidth = 0
        expectedHeight = 0
        lastFrameArrivalNanos = 0L
        appliedCapturePriority = null
        activeTransform = null
        ContentGeometryRegistry.clear()
        sourceRgba.release()
        normalizedRgba.release()
        tightRgbaBytes = ByteArray(0)
        LatestFrameProvider.setCaptureRunning(false)
        LatestFrameProvider.clear()
        Log.i(CookieRunBotApplication.TAG, "[CAPTURE] stopped")
    }

    private fun createCaptureResources(width: Int, height: Int, reason: String) {
        val projection = mediaProjection
        val handler = captureHandler ?: return
        if (width <= 0 || height <= 0) return
        updateContentTransform(width, height, reason)
        if (imageReader != null && width == expectedWidth && height == expectedHeight) return

        // Android 14+ allows only one createVirtualDisplay() call per
        // MediaProjection token. Keep the same VirtualDisplay across rotation
        // and resize it instead of creating a second one.
        val existingDisplay = virtualDisplay
        existingDisplay?.setSurface(null)
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                consumeLatestImage(image)
            } catch (error: Exception) {
                Log.e(CookieRunBotApplication.TAG, "[CAPTURE] Frame processing failed", error)
                LatestFrameProvider.reportError(error.message ?: "Frame processing failed")
            } finally {
                image.close()
            }
        }, handler)

        imageReader = reader
        expectedWidth = width
        expectedHeight = height
        if (existingDisplay != null) {
            existingDisplay.resize(width, height, densityDpi)
            existingDisplay.setSurface(reader.surface)
            virtualDisplay = existingDisplay
        } else {
            val activeProjection = requireNotNull(projection) {
                "MediaProjection is unavailable while creating capture resources"
            }
            virtualDisplay = activeProjection.createVirtualDisplay(
                "CookieRunBotCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
        }
        Log.i(
            CookieRunBotApplication.TAG,
            "[CAPTURE] Capture resources recreated: ${width}x$height ($reason, " +
                "orientation=${orientationName(width, height)})",
        )
    }

    private fun consumeLatestImage(image: Image) {
        val frameCapturedAtNanos = SystemClock.elapsedRealtimeNanos()
        val activeProfile = CapturePerformanceProfile.active
        applyCaptureThreadPriority(activeProfile)
        val previousArrivalNanos = lastFrameArrivalNanos
        lastFrameArrivalNanos = frameCapturedAtNanos
        val frameArrivalGapMs = if (previousArrivalNanos > 0L) {
            (frameCapturedAtNanos - previousArrivalNanos).coerceAtLeast(0L) / 1_000_000.0
        } else {
            0.0
        }
        val measurePerformance = activeProfile == PerformanceProfile.REDFINGER_OPTIMIZED
        val plane = image.planes.firstOrNull() ?: return
        val width = image.width
        val height = image.height
        val requiredBytes = width * height * RGBA_CHANNELS
        if (tightRgbaBytes.size != requiredBytes) tightRgbaBytes = ByteArray(requiredBytes)

        copyPlaneToTightRgba(
            buffer = plane.buffer,
            width = width,
            height = height,
            pixelStride = plane.pixelStride,
            rowStride = plane.rowStride,
            destination = tightRgbaBytes,
        )
        val planeCopiedAtNanos = if (measurePerformance) SystemClock.elapsedRealtimeNanos() else 0L

        // Keep Mat allocation and pixel upload timings separate. create() is usually a
        // no-op for steady dimensions, while put() copies the full RGBA payload.
        val matCreateStartedAtNanos = if (measurePerformance) SystemClock.elapsedRealtimeNanos() else 0L
        sourceRgba.create(height, width, CvType.CV_8UC4)
        val matCreatedAtNanos = if (measurePerformance) SystemClock.elapsedRealtimeNanos() else 0L
        sourceRgba.put(0, 0, tightRgbaBytes)
        val matPutCompletedAtNanos = if (measurePerformance) SystemClock.elapsedRealtimeNanos() else 0L
        val transform = activeTransform?.takeIf {
            it.sourceWidth == width && it.sourceHeight == height
        } ?: updateContentTransform(width, height, "frame-size-change")
        val exactSizeFastPath = CapturePerformanceProfile.canUseExactSizeFastPath(transform)
        if (exactSizeFastPath) {
            FrameNormalizer.normalizeExactSize(sourceRgba, normalizedRgba, transform)
        } else {
            FrameNormalizer.normalize(sourceRgba, normalizedRgba, transform)
        }
        val normalizedAtNanos = if (measurePerformance) SystemClock.elapsedRealtimeNanos() else 0L

        val now = SystemClock.elapsedRealtimeNanos()
        frameCount += 1
        val elapsed = now - fpsWindowStartNanos
        if (elapsed >= FPS_WINDOW_NANOS) {
            currentFps = frameCount * 1_000_000_000.0 / elapsed
            frameCount = 0
            fpsWindowStartNanos = now
        }
        // Image.timestamp is supplied by the producer and is not guaranteed
        // to use the same time base as SystemClock.elapsedRealtimeNanos() on
        // every device.  Freshness is measured from the moment this latest
        // image was received, so use the local monotonic arrival timestamp.
        // This prevents a fresh frame from being rejected as stale by the bot
        // safety check.
        LatestFrameProvider.updateFrame(
            sourceRgba,
            normalizedRgba,
            transform,
            frameCapturedAtNanos,
            currentFps,
        )
        val publishedAtNanos = if (measurePerformance) SystemClock.elapsedRealtimeNanos() else 0L

        if (now - lastCaptureLogNanos >= CAPTURE_LOG_INTERVAL_NANOS) {
            Log.i(CookieRunBotApplication.TAG, "[CAPTURE] Frame received: ${width}x$height")
            Log.i(
                CookieRunBotApplication.TAG,
                "[CAPTURE] Normalized: ${FrameNormalizer.LOGICAL_WIDTH}x${FrameNormalizer.LOGICAL_HEIGHT}",
            )
            if (measurePerformance) Log.i(
                CookieRunBotApplication.TAG,
                "[CAPTURE PERF] plane_copy=${millis(planeCopiedAtNanos - frameCapturedAtNanos)}ms " +
                    "mat_create=${millis(matCreatedAtNanos - matCreateStartedAtNanos)}ms " +
                    "mat_put=${millis(matPutCompletedAtNanos - matCreatedAtNanos)}ms " +
                    "rgba_mat=${millis(matPutCompletedAtNanos - planeCopiedAtNanos)}ms " +
                    "normalize=${millis(normalizedAtNanos - matPutCompletedAtNanos)}ms " +
                    "publish=${millis(publishedAtNanos - normalizedAtNanos)}ms " +
                    "total=${millis(publishedAtNanos - frameCapturedAtNanos)}ms " +
                    "callback_start_gap=${String.format(java.util.Locale.US, "%.2f", frameArrivalGapMs)}ms",
                    // The path marker makes device-side before/after captures comparable.
            )
            if (measurePerformance) Log.i(CookieRunBotApplication.TAG, "[CAPTURE PERF] exact_size_fast_path=$exactSizeFastPath fps=${String.format(java.util.Locale.US, "%.2f", currentFps)}")
            lastCaptureLogNanos = now
        }
        if (now - lastPersistentTraceNanos >= PERSISTENT_TRACE_INTERVAL_NANOS) {
            PersistentTrace.log(
                "[CAPTURE] frame generation=${LatestFrameProvider.currentGeneration()} " +
                    "source=${width}x$height contentRect=${rectText(transform)} normalized=1280x720",
            )
            lastPersistentTraceNanos = now
        }
    }

    private fun millis(nanos: Long): String = String.format(java.util.Locale.US, "%.2f", nanos / 1_000_000.0)

    private fun updateContentTransform(width: Int, height: Int, requestedReason: String): ContentTransform {
        val previous = activeTransform
        val transform = FrameNormalizer.contentTransform(width, height, displayCutoutInsets())
        if (transform != previous) {
            val reason = when {
                previous == null -> "initial"
                previous.sourceWidth != width || previous.sourceHeight != height -> {
                    val orientationChanged =
                        (previous.sourceWidth >= previous.sourceHeight) != (width >= height)
                    if (orientationChanged) "orientation" else "display-change"
                }
                previous.displayCutoutInsets != transform.displayCutoutInsets -> "insets-change"
                else -> requestedReason.replace(' ', '-')
            }
            activeTransform = transform
            ContentGeometryRegistry.update(transform)
            val rect = transform.contentRect
            val message = String.format(
                java.util.Locale.US,
                "[NORMALIZER] source=%dx%d sourceAspect=%.4f target=%dx%d targetAspect=%.4f " +
                    "contentRect=(%d,%d,%d,%d) contentSize=%dx%d scaleX=%.4f scaleY=%.4f " +
                    "offsetX=%d offsetY=%d cutoutInsets=(%d,%d,%d,%d) reason=%s",
                width,
                height,
                width.toDouble() / height,
                FrameNormalizer.LOGICAL_WIDTH,
                FrameNormalizer.LOGICAL_HEIGHT,
                FrameNormalizer.LOGICAL_WIDTH.toDouble() / FrameNormalizer.LOGICAL_HEIGHT,
                rect.x,
                rect.y,
                rect.x + rect.width,
                rect.y + rect.height,
                rect.width,
                rect.height,
                transform.scaleX,
                transform.scaleY,
                transform.offsetX,
                transform.offsetY,
                transform.displayCutoutInsets.left,
                transform.displayCutoutInsets.top,
                transform.displayCutoutInsets.right,
                transform.displayCutoutInsets.bottom,
                reason,
            )
            Log.i(CookieRunBotApplication.TAG, message)
            DebugLogStore.log(message)
            PersistentTrace.log(message)
        }
        return transform
    }

    @Suppress("DEPRECATION")
    private fun displayCutoutInsets(): ContentInsets {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val insets = windowManager.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.displayCutout(),
                )
                ContentInsets(insets.left, insets.top, insets.right, insets.bottom)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                val cutout = windowManager.defaultDisplay.cutout
                ContentInsets(
                    cutout?.safeInsetLeft ?: 0,
                    cutout?.safeInsetTop ?: 0,
                    cutout?.safeInsetRight ?: 0,
                    cutout?.safeInsetBottom ?: 0,
                )
            }
            else -> ContentInsets()
        }
    }

    private fun rectText(transform: ContentTransform): String {
        val rect = transform.contentRect
        return "(${rect.x},${rect.y},${rect.x + rect.width},${rect.y + rect.height})"
    }

    private fun applyCaptureThreadPriority(profile: PerformanceProfile) {
        if (appliedCapturePriority == profile) return
        val priority = if (profile == PerformanceProfile.REDFINGER_OPTIMIZED) {
            Process.THREAD_PRIORITY_DISPLAY
        } else {
            Process.THREAD_PRIORITY_DEFAULT
        }
        runCatching { Process.setThreadPriority(priority) }
            .onFailure { error ->
                Log.w(
                    CookieRunBotApplication.TAG,
                    "[CAPTURE] Unable to set capture thread priority=$priority: ${error.message}",
                )
            }
        appliedCapturePriority = profile
        Log.i(
            CookieRunBotApplication.TAG,
            "[CAPTURE] thread priority updated profile=$profile priority=$priority",
        )
    }

    private fun copyPlaneToTightRgba(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
        destination: ByteArray,
    ) {
        buffer.rewind()
        val tightRowBytes = width * RGBA_CHANNELS
        // Most RGBA_8888 devices expose tightly packed rows. Copy the whole plane
        // in one bulk operation instead of issuing one ByteBuffer copy per row.
        if (pixelStride == RGBA_CHANNELS && rowStride == tightRowBytes) {
            buffer.get(destination, 0, tightRowBytes * height)
            return
        }
        if (pixelStride == RGBA_CHANNELS) {
            repeat(height) { row ->
                buffer.position(row * rowStride)
                buffer.get(destination, row * tightRowBytes, tightRowBytes)
            }
            return
        }

        repeat(height) { row ->
            repeat(width) { column ->
                val sourceOffset = row * rowStride + column * pixelStride
                val targetOffset = (row * width + column) * RGBA_CHANNELS
                destination[targetOffset] = buffer.get(sourceOffset)
                destination[targetOffset + 1] = buffer.get(sourceOffset + 1)
                destination[targetOffset + 2] = buffer.get(sourceOffset + 2)
                destination[targetOffset + 3] = buffer.get(sourceOffset + 3)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun orientationName(width: Int, height: Int): String =
        if (width >= height) "landscape" else "portrait"

    companion object {
        private const val PERSISTENT_TRACE_INTERVAL_NANOS = 5_000_000_000L
        private const val MAX_IMAGES = 2
        private const val RGBA_CHANNELS = 4
        private const val FPS_WINDOW_NANOS = 1_000_000_000L
        private const val CAPTURE_LOG_INTERVAL_NANOS = 1_000_000_000L
        private const val CAPTURE_THREAD_JOIN_TIMEOUT_MS = 1_000L
    }
}
