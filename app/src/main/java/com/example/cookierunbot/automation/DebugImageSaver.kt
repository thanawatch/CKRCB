package com.example.cookierunbot.automation

import android.content.Context
import com.example.cookierunbot.detection.DetectionResult
import com.example.cookierunbot.detection.StateDetector
import com.example.cookierunbot.detection.TemplateMatch
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.util.Locale

class DebugImageSaver(context: Context) {
    private val imageStore = PublicImageStore(context)

    fun saveStage(frameRgba: Mat, result: DetectionResult, filename: String): String =
        save(
            frameRgba,
            StateDetector.roiFor(result.state),
            result.x,
            result.y,
            result.width,
            result.height,
            result.score,
            filename,
        )

    fun saveTemplate(frameRgba: Mat, roi: Rect, match: TemplateMatch, filename: String): String =
        save(frameRgba, roi, match.x, match.y, match.width, match.height, match.score, filename)

    private fun save(
        frameRgba: Mat,
        roi: Rect,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        score: Double,
        filename: String,
    ): String {
        val crop = frameRgba.submat(roi).clone()
        try {
            if (x >= 0 && y >= 0 && width > 0 && height > 0) {
                Imgproc.rectangle(
                    crop,
                    Point((x - roi.x).toDouble(), (y - roi.y).toDouble()),
                    Point((x - roi.x + width).toDouble(), (y - roi.y + height).toDouble()),
                    Scalar(0.0, 255.0, 0.0, 255.0),
                    2,
                )
            }
            Imgproc.putText(
                crop,
                "score=${String.format(Locale.US, "%.4f", score)}",
                Point(8.0, 24.0),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                0.65,
                Scalar(255.0, 255.0, 0.0, 255.0),
                2,
            )
            val bgra = Mat()
            try {
                Imgproc.cvtColor(crop, bgra, Imgproc.COLOR_RGBA2BGRA)
                return imageStore.savePng(
                    bgra,
                    subdirectory = "debug",
                    filename = filename,
                    replaceExisting = true,
                )
            } finally {
                bgra.release()
            }
        } finally {
            crop.release()
        }
    }
}
