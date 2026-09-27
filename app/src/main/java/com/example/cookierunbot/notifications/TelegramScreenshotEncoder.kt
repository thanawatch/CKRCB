package com.example.cookierunbot.notifications

import com.example.cookierunbot.capture.FrameNormalizer
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Telegram-only export. LatestFrameProvider's cloned frame remains RGBA and is never modified.
 * OpenCV's four-channel PNG encoder expects BGRA; explicitly reorder into a separate Mat.
 */
internal object TelegramScreenshotEncoder {
    fun encodeNormalizedPng(normalizedRgba: Mat): ByteArray {
        require(
            normalizedRgba.rows() == FrameNormalizer.LOGICAL_HEIGHT &&
                normalizedRgba.cols() == FrameNormalizer.LOGICAL_WIDTH &&
                normalizedRgba.type() == CvType.CV_8UC4
        ) { "Telegram screenshot requires an RGBA 1280x720 frame." }

        val bgra = Mat()
        val encoded = MatOfByte()
        return try {
            Imgproc.cvtColor(normalizedRgba, bgra, Imgproc.COLOR_RGBA2BGRA)
            check(Imgcodecs.imencode(".png", bgra, encoded)) {
                "Unable to encode Telegram PNG screenshot."
            }
            encoded.toArray()
        } finally {
            encoded.release()
            bgra.release()
        }
    }
}
