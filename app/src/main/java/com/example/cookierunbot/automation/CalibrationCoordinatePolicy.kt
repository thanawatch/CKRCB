package com.example.cookierunbot.automation

import kotlin.math.roundToInt

/** Pixel coordinates in the exact 1280x720 frame published to StateDetector. */
internal data class CalibrationPoint(val x: Int, val y: Int)

/** Keeps preview selection and frame freshness independently testable from Android UI. */
internal object CalibrationCoordinatePolicy {
    const val WIDTH = 1280
    const val HEIGHT = 720

    fun fromImageTouch(touchX: Float, touchY: Float, left: Float, top: Float, scale: Float): CalibrationPoint? {
        if (scale <= 0f || !scale.isFinite()) return null
        val x = (touchX - left) / scale
        val y = (touchY - top) / scale
        if (x < 0f || x >= WIDTH || y < 0f || y >= HEIGHT) return null
        return CalibrationPoint(x.roundToInt().coerceIn(0, WIDTH - 1), y.roundToInt().coerceIn(0, HEIGHT - 1))
    }

    fun freshAfterHidden(
        baselineGeneration: Long,
        capturedGeneration: Long,
        hiddenAtNanos: Long,
        capturedAtNanos: Long,
        ageNanos: Long,
    ): Boolean = capturedGeneration > baselineGeneration && capturedAtNanos >= hiddenAtNanos &&
        ageNanos in 0L..1_000_000_000L
}
