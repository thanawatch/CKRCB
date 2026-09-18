package com.example.cookierunbot.automation

import android.content.Context
import com.example.cookierunbot.capture.FrameNormalizer
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResultImageSaver(private val context: Context) {
    private val imageStore = PublicImageStore(context)

    fun save(normalizedRgba: Mat, roundNumber: Int, stageType: String): String {
        require(
            normalizedRgba.cols() == FrameNormalizer.LOGICAL_WIDTH &&
                normalizedRgba.rows() == FrameNormalizer.LOGICAL_HEIGHT,
        ) { "Result image must be normalized 1280x720" }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US).format(Date())
        val filename =
            "round_${roundNumber.toString().padStart(4, '0')}_${stageType}_$timestamp.png"
        val bgra = Mat()
        return try {
            Imgproc.cvtColor(normalizedRgba, bgra, Imgproc.COLOR_RGBA2BGRA)
            imageStore.savePng(bgra, "results", filename)
        } finally {
            bgra.release()
        }
    }
}
