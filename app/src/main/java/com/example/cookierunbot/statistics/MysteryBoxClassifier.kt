package com.example.cookierunbot.statistics

import android.content.Context
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
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
    val usedNearFallback: Boolean = false,
    val usedFarProbe: Boolean = false,
    val usedExtremeFallback: Boolean = false,
    val fastPathDurationMs: Double = 0.0,
    val fallbackDurationMs: Double = 0.0,
    val totalDurationMs: Double = 0.0,
    val bestScoreByGrade: Map<String, Double> = emptyMap(),
    val bestScaleByGrade: Map<String, Double> = emptyMap(),
    val rawCandidates: List<BoxDetection> = emptyList(),
)
internal object MysteryBoxPhasePolicy {
    fun shouldClassify(phase: Int): Boolean = phase == 1
}
internal object MysteryBoxScanPolicy {
    const val FAST_SCALE = 1.0
    internal val NEAR_FALLBACK_SCALES = setOf(0.95, 1.05)
    internal const val FAR_PROBE_SCALE = 1.10
    internal const val EXTREME_FALLBACK_SCALE = 1.15
    private const val MAX_VISIBLE_BOXES = 4
    private const val EXTREME_WEAK_SCORE = 0.75
    // If every detection we already have is this confident, the match is clean and
    // real — scanning 15 more scale/grade variants only adds noise, which is how a
    // single real box was ending up reported as 2-4 boxes (or the wrong grade).
    private const val HIGH_CONFIDENCE_SCORE = 0.85

    private fun allHighConfidence(detections: List<BoxDetection>): Boolean =
        detections.isNotEmpty() && detections.all { it.score >= HIGH_CONFIDENCE_SCORE }

    fun shouldRunNearFallback(fastPathDetections: List<BoxDetection>): Boolean {
        if (allHighConfidence(fastPathDetections)) return false
        if (fastPathDetections.size <= 2) return true
        if (fastPathDetections.size >= MAX_VISIBLE_BOXES) return false
        return fastPathDetections.minOfOrNull { it.score }?.let { it < EXTREME_WEAK_SCORE } ?: true
    }

    fun shouldRunFarProbe(nearDetections: List<BoxDetection>): Boolean {
        if (allHighConfidence(nearDetections)) return false
        if (nearDetections.size <= 2) return true
        if (nearDetections.size >= MAX_VISIBLE_BOXES) return false
        return nearDetections.minOfOrNull { it.score }?.let { it < EXTREME_WEAK_SCORE } ?: true
    }

    fun shouldRunExtremeFallback(
        beforeFarProbeCount: Int,
        farProbeDetections: List<BoxDetection>,
    ): Boolean {
        if (allHighConfidence(farProbeDetections)) return false
        if (farProbeDetections.size >= MAX_VISIBLE_BOXES) return false
        val weakest = farProbeDetections.minOfOrNull { it.score } ?: Double.NEGATIVE_INFINITY
        return farProbeDetections.size <= 2 ||
            farProbeDetections.size > beforeFarProbeCount ||
            weakest < EXTREME_WEAK_SCORE
    }
}

private data class BoxTemplateVariant(
    val grade: String,
    val scale: Double,
    val bgr: Mat,
    val alphaMask: Mat,
)

private data class BoxBaseTemplate(val grade: String, val bgr: Mat, val alphaMask: Mat)

// Fixed canvas every grade's raw asset gets normalized to at load time, regardless of
// whatever size the source PNG actually is. This is what lets buildDiscriminativeMask
// (and every size-sensitive check downstream) assume all 4 grades line up pixel-for-
// pixel without requiring the asset files themselves to be pre-cropped to match.
private val TEMPLATE_TARGET_SIZE = Size(170.0, 150.0)
private const val CORE_ALPHA_THRESHOLD = 199.0 // keeps alpha >= 200; excludes soft glow falloff

// Centers a fixed-size (TEMPLATE_TARGET_SIZE) crop on the "solid" part of the box --
// near-opaque pixels (alpha >= 200), ignoring soft glow/sparkle halo that differs in
// extent per grade. Works on any input size/aspect ratio; always returns the same
// output size. See the earlier discussion: a plain "crop to any non-transparent pixel"
// bounding box still came out a different size per grade because higher-rarity boxes
// have more glow bleeding past the box edge -- centroid-of-the-core does not.
private fun normalizeToFixedSize(rgba: Mat, targetSize: Size): Mat {
    val targetW = targetSize.width.toInt()
    val targetH = targetSize.height.toInt()

    val rawAlpha = Mat()
    val coreMask = Mat()
    val padded = Mat()
    try {
        Core.extractChannel(rgba, rawAlpha, 3)
        Imgproc.threshold(rawAlpha, coreMask, CORE_ALPHA_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)

        var moments = Imgproc.moments(coreMask, true)
        if (moments.m00 <= 0.0) {
            // No near-opaque pixels at all (unexpected) -- fall back to any non-transparent pixel.
            Imgproc.threshold(rawAlpha, coreMask, 0.0, 255.0, Imgproc.THRESH_BINARY)
            moments = Imgproc.moments(coreMask, true)
        }
        val cx = if (moments.m00 > 0.0) moments.m10 / moments.m00 else rgba.cols() / 2.0
        val cy = if (moments.m00 > 0.0) moments.m01 / moments.m00 else rgba.rows() / 2.0

        // Pad generously so the fixed window always fits, regardless of how close the
        // core is to an edge in any particular source image.
        val pad = maxOf(targetW, targetH)
        Core.copyMakeBorder(
            rgba, padded, pad, pad, pad, pad, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0, 0.0),
        )

        val cxP = cx + pad
        val cyP = cy + pad
        var x0 = Math.round(cxP - targetW / 2.0).toInt()
        var y0 = Math.round(cyP - targetH / 2.0).toInt()
        x0 = x0.coerceIn(0, padded.cols() - targetW)
        y0 = y0.coerceIn(0, padded.rows() - targetH)

        return padded.submat(Rect(x0, y0, targetW, targetH)).clone()
    } finally {
        rawAlpha.release()
        coreMask.release()
        padded.release()
    }
}

// Pixels whose color varies by less than this across all 4 grades are treated as
// "shared geometry" (box outline, corner studs) rather than grade-distinctive content.
// 15.0 is the measured cross-grade std-dev at the outline/stud pixels in the real
// templates (median std-dev on genuinely distinctive face pixels was ~40).
private const val DISCRIMINATIVE_STD_THRESHOLD = 15.0

// Pixels that are opaque in every grade AND vary across grades (the face color/texture),
// excluding shared hardware like the outline and corner studs. Masking matchTemplate
// with this instead of the raw alpha mask stops every grade from getting a "free" score
// boost from matching the ~7% of pixels that are identical no matter which grade it is.
// Requires all templates to be the same size/alignment (true once normalized).
private fun buildDiscriminativeMask(bases: List<BoxBaseTemplate>): Mat? {
    if (bases.size < 2) return null
    val size = bases[0].bgr.size()
    if (bases.any { it.bgr.size() != size }) return null // not aligned; skip the refinement

    val commonOpaque = bases[0].alphaMask.clone()
    for (i in 1 until bases.size) {
        Core.bitwise_and(commonOpaque, bases[i].alphaMask, commonOpaque)
    }

    val floats = bases.map { Mat().also { f -> it.bgr.convertTo(f, CvType.CV_32FC3) } }
    val n = floats.size.toDouble()
    val sum = Mat.zeros(size, CvType.CV_32FC3)
    floats.forEach { Core.add(sum, it, sum) }
    val mean = Mat()
    Core.divide(sum, Scalar(n, n, n), mean)

    val sumSq = Mat.zeros(size, CvType.CV_32FC3)
    val diff = Mat()
    val sq = Mat()
    floats.forEach { f ->
        Core.subtract(f, mean, diff)
        Core.multiply(diff, diff, sq)
        Core.add(sumSq, sq, sumSq)
    }
    val variance = Mat()
    Core.divide(sumSq, Scalar(n, n, n), variance)

    val channels = mutableListOf<Mat>()
    Core.split(variance, channels)
    val varSum = Mat.zeros(size, CvType.CV_32F)
    channels.forEach { Core.add(varSum, it, varSum) }
    val avgVariance = Mat()
    Core.divide(varSum, Scalar(3.0), avgVariance)
    val std = Mat()
    Core.sqrt(avgVariance, std)

    val discriminative = Mat()
    Core.compare(std, Scalar(DISCRIMINATIVE_STD_THRESHOLD), discriminative, Core.CMP_GE)

    val result = Mat()
    Core.bitwise_and(commonOpaque, discriminative, result)

    floats.forEach { it.release() }
    channels.forEach { it.release() }
    listOf(commonOpaque, sum, mean, sumSq, diff, sq, variance, varSum, avgVariance, std, discriminative)
        .forEach { it.release() }

    return result
}

class MysteryBoxClassifier(context: Context) : AutoCloseable {
    private val templateVariants = mutableListOf<BoxTemplateVariant>().apply {
        val bases = mutableListOf<BoxBaseTemplate>()
        listOf("wood", "silver", "gold", "rainbow").forEach { grade ->
            runCatching {
                context.assets.open("templates/BOX_${grade.uppercase()}.png").use { input ->
                    val bitmap = requireNotNull(BitmapFactory.decodeStream(input))
                    val rawRgba = Mat()
                    var rgba: Mat? = null
                    val baseBgr = Mat()
                    val baseMask = Mat()
                    try {
                        Utils.bitmapToMat(bitmap, rawRgba)
                        // Raw asset can be any size/aspect ratio -- normalize before anything
                        // else touches it, so every downstream step (scale variants, the
                        // cross-grade discriminative mask) can assume identical dimensions.
                        val normalized = normalizeToFixedSize(rawRgba, TEMPLATE_TARGET_SIZE)
                        rgba = normalized
                        Imgproc.cvtColor(normalized, baseBgr, Imgproc.COLOR_RGBA2BGR)
                        Core.extractChannel(normalized, baseMask, 3)
                        Imgproc.threshold(baseMask, baseMask, 1.0, 255.0, Imgproc.THRESH_BINARY)
                        check(Core.countNonZero(baseMask) > 0) {
                            "Mystery Box alpha mask is empty for grade=$grade"
                        }
                        bases.add(BoxBaseTemplate(grade, baseBgr.clone(), baseMask.clone()))
                    } finally {
                        rgba?.release()
                        baseMask.release()
                        baseBgr.release()
                        rawRgba.release()
                        bitmap.recycle()
                    }
                }
            }
        }

        // Shared across all grades; null (falls back to each grade's plain alpha mask)
        // if asset loading failed or the templates aren't uniformly sized/aligned.
        val discriminativeMask = buildDiscriminativeMask(bases)

        bases.forEach { base ->
            val effectiveMask = Mat()
            try {
                if (discriminativeMask != null) {
                    Core.bitwise_and(base.alphaMask, discriminativeMask, effectiveMask)
                } else {
                    base.alphaMask.copyTo(effectiveMask)
                }

                SCALES.forEach { scale ->
                    val variantBgr: Mat
                    val variantMask: Mat
                    if (scale == MysteryBoxScanPolicy.FAST_SCALE) {
                        variantBgr = base.bgr.clone()
                        variantMask = effectiveMask.clone()
                    } else {
                        variantBgr = Mat()
                        variantMask = Mat()
                        val size = Size(base.bgr.cols() * scale, base.bgr.rows() * scale)
                        Imgproc.resize(base.bgr, variantBgr, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
                        Imgproc.resize(effectiveMask, variantMask, size, 0.0, 0.0, Imgproc.INTER_NEAREST)
                    }
                    add(BoxTemplateVariant(base.grade, scale, variantBgr, variantMask))
                }
            } finally {
                effectiveMask.release()
            }
        }
        bases.forEach { it.alphaMask.release(); it.bgr.release() }
        discriminativeMask?.release()
    }
    private val fastPathVariants =
        templateVariants.filter { it.scale == MysteryBoxScanPolicy.FAST_SCALE }
    private val nearFallbackVariants =
        templateVariants.filter { it.scale in MysteryBoxScanPolicy.NEAR_FALLBACK_SCALES }
    private val farProbeVariants =
        templateVariants.filter { it.scale == MysteryBoxScanPolicy.FAR_PROBE_SCALE }
    private val extremeFallbackVariants =
        templateVariants.filter { it.scale == MysteryBoxScanPolicy.EXTREME_FALLBACK_SCALE }

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

            var fallbackDurationMs = 0.0
            var detections = fastPathDetections
            val useNearFallback = MysteryBoxScanPolicy.shouldRunNearFallback(detections)
            if (useNearFallback) {
                val startedAt = System.nanoTime()
                collectVariants(cropBgr, nearFallbackVariants, candidates, bestScores, bestScales)
                fallbackDurationMs += elapsedMs(startedAt)
                detections = suppressOverlaps(candidates)
            }

            val beforeFarProbeCount = detections.size
            val useFarProbe =
                useNearFallback && MysteryBoxScanPolicy.shouldRunFarProbe(detections)
            if (useFarProbe) {
                val startedAt = System.nanoTime()
                collectVariants(cropBgr, farProbeVariants, candidates, bestScores, bestScales)
                fallbackDurationMs += elapsedMs(startedAt)
                detections = suppressOverlaps(candidates)
            }

            val useExtremeFallback = useFarProbe &&
                MysteryBoxScanPolicy.shouldRunExtremeFallback(
                    beforeFarProbeCount,
                    detections,
                )
            if (useExtremeFallback) {
                val startedAt = System.nanoTime()
                collectVariants(cropBgr, extremeFallbackVariants, candidates, bestScores, bestScales)
                fallbackDurationMs += elapsedMs(startedAt)
                detections = suppressOverlaps(candidates)
            }

            BoxScanResult(
                candidateCount = candidates.size,
                detections = detections,
                fastPathCandidateCount = fastPathCandidateCount,
                fastPathAcceptedCount = fastPathDetections.size,
                usedFallback = useNearFallback || useFarProbe || useExtremeFallback,
                usedNearFallback = useNearFallback,
                usedFarProbe = useFarProbe,
                usedExtremeFallback = useExtremeFallback,
                fastPathDurationMs = fastPathDurationMs,
                fallbackDurationMs = fallbackDurationMs,
                totalDurationMs = elapsedMs(totalStartedAt),
                bestScoreByGrade = bestScores.toMap(),
                bestScaleByGrade = bestScales.toMap(),
                rawCandidates = candidates.toList(),
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
                // Suppress almost one whole box around the winning peak. The masked matcher
                // can produce secondary peaks from the same physical box well beyond half a
                // template width, especially on cloud-rendered frames.
                val radiusX =
                    (templateBgr.cols() * LOCAL_PEAK_SUPPRESSION_X_FRACTION).toInt().coerceAtLeast(1)
                val radiusY =
                    (templateBgr.rows() * LOCAL_PEAK_SUPPRESSION_Y_FRACTION).toInt().coerceAtLeast(1)
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
        // Only scale 1.0 is scanned now -- the near/0.95-1.05/far-1.10/extreme-1.15
        // fallback stages still exist in MysteryBoxScanPolicy and still get *called*,
        // but their variant lists come out empty (nothing in templateVariants has those
        // scales anymore), so collectVariants() on them is a harmless no-op. Add scales
        // back here if real on-device scale variance ever shows up in the logs.
        internal val SCALES = listOf(1.00)
        private val ROI = Rect(192, 144, 896, 396)
        private const val MAX_LOCAL_CANDIDATES_PER_SCALE = 8
        internal const val NMS_IOU_THRESHOLD = 0.30
        internal const val NMS_SMALLER_OVERLAP_THRESHOLD = 0.30
        internal const val POSITION_CLUSTER_X_FRACTION = 1.00
        internal const val POSITION_CLUSTER_Y_FRACTION = 0.80
        private const val LOCAL_PEAK_SUPPRESSION_X_FRACTION = 1.00
        private const val LOCAL_PEAK_SUPPRESSION_Y_FRACTION = 0.80
        private const val BRIDGE_SUPPORT_MIN_SCORE = 0.75
        private const val BRIDGE_SUPPORT_MIN_RELATIVE_SCORE = 0.85
        private const val MAX_BOXES = 4

        internal fun suppressOverlaps(candidates: List<BoxDetection>): List<BoxDetection> {
            val nonBridgeCandidates = candidates.filterNot { candidate ->
                isBridgeCandidate(candidate, candidates)
            }
            val accepted = mutableListOf<BoxDetection>()
            nonBridgeCandidates.sortedByDescending { it.score }.forEach { candidate ->
                if (accepted.size < MAX_BOXES &&
                    accepted.none { samePhysicalBox(it.rect, candidate.rect) }
                ) {
                    accepted += candidate
                }
            }
            return accepted.sortedBy { it.rect.x }
        }

        /**
         * A shifted peak can land between two real boxes and overlap/cluster with both.
         * Greedy score-first NMS would let a high-scoring bridge suppress both real boxes.
         * Reject that bridge only when it has strong support on both horizontal sides and
         * those two supporting candidates are themselves clearly separate boxes.
         */
        internal fun isBridgeCandidate(
            candidate: BoxDetection,
            candidates: List<BoxDetection>,
        ): Boolean {
            val candidateCenterX = centerX(candidate.rect)
            val minimumSupportScore = maxOf(
                BRIDGE_SUPPORT_MIN_SCORE,
                candidate.score * BRIDGE_SUPPORT_MIN_RELATIVE_SCORE,
            )
            val leftSupport = candidates.asSequence()
                .filter { it !== candidate }
                .filter { it.score >= minimumSupportScore }
                .filter { centerX(it.rect) < candidateCenterX }
                .filter { samePhysicalBox(it.rect, candidate.rect) }
                .maxByOrNull { it.score }
            val rightSupport = candidates.asSequence()
                .filter { it !== candidate }
                .filter { it.score >= minimumSupportScore }
                .filter { centerX(it.rect) > candidateCenterX }
                .filter { samePhysicalBox(it.rect, candidate.rect) }
                .maxByOrNull { it.score }

            return leftSupport != null &&
                rightSupport != null &&
                !samePhysicalBox(leftSupport.rect, rightSupport.rect)
        }

        private fun centerX(rect: Rect): Double = rect.x + rect.width / 2.0

        internal fun samePhysicalBox(first: Rect, second: Rect): Boolean =
            iou(first, second) >= NMS_IOU_THRESHOLD ||
                overlapOverSmallerArea(first, second) >= NMS_SMALLER_OVERLAP_THRESHOLD ||
                centersBelongToSamePositionCluster(first, second)

        /**
         * Position-aware de-duplication for shifted peaks from one physical box.
         * Thresholds are relative to the smaller candidate so the rule stays stable
         * across the existing 0.95..1.15 scale variants. Two distinct non-overlapping
         * boxes remain separate because their centers are at least roughly one box
         * width apart.
         */
        internal fun centersBelongToSamePositionCluster(first: Rect, second: Rect): Boolean {
            val firstCenterX = first.x + first.width / 2.0
            val firstCenterY = first.y + first.height / 2.0
            val secondCenterX = second.x + second.width / 2.0
            val secondCenterY = second.y + second.height / 2.0
            val maxCenterDx =
                minOf(first.width, second.width) * POSITION_CLUSTER_X_FRACTION
            val maxCenterDy =
                minOf(first.height, second.height) * POSITION_CLUSTER_Y_FRACTION
            return kotlin.math.abs(firstCenterX - secondCenterX) < maxCenterDx &&
                kotlin.math.abs(firstCenterY - secondCenterY) < maxCenterDy
        }

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
