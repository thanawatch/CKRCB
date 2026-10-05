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
    val bestScoreByGrade: Map<String, Double> = emptyMap(),
    val bestScaleByGrade: Map<String, Double> = emptyMap(),
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
    val bgr: Mat,
    val alphaMask: Mat,
)

class MysteryBoxClassifier(context: Context) : AutoCloseable {
    private val templateVariants = mutableListOf<BoxTemplateVariant>().apply {
        listOf("wood", "silver", "gold", "rainbow").forEach { grade ->
            runCatching {
                context.assets.open("templates/BOX_${grade.uppercase()}.png").use { input ->
                    val bitmap = requireNotNull(BitmapFactory.decodeStream(input))
                    val rgba = Mat()
                    val baseBgr = Mat()
                    val baseMask = Mat()
                    try {
                        Utils.bitmapToMat(bitmap, rgba)
                        Imgproc.cvtColor(rgba, baseBgr, Imgproc.COLOR_RGBA2BGR)
                        Core.extractChannel(rgba, baseMask, 3)
                        Imgproc.threshold(baseMask, baseMask, 1.0, 255.0, Imgproc.THRESH_BINARY)
                        check(Core.countNonZero(baseMask) > 0) {
                            "Mystery Box alpha mask is empty for grade=$grade"
                        }

                        SCALES.forEach { scale ->
                            val variantBgr: Mat
                            val variantMask: Mat
                            if (scale == MysteryBoxScanPolicy.FAST_SCALE) {
                                variantBgr = baseBgr.clone()
                                variantMask = baseMask.clone()
                            } else {
                                variantBgr = Mat()
                                variantMask = Mat()
                                val size = Size(baseBgr.cols() * scale, baseBgr.rows() * scale)
                                Imgproc.resize(baseBgr, variantBgr, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
                                Imgproc.resize(baseMask, variantMask, size, 0.0, 0.0, Imgproc.INTER_NEAREST)
                            }
                            add(BoxTemplateVariant(grade, scale, variantBgr, variantMask))
                        }
                    } finally {
                        baseMask.release()
                        baseBgr.release()
                        rgba.release()
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
        val cropRgba = frame.submat(ROI)
        val cropBgr = Mat()
        Imgproc.cvtColor(cropRgba, cropBgr, Imgproc.COLOR_RGBA2BGR)
        val totalStartedAt = System.nanoTime()
        return try {
            val candidates = mutableListOf<BoxDetection>()
            val bestScores = mutableMapOf<String, Double>()
            val bestScales = mutableMapOf<String, Double>()

            val fastStartedAt = System.nanoTime()
            collectVariants(cropBgr, fastPathVariants, candidates, bestScores, bestScales)
            val fastPathDurationMs = elapsedMs(fastStartedAt)
            val fastPathCandidateCount = candidates.size
            val fastPathDetections = suppressOverlaps(candidates)
            val useFallback = MysteryBoxScanPolicy.shouldRunFallback(fastPathDetections)

            var fallbackDurationMs = 0.0
            val detections = if (useFallback) {
                val fallbackStartedAt = System.nanoTime()
                collectVariants(cropBgr, fallbackVariants, candidates, bestScores, bestScales)
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
                bestScoreByGrade = bestScores.toMap(),
                bestScaleByGrade = bestScales.toMap(),
            )
        } finally {
            cropBgr.release()
            cropRgba.release()
        }
    }

    private fun collectVariants(
        cropBgr: Mat,
        variants: List<BoxTemplateVariant>,
        output: MutableList<BoxDetection>,
        bestScores: MutableMap<String, Double>,
        bestScales: MutableMap<String, Double>,
    ) {
        variants.forEach { variant ->
            if (variant.bgr.cols() <= cropBgr.cols() && variant.bgr.rows() <= cropBgr.rows()) {
                val bestScore = collectLocalCandidates(
                    cropBgr,
                    variant.bgr,
                    variant.alphaMask,
                    variant.grade,
                    output,
                )
                if (bestScore > (bestScores[variant.grade] ?: Double.NEGATIVE_INFINITY)) {
                    bestScores[variant.grade] = bestScore
                    bestScales[variant.grade] = variant.scale
                }
            }
        }
    }

    private fun elapsedMs(startedAtNanos: Long): Double =
        (System.nanoTime() - startedAtNanos) / 1_000_000.0

    private fun collectLocalCandidates(
        cropBgr: Mat,
        templateBgr: Mat,
        alphaMask: Mat,
        grade: String,
        output: MutableList<BoxDetection>,
    ): Double {
        val result = Mat()
        var bestScore = Double.NEGATIVE_INFINITY
        try {
            Imgproc.matchTemplate(
                cropBgr,
                templateBgr,
                result,
                Imgproc.TM_SQDIFF_NORMED,
                alphaMask,
            )
            repeat(MAX_LOCAL_CANDIDATES_PER_SCALE) {
                val minimum = Core.minMaxLoc(result)
                if (!minimum.minVal.isFinite()) return bestScore
                val score = 1.0 - minimum.minVal.coerceIn(0.0, 1.0)
                bestScore = maxOf(bestScore, score)
                if (score < MATCH_THRESHOLD) return bestScore

                val x = minimum.minLoc.x.toInt()
                val y = minimum.minLoc.y.toInt()
                output += BoxDetection(
                    grade,
                    score,
                    Rect(ROI.x + x, ROI.y + y, templateBgr.cols(), templateBgr.rows()),
                )
                val radiusX = (templateBgr.cols() / 2).coerceAtLeast(1)
                val radiusY = (templateBgr.rows() / 2).coerceAtLeast(1)
                Imgproc.rectangle(
                    result,
                    Point((x - radiusX).coerceAtLeast(0).toDouble(), (y - radiusY).coerceAtLeast(0).toDouble()),
                    Point(
                        (x + radiusX).coerceAtMost(result.cols() - 1).toDouble(),
                        (y + radiusY).coerceAtMost(result.rows() - 1).toDouble(),
                    ),
                    Scalar(1.0),
                    Imgproc.FILLED,
                )
            }
            return bestScore
        } finally { result.release() }
    }

    override fun close() = templateVariants.forEach {
        it.alphaMask.release()
        it.bgr.release()
    }

    companion object {
        internal const val MATCH_THRESHOLD = 0.60
        internal val SCALES = listOf(0.95, 1.00, 1.05, 1.10, 1.15)
        private val ROI = Rect(192, 144, 896, 396)
        private const val MAX_LOCAL_CANDIDATES_PER_SCALE = 8
        internal const val NMS_IOU_THRESHOLD = 0.30
        internal const val NMS_SMALLER_OVERLAP_THRESHOLD = 0.30
        private const val MAX_BOXES = 4

        internal fun suppressOverlaps(candidates: List<BoxDetection>): List<BoxDetection> {
            val accepted = mutableListOf<BoxDetection>()
            candidates.sortedByDescending { it.score }.forEach { candidate ->
                if (accepted.size < MAX_BOXES &&
                    accepted.none { samePhysicalBox(it.rect, candidate.rect) }
                ) {
                    accepted += candidate
                }
            }
            return accepted.sortedBy { it.rect.x }
        }

        internal fun samePhysicalBox(first: Rect, second: Rect): Boolean =
            iou(first, second) >= NMS_IOU_THRESHOLD ||
                overlapOverSmallerArea(first, second) >= NMS_SMALLER_OVERLAP_THRESHOLD

        private fun overlapOverSmallerArea(first: Rect, second: Rect): Double {
            val intersection = intersectionArea(first, second)
            if (intersection == 0) return 0.0
            val smallerArea = minOf(first.width * first.height, second.width * second.height)
            if (smallerArea <= 0) return 0.0
            return intersection.toDouble() / smallerArea.toDouble()
        }

        private fun iou(first: Rect, second: Rect): Double {
            val intersection = intersectionArea(first, second)
            if (intersection == 0) return 0.0
            val union = first.width * first.height + second.width * second.height - intersection
            return intersection.toDouble() / union.toDouble()
        }

        private fun intersectionArea(first: Rect, second: Rect): Int {
            val left = maxOf(first.x, second.x)
            val top = maxOf(first.y, second.y)
            val right = minOf(first.x + first.width, second.x + second.width)
            val bottom = minOf(first.y + first.height, second.y + second.height)
            return (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
        }
    }
}
