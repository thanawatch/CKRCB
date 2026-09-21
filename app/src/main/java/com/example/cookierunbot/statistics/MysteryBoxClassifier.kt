package com.example.cookierunbot.statistics

import android.content.Context
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class MysteryBoxClassifier(context: Context) : AutoCloseable {
    private val templates = buildMap {
        listOf("wood", "silver", "gold", "rainbow").forEach { grade ->
            runCatching {
                context.assets.open("templates/BOX_${grade.uppercase()}.png").use { input ->
                    val bitmap = requireNotNull(BitmapFactory.decodeStream(input))
                    Mat().also { Utils.bitmapToMat(bitmap, it); bitmap.recycle() }
                }
            }.getOrNull()?.let { put(grade, it) }
        }
    }

    fun classify(frame: Mat): Pair<String, Double> {
        if (templates.isEmpty()) return "unknown" to -1.0
        val roi = Rect(192, 144, 896, 396)
        val crop = frame.submat(roi)
        return try {
            var bestGrade = "unknown"
            var bestScore = -1.0
            templates.forEach { (grade, template) ->
                listOf(0.95, 1.0, 1.05, 1.10, 1.15).forEach { scale ->
                    val candidate = Mat()
                    try {
                        Imgproc.resize(template, candidate, Size(template.cols() * scale, template.rows() * scale))
                        if (candidate.cols() <= crop.cols() && candidate.rows() <= crop.rows()) {
                            val result = Mat()
                            try {
                                Imgproc.matchTemplate(crop, candidate, result, Imgproc.TM_CCOEFF_NORMED)
                                val score = Core.minMaxLoc(result).maxVal
                                if (score > bestScore) { bestScore = score; bestGrade = grade }
                            } finally { result.release() }
                        }
                    } finally { candidate.release() }
                }
            }
            if (bestScore >= 0.60) bestGrade to bestScore else "unknown" to bestScore
        } finally { crop.release() }
    }

    override fun close() = templates.values.forEach(Mat::release)
}
