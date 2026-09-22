package com.example.cookierunbot.automation

import android.content.Context
import com.example.cookierunbot.capture.DebugFramePair
import com.example.cookierunbot.capture.FrameNormalizer
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DebugFramePaths(
    val raw: String,
    val normalized: String,
    val normalizedPng: ByteArray,
)

/** Saves the raw source and the exact normalized frame consumed by OpenCV. */
class NoMatchDebugFrameSaver(context: Context) {
    private val imageStore = PublicImageStore(context.applicationContext)

    fun save(pair: DebugFramePair): DebugFramePaths {
        require(
            pair.normalizedMat.cols() == FrameNormalizer.LOGICAL_WIDTH &&
                pair.normalizedMat.rows() == FrameNormalizer.LOGICAL_HEIGHT,
        ) { "No-match debug frame must be normalized 1280x720" }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val rawName = "debug_raw_${pair.rawMat.cols()}x${pair.rawMat.rows()}_$timestamp.png"
        val normalizedName =
            "debug_normalized_${pair.normalizedMat.cols()}x${pair.normalizedMat.rows()}_$timestamp.png"
        return DebugFramePaths(
            raw = saveRgba(pair.rawMat, rawName),
            normalized = saveRgba(pair.normalizedMat, normalizedName),
            normalizedPng = encodeNormalizedPng(pair.normalizedMat),
        )
    }

    fun encodeNormalizedPng(normalizedRgba: Mat): ByteArray {
        require(
            normalizedRgba.cols() == FrameNormalizer.LOGICAL_WIDTH &&
                normalizedRgba.rows() == FrameNormalizer.LOGICAL_HEIGHT,
        ) { "Diagnostic frame must be normalized 1280x720" }
        return encodePng(normalizedRgba)
    }

    private fun saveRgba(rgba: Mat, filename: String): String {
        val bgra = Mat()
        return try {
            Imgproc.cvtColor(rgba, bgra, Imgproc.COLOR_RGBA2BGRA)
            imageStore.savePng(bgra, "debug", filename)
        } finally {
            bgra.release()
        }
    }

    private fun encodePng(rgba: Mat): ByteArray {
        val encoded = MatOfByte()
        return try {
            check(Imgcodecs.imencode(".png", rgba, encoded)) { "Unable to encode normalized debug frame" }
            encoded.toArray()
        } finally { encoded.release() }
    }
}
