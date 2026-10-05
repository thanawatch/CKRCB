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
data class BoxScanResult(
    val candidateCount: Int,
    val detections: List<BoxDetection>,
    val fastPathCandidateCount: Int = 0,
    val fastPathAcceptedCount: Int = 0,
    val usedFallback: Boolean = false,
    val fastPathDurationMs: Double = 0.0,
    val fallbackDurationMs: Double = 0.0,
    val totalDurationMs: Double = 0.0,
)
internal object MysteryBoxPhasePolicy {
    fun shouldClassify(phase: Int): Boolean = phase == 1
}
internal object MysteryBoxScanPolicy {
    const val FAST_SCALE = 1.0
    fun shouldRunFallback(fastPathDetections: List<BoxDetection>): Boolean =
        fastPathDetections.isEmpty()
}

private data class BoxTemplateVariant(
    val grade: String,
    val scale: Double,
    val mat: Mat,
)

class MysteryBoxClassifier(context: Context) : AutoCloseable {
    private val templateVariants = mutableListOf<BoxTemplateVariant>().apply {
        listOf("wood", "silver", "gold", "rainbow").forEach { grade ->
            runCatching {
                context.assets.open("templates/BOX_${grade.uppercase()}.png").use { input ->
                    val bitmap = requireNotNull(BitmapFactory.decodeStream(input))
                    val base = Mat()
                    try {
                        Utils.bitmapToMat(bitmap, base)
                        SCALES.forEach { scale ->
                            val variant = if (scale == MysteryBoxScanPolicy.FAST_SCALE) {
                                base.clone()
                            } else {
                                Mat().also { scaled ->
                                    Imgproc.resize(
                                        base,
                                        scaled,
                                        Size(base.cols() * scale, base.rows() * scale),
                                    )
                                }
                            }
                            add(BoxTemplateVariant(grade, scale, variant))
                        }
                    } finally {
                        base.release()
                        bitmap.recycle()
                    }
                }
            }
        }
    }
    private val fastPathVariants =
        templateVariants.filter { it.scale == MysteryBoxScanPolicy.FAST_SCALE }
    private val fallbackVariants =
        templateVariants.filter { it.scale != MysteryBoxScanPolicy.FAST_SCALE }

    fun classifyAll(frame: Mat): List<BoxDetection> = scan(frame).detections

    fun scan(frame: Mat): BoxScanResult {
        if (templateVariants.isEmpty() ||
            frame.cols() < ROI.x + ROI.width ||
            frame.rows() < ROI.y + ROI.height
        ) {
            return BoxScanResult(0, emptyList())
        }
        val crop = frame.submat(ROI)
        val totalStartedAt = System.nanoTime()
        return try {
            val candidates = mutableListOf<BoxDetection>()

            val fastStartedAt = System.nanoTime()
            collectVariants(crop, fastPathVariants, candidates)
            val fastPathDurationMs = elapsedMs(fastStartedAt)
            val fastPathCandidateCount = candidates.size
            val fastPathDetections = suppressOverlaps(candidates)
            val useFallback = MysteryBoxScanPolicy.shouldRunFallback(fastPathDetections)

            var fallbackDurationMs = 0.0
            val detections = if (useFallback) {
                val fallbackStartedAt = System.nanoTime()
                collectVariants(crop, fallbackVariants, candidates)
                fallbackDurationMs = elapsedMs(fallbackStartedAt)
                suppressOverlaps(candidates)
            } else {
                fastPathDetections
            }

            BoxScanResult(
                candidateCount = candidates.size,
                detections = detections,
                fastPathCandidateCount = fastPathCandidateCount,
                fastPathAcceptedCount = fastPathDetections.size,
                usedFallback = useFallback,
                fastPathDurationMs = fastPathDurationMs,
                fallbackDurationMs = fallbackDurationMs,
                totalDurationMs = elapsedMs(totalStartedAt),
            )
        } finally { crop.release() }
    }

    private fun collectVariants(
        crop: Mat,
        variants: List<BoxTemplateVariant>,
        output: MutableList<BoxDetection>,
    ) {
        variants.forEach { variant ->
            if (variant.mat.cols() <= crop.cols() && variant.mat.rows() <= crop.rows()) {
                collectLocalCandidates(crop, variant.mat, variant.grade, output)
            }
        }
    }

    private fun elapsedMs(startedAtNanos: Long): Double =
        (System.nanoTime() - startedAtNanos) / 1_000_000.0

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

    override fun close() = templateVariants.forEach { it.mat.release() }

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
