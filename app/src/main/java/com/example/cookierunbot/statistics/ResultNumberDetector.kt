package com.example.cookierunbot.statistics

import android.content.Context
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class ResultNumberDetector(context: Context) : AutoCloseable {
    data class Reading(val value: Int?, val confidence: Double, val reason: String? = null)
    data class Result(val coins: Reading, val xp: Reading)

    private val templates = buildMap {
        (0..9).forEach { digit ->
            runCatching {
                context.assets.open("templates/digits/$digit.png").use { input ->
                    val bitmap = requireNotNull(BitmapFactory.decodeStream(input))
                    val rgba = Mat()
                    val gray = Mat()
                    Utils.bitmapToMat(bitmap, rgba)
                    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                    rgba.release()
                    bitmap.recycle()
                    gray
                }
            }.getOrNull()?.let { put(digit, it) }
        }
    }

    fun read(frame: Mat): Result = Result(
        coins = readRegion(frame, COINS_ROI),
        xp = readRegion(frame, XP_ROI),
    )

    private fun readRegion(frame: Mat, roi: Rect): Reading {
        if (frame.cols() != 1280 || frame.rows() != 720) return Reading(null, 0.0, "invalid_frame_size")
        if (templates.size != 10) return Reading(null, 0.0, "digit_templates_missing")
        val crop = frame.submat(roi)
        val gray = Mat()
        val threshold = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        return try {
            Imgproc.cvtColor(crop, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(gray, threshold, 125.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Imgproc.findContours(threshold, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val boxes = contours.map(Imgproc::boundingRect)
                .filter { it.height in 16..38 && it.width in 4..34 && it.x >= 180 }
                .sortedBy { it.x }
            if (boxes.isEmpty()) return Reading(null, 0.0, "no_digit_components")
            val values = ArrayList<Int>(boxes.size)
            val scores = ArrayList<Double>(boxes.size)
            boxes.forEach { box ->
                val character = threshold.submat(box)
                try {
                    var bestDigit = -1
                    var bestScore = -1.0
                    templates.forEach { (digit, template) ->
                        val resized = Mat()
                        try {
                            Imgproc.resize(template, resized, Size(character.cols().toDouble(), character.rows().toDouble()))
                            val scoreMat = Mat()
                            try {
                                Imgproc.matchTemplate(character, resized, scoreMat, Imgproc.TM_CCOEFF_NORMED)
                                val score = Core.minMaxLoc(scoreMat).maxVal
                                if (score > bestScore) { bestScore = score; bestDigit = digit }
                            } finally { scoreMat.release() }
                        } finally { resized.release() }
                    }
                    values += bestDigit
                    scores += bestScore
                } finally { character.release() }
            }
            val confidence = scores.minOrNull() ?: 0.0
            if (values.any { it < 0 } || confidence < MIN_DIGIT_CONFIDENCE) {
                Reading(null, confidence, "low_confidence")
            } else {
                Reading(constructNumber(values), confidence)
            }
        } catch (error: Exception) {
            Reading(null, 0.0, error.message ?: "recognition_error")
        } finally {
            contours.forEach(MatOfPoint::release)
            hierarchy.release()
            threshold.release()
            gray.release()
            crop.release()
        }
    }

    override fun close() = templates.values.forEach(Mat::release)

    companion object {
        val COINS_ROI = Rect(750, 360, 400, 90)
        val XP_ROI = Rect(750, 440, 400, 80)
        const val MIN_DIGIT_CONFIDENCE = 0.45
        internal fun constructNumber(digits: List<Int>): Int? = digits.joinToString("").toIntOrNull()
    }
}
