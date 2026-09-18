package com.example.cookierunbot.capture

import android.os.SystemClock
import com.example.cookierunbot.detection.DetectionResult
import org.opencv.core.Mat

object LatestFrameProvider {
    private val lock = Object()
    private val latestNormalized = Mat()
    private val requestedDebugRaw = Mat()
    private val requestedDebugNormalized = Mat()

    private var captureRunning = false
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var capturedAtNanos = 0L
    private var publishedAtNanos = 0L
    private var generation = 0L
    private var captureFps = 0.0
    private var detection: DetectionResult? = null
    private var error: String? = null
    private var contentTransform: ContentTransform? = null
    private var debugFrameRequestedAfterGeneration: Long? = null
    private var debugFrameGeneration = -1L
    private var debugContentTransform: ContentTransform? = null

    fun setCaptureRunning(running: Boolean) = synchronized(lock) {
        captureRunning = running
        if (running) error = null
        lock.notifyAll()
    }

    fun updateFrame(
        sourceRgba: Mat,
        normalizedRgba: Mat,
        transform: ContentTransform,
        timestampNanos: Long,
        fps: Double,
    ) = synchronized(lock) {
        normalizedRgba.copyTo(latestNormalized)
        sourceWidth = transform.sourceWidth
        sourceHeight = transform.sourceHeight
        contentTransform = transform
        capturedAtNanos = timestampNanos
        publishedAtNanos = SystemClock.elapsedRealtimeNanos()
        generation += 1L
        val requestedAfter = debugFrameRequestedAfterGeneration
        if (requestedAfter != null && generation > requestedAfter) {
            sourceRgba.copyTo(requestedDebugRaw)
            normalizedRgba.copyTo(requestedDebugNormalized)
            debugFrameGeneration = generation
            debugContentTransform = transform
            debugFrameRequestedAfterGeneration = null
        }
        captureFps = fps
        error = null
        lock.notifyAll()
    }

    fun snapshot(): FrameSnapshot? = synchronized(lock) {
        if (latestNormalized.empty()) return null
        FrameSnapshot(
            mat = latestNormalized.clone(),
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            capturedAtNanos = capturedAtNanos,
            publishedAtNanos = publishedAtNanos,
            generation = generation,
            contentTransform = requireNotNull(contentTransform),
        )
    }

    fun currentGeneration(): Long = synchronized(lock) { generation }

    /**
     * Waits for a frame newer than [afterGeneration] and returns only the latest one.
     * Intermediate frames are deliberately discarded, so the detector cannot build a backlog.
     */
    fun awaitNextSnapshot(afterGeneration: Long, timeoutMs: Long): FrameSnapshot? = synchronized(lock) {
        val deadline = SystemClock.elapsedRealtimeNanos() + timeoutMs * 1_000_000L
        while (captureRunning && generation <= afterGeneration) {
            val remainingNanos = deadline - SystemClock.elapsedRealtimeNanos()
            if (remainingNanos <= 0L) return null
            val waitMs = remainingNanos / 1_000_000L
            val waitNanos = (remainingNanos % 1_000_000L).toInt()
            lock.wait(waitMs, waitNanos)
        }
        if (latestNormalized.empty() || generation <= afterGeneration) return null
        FrameSnapshot(
            mat = latestNormalized.clone(),
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            capturedAtNanos = capturedAtNanos,
            publishedAtNanos = publishedAtNanos,
            generation = generation,
            contentTransform = requireNotNull(contentTransform),
        )
    }

    fun currentContentTransform(sourceWidth: Int, sourceHeight: Int): ContentTransform? = synchronized(lock) {
        contentTransform?.takeIf {
            it.sourceWidth == sourceWidth && it.sourceHeight == sourceHeight
        }
    }

    fun requestDebugFramePair(afterGeneration: Long) = synchronized(lock) {
        debugFrameRequestedAfterGeneration = afterGeneration
        debugFrameGeneration = -1L
        debugContentTransform = null
        requestedDebugRaw.release()
        requestedDebugNormalized.release()
    }

    fun consumeDebugFramePair(): DebugFramePair? = synchronized(lock) {
        if (debugFrameGeneration < 0L || requestedDebugRaw.empty() || requestedDebugNormalized.empty()) {
            return null
        }
        val transform = debugContentTransform ?: return null
        DebugFramePair(
            rawMat = requestedDebugRaw.clone(),
            normalizedMat = requestedDebugNormalized.clone(),
            generation = debugFrameGeneration,
            contentTransform = transform,
        ).also {
            requestedDebugRaw.release()
            requestedDebugNormalized.release()
            debugFrameGeneration = -1L
            debugContentTransform = null
        }
    }

    fun cancelDebugFrameRequest() = synchronized(lock) {
        debugFrameRequestedAfterGeneration = null
        debugFrameGeneration = -1L
        debugContentTransform = null
        requestedDebugRaw.release()
        requestedDebugNormalized.release()
    }

    fun updateDetection(result: DetectionResult) = synchronized(lock) {
        detection = result
    }

    fun reportError(message: String) = synchronized(lock) {
        error = message
    }

    fun clear() = synchronized(lock) {
        latestNormalized.release()
        sourceWidth = 0
        sourceHeight = 0
        capturedAtNanos = 0L
        publishedAtNanos = 0L
        // Keep generation monotonic across capture restarts so an awaiting detector
        // cannot get stuck waiting for a restarted stream to catch up to an old value.
        generation += 1L
        captureFps = 0.0
        detection = null
        contentTransform = null
        debugFrameRequestedAfterGeneration = null
        debugFrameGeneration = -1L
        debugContentTransform = null
        requestedDebugRaw.release()
        requestedDebugNormalized.release()
        lock.notifyAll()
    }

    fun diagnostics(): Diagnostics = synchronized(lock) {
        val ageMs = if (capturedAtNanos > 0L) {
            (SystemClock.elapsedRealtimeNanos() - capturedAtNanos).coerceAtLeast(0L) / 1_000_000.0
        } else {
            null
        }
        Diagnostics(
            captureRunning = captureRunning,
            frameSequence = generation,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            normalizedWidth = if (latestNormalized.empty()) 0 else latestNormalized.cols(),
            normalizedHeight = if (latestNormalized.empty()) 0 else latestNormalized.rows(),
            frameAgeMs = ageMs,
            captureFps = captureFps,
            detection = detection,
            error = error,
        )
    }
}

data class FrameSnapshot(
    val mat: Mat,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val capturedAtNanos: Long,
    val publishedAtNanos: Long,
    val generation: Long,
    val contentTransform: ContentTransform,
)

data class DebugFramePair(
    val rawMat: Mat,
    val normalizedMat: Mat,
    val generation: Long,
    val contentTransform: ContentTransform,
) {
    fun release() {
        rawMat.release()
        normalizedMat.release()
    }
}

data class Diagnostics(
    val captureRunning: Boolean,
    val frameSequence: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val normalizedWidth: Int,
    val normalizedHeight: Int,
    val frameAgeMs: Double?,
    val captureFps: Double,
    val detection: DetectionResult?,
    val error: String?,
)
