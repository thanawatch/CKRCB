package com.example.cookierunbot.statistics

import android.content.Context
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

data class BoxDetection(val grade: String, val score: Double, val rect: Rect)
data class BoxScanResult(val candidateCount: Int, val detections: List<BoxDetection>)
internal object MysteryBoxPhasePolicy {
    fun shouldClassify(phase: Int): Boolean = phase == 1
}

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

    fun classifyAll(frame: Mat): List<BoxDetection> = scan(frame).detections

    fun scan(frame: Mat): BoxScanResult {
        if (templates.isEmpty() || frame.cols() < ROI.x + ROI.width || frame.rows() < ROI.y + ROI.height) {
            return BoxScanResult(0, emptyList())
        }
        val crop = frame.submat(ROI)
        return try {
            val candidates = mutableListOf<BoxDetection>()
            templates.forEach { (grade, template) ->
                SCALES.forEach { scale ->
                    val candidate = Mat()
                    try {
                        Imgproc.resize(template, candidate, Size(template.cols() * scale, template.rows() * scale))
                        if (candidate.cols() <= crop.cols() && candidate.rows() <= crop.rows()) {
                            collectLocalCandidates(crop, candidate, grade, candidates)
                        }
                    } finally { candidate.release() }
                }
            }
            BoxScanResult(candidates.size, suppressOverlaps(candidates))
        } finally { crop.release() }
    }

    private fun collectLocalCandidates(crop: Mat, template: Mat, grade: String, output: MutableList<BoxDetection>) {
        val result = Mat()
        try {
            Imgproc.matchTemplate(crop, template, result, Imgproc.TM_CCOEFF_NORMED)
            repeat(MAX_LOCAL_CANDIDATES_PER_SCALE) {
                val maximum = Core.minMaxLoc(result)
                if (maximum.maxVal < MATCH_THRESHOLD) return
                val x = maximum.maxLoc.x.toInt()
                val y = maximum.maxLoc.y.toInt()
                output += BoxDetection(
                    grade,
                    maximum.maxVal,
                    Rect(ROI.x + x, ROI.y + y, template.cols(), template.rows()),
                )
                val radiusX = (template.cols() / 2).coerceAtLeast(1)
                val radiusY = (template.rows() / 2).coerceAtLeast(1)
                Imgproc.rectangle(
                    result,
                    Point((x - radiusX).coerceAtLeast(0).toDouble(), (y - radiusY).coerceAtLeast(0).toDouble()),
                    Point(
                        (x + radiusX).coerceAtMost(result.cols() - 1).toDouble(),
                        (y + radiusY).coerceAtMost(result.rows() - 1).toDouble(),
                    ),
                    Scalar(-1.0),
                    Imgproc.FILLED,
                )
            }
        } finally { result.release() }
    }

    override fun close() = templates.values.forEach(Mat::release)

    companion object {
        internal const val MATCH_THRESHOLD = 0.60
        internal val SCALES = listOf(0.95, 1.00, 1.05, 1.10, 1.15)
        private val ROI = Rect(192, 144, 896, 396)
        private const val MAX_LOCAL_CANDIDATES_PER_SCALE = 8
        internal const val NMS_IOU_THRESHOLD = 0.30
        private const val MAX_BOXES = 4

        internal fun suppressOverlaps(candidates: List<BoxDetection>): List<BoxDetection> {
            val accepted = mutableListOf<BoxDetection>()
            candidates.sortedByDescending { it.score }.forEach { candidate ->
                if (accepted.size < MAX_BOXES && accepted.none { iou(it.rect, candidate.rect) >= NMS_IOU_THRESHOLD }) {
                    accepted += candidate
                }
            }
            return accepted.sortedBy { it.rect.x }
        }

        private fun iou(first: Rect, second: Rect): Double {
            val left = maxOf(first.x, second.x)
            val top = maxOf(first.y, second.y)
            val right = minOf(first.x + first.width, second.x + second.width)
            val bottom = minOf(first.y + first.height, second.y + second.height)
            val intersection = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
            if (intersection == 0) return 0.0
            val union = first.width * first.height + second.width * second.height - intersection
            return intersection.toDouble() / union.toDouble()
        }
    }
}
