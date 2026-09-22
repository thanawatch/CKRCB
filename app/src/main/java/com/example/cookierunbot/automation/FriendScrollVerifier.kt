package com.example.cookierunbot.automation

import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

internal data class FriendScrollSample(val pixels: ByteArray)

internal data class FriendScrollVerification(
    val changed: Boolean,
    val difference: Double,
)

internal fun verifiedFriendScrollProgressEvent(phase: String, changed: Boolean): String? = when {
    !changed -> null
    phase == "FIND_TOP" -> "TOP_SCROLL_CHANGED"
    phase == "SCROLL_DOWN" -> "SCROLL_CHANGED"
    else -> null
}

internal fun isMeaningfulFriendLifeProgressEvent(event: String): Boolean = event in setOf(
    "TOP_SCROLL_CHANGED",
    "TOP_DETECTED",
    "SEND_DETECTED",
    "SEND_COMPLETE",
    "BOTTOM_DETECTED",
    "SCROLL_CHANGED",
)

internal object FriendScrollVerifier {
    const val DIFFERENCE_THRESHOLD = 0.03
    private const val SAMPLE_WIDTH = 110
    private const val SAMPLE_HEIGHT = 60

    fun capture(normalizedRgba: Mat, viewport: Rect): FriendScrollSample {
        val roi = normalizedRgba.submat(viewport)
        val gray = Mat()
        val resized = Mat()
        return try {
            Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(gray, resized, Size(SAMPLE_WIDTH.toDouble(), SAMPLE_HEIGHT.toDouble()))
            val pixels = ByteArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
            resized.get(0, 0, pixels)
            FriendScrollSample(pixels)
        } finally {
            resized.release()
            gray.release()
            roi.release()
        }
    }

    fun compare(before: FriendScrollSample, after: FriendScrollSample): FriendScrollVerification {
        require(before.pixels.size == after.pixels.size) { "Friend scroll samples must have equal size" }
        var differenceSum = 0L
        for (index in before.pixels.indices) {
            val beforeValue = before.pixels[index].toInt() and 0xff
            val afterValue = after.pixels[index].toInt() and 0xff
            differenceSum += kotlin.math.abs(beforeValue - afterValue)
        }
        val normalizedDifference = differenceSum.toDouble() / (before.pixels.size * 255.0)
        return FriendScrollVerification(
            changed = normalizedDifference >= DIFFERENCE_THRESHOLD,
            difference = normalizedDifference,
        )
    }
}
