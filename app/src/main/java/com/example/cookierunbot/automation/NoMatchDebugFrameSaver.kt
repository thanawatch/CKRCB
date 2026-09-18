package com.example.cookierunbot.automation

import android.content.Context
import com.example.cookierunbot.capture.DebugFramePair
import com.example.cookierunbot.capture.FrameNormalizer
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DebugFramePaths(val raw: String, val normalized: String)

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
        )
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
}
