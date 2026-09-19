package com.example.cookierunbot.detection

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.automation.BotStage
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.EnumMap
import java.util.Locale
import kotlin.math.roundToInt

class StateDetector(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val templates = EnumMap<BotStage, List<Mat>>(BotStage::class.java)
    private val auxiliaryTemplates = mutableMapOf<String, Mat>()
    private val reusableScreenGray = Mat()
    private val reusableStageMatch = Mat()

    init {
        for (stage in DETECTABLE_STAGES) {
            templates[stage] = loadTemplateVariants(context, stage)
        }
    }

    fun detectMainMenu(normalizedRgba: Mat): DetectionResult =
        detect(BotStage.MAINMENU, normalizedRgba)

    fun detectFirst(stages: List<BotStage>, normalizedRgba: Mat): DetectionResult? {
        validateFrame(normalizedRgba)
        Imgproc.cvtColor(normalizedRgba, reusableScreenGray, Imgproc.COLOR_RGBA2GRAY)
        for (stage in stages) {
            val result = detectGray(stage, reusableScreenGray)
            if (result.matched) return result
        }
        return null
    }

    /** Scores diagnostic candidates using one grayscale conversion without changing matching. */
    fun scoreStages(stages: List<BotStage>, normalizedRgba: Mat): List<DetectionResult> {
        validateFrame(normalizedRgba)
        Imgproc.cvtColor(normalizedRgba, reusableScreenGray, Imgproc.COLOR_RGBA2GRAY)
        return stages.map { stage -> detectGray(stage, reusableScreenGray) }
    }

    fun detectTemplate(
        normalizedRgba: Mat,
        assetName: String,
        roiRect: Rect,
    ): TemplateMatch {
        val roiRgba = normalizedRgba.submat(roiRect)
        val roiGray = Mat()
        try {
            Imgproc.cvtColor(roiRgba, roiGray, Imgproc.COLOR_RGBA2GRAY)
            val template = auxiliaryTemplates.getOrPut(assetName) {
                loadGrayTemplate(appContext, "templates/$assetName")
            }
            if (roiGray.cols() < template.cols() || roiGray.rows() < template.rows()) {
                return TemplateMatch(-1.0, false, -1, -1, 0, 0)
            }
            val result = Mat()
            return try {
                Imgproc.matchTemplate(roiGray, template, result, Imgproc.TM_CCOEFF_NORMED)
                val match = Core.minMaxLoc(result)
                TemplateMatch(
                    match.maxVal,
                    match.maxVal >= MATCH_THRESHOLD,
                    match.maxLoc.x.toInt() + roiRect.x,
                    match.maxLoc.y.toInt() + roiRect.y,
                    template.cols(),
                    template.rows(),
                )
            } finally {
                result.release()
            }
        } finally {
            roiGray.release()
            roiRgba.release()
        }
    }

    fun detectAntiBotOddCards(normalizedRgba: Mat): List<Int> {
        val cardPoints = listOf(
            346 to 179, 542 to 179, 738 to 179,
            346 to 435, 542 to 435, 738 to 435,
        )
        val gray = Mat()
        Imgproc.cvtColor(normalizedRgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val cards = cardPoints.map { (x, y) -> gray.submat(Rect(x, y, 183, 241)).clone() }
        try {
            val averages = DoubleArray(cards.size)
            for (i in cards.indices) {
                var total = 0.0
                for (j in cards.indices) {
                    if (i == j) continue
                    val result = Mat()
                    try {
                        Imgproc.matchTemplate(cards[i], cards[j], result, Imgproc.TM_CCOEFF_NORMED)
                        total += Core.minMaxLoc(result).maxVal
                    } finally {
                        result.release()
                    }
                }
                averages[i] = total / (cards.size - 1)
            }
            return averages.indices.sortedBy { averages[it] }.take(2)
        } finally {
            cards.forEach(Mat::release)
            gray.release()
        }
    }

    fun detect(stage: BotStage, normalizedRgba: Mat): DetectionResult {
        require(stage in DETECTABLE_STAGES) { "${stage.name} has no configured stage template" }
        validateFrame(normalizedRgba)

        Imgproc.cvtColor(normalizedRgba, reusableScreenGray, Imgproc.COLOR_RGBA2GRAY)
        return detectGray(stage, reusableScreenGray)
    }

    private fun detectGray(stage: BotStage, screenGray: Mat): DetectionResult {
        require(stage in DETECTABLE_STAGES) { "${stage.name} has no configured stage template" }
        val roiRect = ROI_BY_STAGE.getValue(stage)
        val roiGray = screenGray.submat(roiRect)
        try {
            var bestScore = -1.0
            var bestX = -1
            var bestY = -1
            var bestWidth = 0
            var bestHeight = 0

            val templatesForStage = templates.getValue(stage)
            val template = templatesForStage.firstOrNull()
            if (stage == BotStage.DAILY_NEW && template != null) {
                val templateWidth = template.cols()
                val templateHeight = template.rows()
                val roiWidth = roiGray.cols()
                val roiHeight = roiGray.rows()
                if (templateWidth > roiWidth || templateHeight > roiHeight) {
                    Log.i(
                        CookieRunBotApplication.TAG,
                        "[DAILY_NEW_DIAG] skipped=template_larger_than_roi " +
                            "templateSize=${templateWidth}x${templateHeight} " +
                            "roiSize=${roiWidth}x${roiHeight} " +
                            "roi=(${roiRect.x},${roiRect.y},${roiRect.x + roiRect.width},${roiRect.y + roiRect.height})"
                    )
                    return DetectionResult(
                        state = stage,
                        score = -1.0,
                        matched = false,
                        x = -1,
                        y = -1,
                        width = 0,
                        height = 0,
                    )
                }
            }
            if (stage == BotStage.PARTY_RUN_SEASON_REWARDS && template != null) {
                val templateWidth = template.cols()
                val templateHeight = template.rows()
                val roiWidth = roiGray.cols()
                val roiHeight = roiGray.rows()
                if (templateWidth > roiWidth || templateHeight > roiHeight) {
                    Log.i(
                        CookieRunBotApplication.TAG,
                        "[PARTY_RUN_SEASON_REWARDS_DIAG] skipped=template_larger_than_roi " +
                            "templateSize=${templateWidth}x${templateHeight} " +
                            "roiSize=${roiWidth}x${roiHeight} " +
                            "roi=(${roiRect.x},${roiRect.y},${roiRect.x + roiRect.width},${roiRect.y + roiRect.height})"
                    )
                    return DetectionResult(
                        state = stage,
                        score = -1.0,
                        matched = false,
                        x = -1,
                        y = -1,
                        width = 0,
                        height = 0,
                    )
                }
            }

            for (template in templatesForStage) {
                if (roiGray.cols() < template.cols() || roiGray.rows() < template.rows()) {
                    continue
                }
                Imgproc.matchTemplate(
                    roiGray,
                    template,
                    reusableStageMatch,
                    Imgproc.TM_CCOEFF_NORMED,
                )
                val match = Core.minMaxLoc(reusableStageMatch)
                if (match.maxVal > bestScore) {
                    bestScore = match.maxVal
                    bestX = match.maxLoc.x.toInt() + roiRect.x
                    bestY = match.maxLoc.y.toInt() + roiRect.y
                    bestWidth = template.cols()
                    bestHeight = template.rows()
                }
            }

            val matched = bestScore >= MATCH_THRESHOLD
            if (stage == BotStage.DAILY_NEW) {
                Log.i(
                    CookieRunBotApplication.TAG,
                    "[DAILY_NEW_DIAG] frameSequence=0 " +
                        "score=${String.format(Locale.US, "%.4f", bestScore)} " +
                        "matched=$matched " +
                        "roi=(${roiRect.x},${roiRect.y},${roiRect.x + roiRect.width},${roiRect.y + roiRect.height}) " +
                        "templateSize=${bestWidth}x${bestHeight} " +
                        "roiSize=${roiGray.cols()}x${roiGray.rows()}"
                )
            }
            if (stage == BotStage.PARTY_RUN_SEASON_REWARDS) {
                Log.i(
                    CookieRunBotApplication.TAG,
                    "[PARTY_RUN_SEASON_REWARDS_DIAG] frameSequence=0 " +
                        "score=${String.format(Locale.US, "%.4f", bestScore)} " +
                        "matched=$matched " +
                        "roi=(${roiRect.x},${roiRect.y},${roiRect.x + roiRect.width},${roiRect.y + roiRect.height}) " +
                        "templateSize=${bestWidth}x${bestHeight} " +
                        "roiSize=${roiGray.cols()}x${roiGray.rows()}"
                )
            }

            return DetectionResult(
                state = stage,
                score = bestScore,
                matched = matched,
                x = bestX,
                y = bestY,
                width = bestWidth,
                height = bestHeight,
            )
        } finally {
            roiGray.release()
        }
    }

    private fun validateFrame(normalizedRgba: Mat) {
        require(
            normalizedRgba.cols() == LOGICAL_WIDTH && normalizedRgba.rows() == LOGICAL_HEIGHT,
        ) {
            "Expected ${LOGICAL_WIDTH}x${LOGICAL_HEIGHT} normalized frame, " +
                "got ${normalizedRgba.cols()}x${normalizedRgba.rows()}"
        }
    }

    override fun close() {
        templates.values.forEach { variants -> variants.forEach(Mat::release) }
        templates.clear()
        auxiliaryTemplates.values.forEach(Mat::release)
        auxiliaryTemplates.clear()
        reusableScreenGray.release()
        reusableStageMatch.release()
    }

    private fun loadTemplateVariants(context: Context, stage: BotStage): List<Mat> {
        val variants = mutableListOf<Mat>()
        for (path in TEMPLATE_PATHS_BY_STAGE.getValue(stage)) {
            val bitmap = context.assets.open(path).use(BitmapFactory::decodeStream)
                ?: error("Unable to decode $path")
            val templateRgba = Mat()
            val templateGray = Mat()
            try {
                Utils.bitmapToMat(bitmap, templateRgba)
                Imgproc.cvtColor(templateRgba, templateGray, Imgproc.COLOR_RGBA2GRAY)
                for (scale in SCALE_BY_STAGE.getValue(stage)) {
                    variants += if (scale == 1.0) {
                        templateGray.clone()
                    } else {
                        Mat().also { scaled ->
                            val width = (templateGray.cols() * scale).roundToInt().coerceAtLeast(1)
                            val height = (templateGray.rows() * scale).roundToInt().coerceAtLeast(1)
                            Imgproc.resize(
                                templateGray,
                                scaled,
                                Size(width.toDouble(), height.toDouble()),
                                0.0,
                                0.0,
                                Imgproc.INTER_LINEAR,
                            )
                        }
                    }
                }
            } finally {
                templateGray.release()
                templateRgba.release()
                bitmap.recycle()
            }
        }
        return variants
    }

    private fun loadGrayTemplate(context: Context, path: String): Mat {
        val bitmap = context.assets.open(path).use(BitmapFactory::decodeStream)
            ?: error("Unable to decode $path")
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return gray.clone()
        } finally {
            gray.release()
            rgba.release()
            bitmap.recycle()
        }
    }

    companion object {
        private const val LOGICAL_WIDTH = 1280
        private const val LOGICAL_HEIGHT = 720
        private const val MATCH_THRESHOLD = 0.8
        private val DETECTABLE_STAGES = setOf(
            BotStage.MAINMENU,
            BotStage.PURCHASE_ITEM,
            BotStage.GAME_START,
            BotStage.GAME_RELAY,
            BotStage.GAME_COMPLETE,
            BotStage.MYSTERY_BOX,
            BotStage.CONGRATULATIONS,
            BotStage.LEVEL_UP,
            BotStage.RELIC_COMPLETE,
            BotStage.RELIC_CLAIM,
            BotStage.DAILY_CHECKIN,
            BotStage.DAILY_CHECKIN_BOOST_SET,
            BotStage.DAILY_TREASURE,
            BotStage.DAILY_NEW,
            BotStage.ENTER_LEAGUE,
            BotStage.LEAGUE_RESULTS,
            BotStage.ANTI_BOT,
            BotStage.CONNECTION_LOST,
            BotStage.INACTIVE,
            BotStage.PREVIOUS_RANK_RESULTS,
            BotStage.TOO_MANY_TREASURES,
            BotStage.OVERTAKE_BREAK_SCORE,
            BotStage.PARTY_RUN,
            BotStage.PARTY_RUN_SEASON_REWARDS,
            BotStage.GAME_SETTINGS,
        )
        private val ROI_BY_STAGE = mapOf(
            BotStage.MAINMENU to Rect(154, 150, 344 - 154, 225 - 150),
            BotStage.PURCHASE_ITEM to Rect(474, 84, 602 - 474, 138 - 84),
            BotStage.GAME_START to Rect(500, 200, 800 - 500, 500 - 200),
            BotStage.GAME_RELAY to Rect(500, 200, 800 - 500, 500 - 200),
            BotStage.GAME_COMPLETE to Rect(523, 27, 757 - 523, 116 - 27),
            BotStage.MYSTERY_BOX to Rect(503, 46, 782 - 503, 107 - 46),
            BotStage.CONGRATULATIONS to Rect(372, 85, 909 - 372, 200 - 85),
            BotStage.LEVEL_UP to Rect(481, 34, 798 - 481, 130 - 34),
            BotStage.RELIC_COMPLETE to Rect(480, 63, 578 - 480, 106 - 63),
            BotStage.RELIC_CLAIM to Rect(412, 72, 865 - 412, 191 - 72),
            BotStage.DAILY_CHECKIN to Rect(491, 21, 792 - 491, 75 - 21),
            BotStage.DAILY_CHECKIN_BOOST_SET to Rect(523, 88, 754 - 523, 182 - 88),
            BotStage.DAILY_TREASURE to Rect(452, 117, 824 - 452, 161 - 117),
            BotStage.DAILY_NEW to Rect(432, 12, 849 - 432, 78 - 12),
            BotStage.ENTER_LEAGUE to Rect(318, 235, 962 - 318, 384 - 235),
            BotStage.LEAGUE_RESULTS to Rect(512, 52, 779 - 512, 105 - 52),
            BotStage.ANTI_BOT to Rect(309, 10, 968 - 309, 111 - 10),
            BotStage.CONNECTION_LOST to Rect(351, 203, 936 - 351, 405 - 203),
            BotStage.INACTIVE to Rect(358, 247, 934 - 358, 372 - 247),
            BotStage.PREVIOUS_RANK_RESULTS to Rect(454, 42, 832 - 454, 115 - 42),
            BotStage.TOO_MANY_TREASURES to Rect(353, 154, 931 - 353, 476 - 154),
            BotStage.OVERTAKE_BREAK_SCORE to Rect(479, 64, 799 - 479, 132 - 64),
            BotStage.PARTY_RUN to Rect(359, 44, 922 - 359, 138 - 44),
            BotStage.PARTY_RUN_SEASON_REWARDS to Rect(350, 0, 590, 140),
            BotStage.GAME_SETTINGS to Rect(484, 62, 769 - 484, 122 - 62),
        )
private val TEMPLATE_PATHS_BY_STAGE = mapOf(
            BotStage.MAINMENU to listOf("templates/MAINMENU_1.png"),
            BotStage.PURCHASE_ITEM to listOf("templates/PURCHASE_ITEM_1.png"),
            BotStage.GAME_START to listOf("templates/GAME_START_1.png"),
            BotStage.GAME_RELAY to listOf("templates/GAME_RELAY_1.png"),
            BotStage.GAME_COMPLETE to listOf("templates/GAME_COMPLETE_1.png"),
            BotStage.MYSTERY_BOX to listOf("templates/MYSTERY_BOX_1.png"),
            BotStage.CONGRATULATIONS to listOf("templates/CONGRATULATIONS_1.png", "templates/CONGRATULATIONS_2.png"),
            BotStage.LEVEL_UP to listOf("templates/LEVEL_UP_1.png"),
            BotStage.RELIC_COMPLETE to listOf("templates/RELIC_COMPLETE_1.png"),
            BotStage.RELIC_CLAIM to listOf("templates/RELIC_CLAIM_1.png"),
            BotStage.DAILY_CHECKIN to listOf("templates/DAILY_CHECKIN_1.png"),
            BotStage.DAILY_CHECKIN_BOOST_SET to listOf("templates/DAILY_CHECKIN_BOOST_SET_1.png"),
            BotStage.DAILY_TREASURE to listOf("templates/DAILY_TREASURE_1.png"),
            BotStage.DAILY_NEW to listOf("templates/DAILY_NEW_1.png"),
            BotStage.ENTER_LEAGUE to listOf("templates/ENTER_LEAGUE_1.png"),
            BotStage.LEAGUE_RESULTS to listOf("templates/LEAGUE_RESULTS_1.png"),
            BotStage.ANTI_BOT to listOf("templates/ANTI_BOT_1.png"),
            BotStage.CONNECTION_LOST to listOf("templates/CONNECTION_LOST_1.png", "templates/CONNECTION_LOST_2.png"),
            BotStage.INACTIVE to listOf("templates/INACTIVE_1.png"),
            BotStage.PREVIOUS_RANK_RESULTS to listOf("templates/PREVIOUS_RANK_RESULTS_1.png"),
            BotStage.TOO_MANY_TREASURES to listOf("templates/TOO_MANY_TREASURES_1.png"),
            BotStage.OVERTAKE_BREAK_SCORE to listOf("templates/OVERTAKE_BREAK_SCORE_1.png"),
            BotStage.PARTY_RUN to listOf("templates/PARTY_RUN_1.png"),
            BotStage.PARTY_RUN_SEASON_REWARDS to listOf("templates/PARTY_RUN_SEASON_REWARDS_1.png"),
            BotStage.GAME_SETTINGS to listOf("templates/GAME_SETTINGS_1.png"),
        )
        private val SCALE_BY_STAGE = mapOf(
            BotStage.MAINMENU to listOf(1.0),
            BotStage.PURCHASE_ITEM to listOf(1.0),
            // Same animation-aware scale set used by Python's transient
            // GAME_START polling, without changing threshold or ROI.
            BotStage.GAME_START to listOf(0.70, 0.80, 0.90, 1.00, 1.10, 1.20, 1.30),
            BotStage.GAME_RELAY to listOf(0.70, 0.80, 0.90, 1.00, 1.10, 1.20, 1.30),
            BotStage.GAME_COMPLETE to listOf(1.0),
            BotStage.MYSTERY_BOX to listOf(1.0),
            BotStage.CONGRATULATIONS to listOf(1.0),
            BotStage.LEVEL_UP to listOf(1.0),
            BotStage.RELIC_COMPLETE to listOf(1.0),
            BotStage.RELIC_CLAIM to listOf(1.0),
            BotStage.DAILY_CHECKIN to listOf(1.0),
            BotStage.DAILY_CHECKIN_BOOST_SET to listOf(1.0),
            BotStage.DAILY_TREASURE to listOf(1.0),
            BotStage.DAILY_NEW to listOf(1.0),
            BotStage.ENTER_LEAGUE to listOf(1.0),
            BotStage.LEAGUE_RESULTS to listOf(1.0),
            BotStage.ANTI_BOT to listOf(1.0),
            BotStage.CONNECTION_LOST to listOf(1.0),
            BotStage.INACTIVE to listOf(1.0),
            BotStage.PREVIOUS_RANK_RESULTS to listOf(1.0),
            BotStage.TOO_MANY_TREASURES to listOf(1.0),
            BotStage.OVERTAKE_BREAK_SCORE to listOf(1.0),
            BotStage.PARTY_RUN to listOf(1.0),
            BotStage.PARTY_RUN_SEASON_REWARDS to listOf(1.0),
            BotStage.GAME_SETTINGS to listOf(1.0),
        )

        fun roiFor(stage: BotStage): Rect {
            val roi = ROI_BY_STAGE.getValue(stage)
            return Rect(roi.x, roi.y, roi.width, roi.height)
        }
    }
}

data class DetectionResult(
    val state: BotStage = BotStage.MAINMENU,
    val score: Double,
    val matched: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    companion object {
        fun notMatched(state: BotStage = BotStage.MAINMENU) =
            DetectionResult(state, -1.0, false, -1, -1, 0, 0)
    }
}

data class TemplateMatch(
    val score: Double,
    val matched: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)
