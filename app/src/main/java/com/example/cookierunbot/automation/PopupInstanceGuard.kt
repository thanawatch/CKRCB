package com.example.cookierunbot.automation

import kotlin.math.abs

internal data class FingerprintRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class PopupFingerprint(val pixels: ByteArray)

internal data class PopupGuardDecision(
    val allowed: Boolean,
    val sameInstance: Boolean,
    val attempt: Int,
)

/** Pure popup-instance classifier; no Android or OpenCV dependency, so it is unit-testable. */
internal class PopupInstanceGuard(private val maxAttempts: Int) {
    private var stage: BotStage? = null
    private var fingerprint: PopupFingerprint? = null
    private var attempts = 0

    fun evaluate(candidateStage: BotStage, candidate: PopupFingerprint?): PopupGuardDecision {
        val same = stage == candidateStage &&
            candidate != null && fingerprint != null &&
            !PopupFingerprinting.isMeaningfullyDifferent(fingerprint!!, candidate)
        val nextAttempt = if (same) attempts + 1 else 1
        return PopupGuardDecision(!same || nextAttempt <= maxAttempts, same, nextAttempt)
    }

    fun record(candidateStage: BotStage, candidate: PopupFingerprint?) {
        val decision = evaluate(candidateStage, candidate)
        attempts = decision.attempt
        stage = candidateStage
        fingerprint = candidate
    }

    fun reset() {
        stage = null
        fingerprint = null
        attempts = 0
    }
}

internal object PopupFingerprinting {
    const val WIDTH = 96
    const val HEIGHT = 64
    private const val PIXEL_DELTA = 16
    private const val CHANGE_RATIO = 0.0125

    val fallbackRegion = FingerprintRegion(240, 40, 1040, 680)

    fun regionFor(stage: BotStage): FingerprintRegion = when (stage) {
        // Includes the changing body/reward content, not only the stable title.
        BotStage.CONGRATULATIONS -> FingerprintRegion(230, 55, 1050, 680)
        BotStage.LEVEL_UP -> FingerprintRegion(300, 20, 980, 680)
        BotStage.DAILY_CHECKIN -> FingerprintRegion(285, 0, 995, 700)
        BotStage.DAILY_CHECKIN_BOOST_SET -> FingerprintRegion(300, 45, 980, 680)
        BotStage.DAILY_TREASURE -> FingerprintRegion(275, 65, 1005, 680)
        BotStage.DAILY_NEW -> FingerprintRegion(300, 0, 1100, 620)
        BotStage.ENTER_LEAGUE -> FingerprintRegion(250, 170, 1030, 650)
        BotStage.LEAGUE_RESULTS -> FingerprintRegion(285, 20, 995, 680)
        BotStage.PREVIOUS_RANK_RESULTS -> FingerprintRegion(285, 15, 995, 680)
        BotStage.TOO_MANY_TREASURES -> FingerprintRegion(265, 100, 1015, 680)
        BotStage.RELIC_COMPLETE -> FingerprintRegion(300, 20, 980, 680)
        BotStage.RELIC_CLAIM -> FingerprintRegion(275, 30, 1005, 680)
        BotStage.PARTY_RUN -> FingerprintRegion(260, 20, 1020, 680)
        BotStage.GAME_SETTINGS -> FingerprintRegion(300, 25, 980, 680)
        BotStage.PREVIOUS_RESULTS -> FingerprintRegion(260, 120, 1020, 680)
        else -> fallbackRegion
    }

    fun sample(
        gray: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        region: FingerprintRegion,
    ): PopupFingerprint? {
        if (frameWidth <= 0 || frameHeight <= 0 || gray.size < frameWidth * frameHeight) return null
        val left = region.left.coerceIn(0, frameWidth - 1)
        val top = region.top.coerceIn(0, frameHeight - 1)
        val right = region.right.coerceIn(left + 1, frameWidth)
        val bottom = region.bottom.coerceIn(top + 1, frameHeight)
        val regionWidth = right - left
        val regionHeight = bottom - top
        val sampled = ByteArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            val sourceY = top + ((y + 0.5) * regionHeight / HEIGHT).toInt().coerceAtMost(regionHeight - 1)
            for (x in 0 until WIDTH) {
                val sourceX = left + ((x + 0.5) * regionWidth / WIDTH).toInt().coerceAtMost(regionWidth - 1)
                sampled[y * WIDTH + x] = gray[sourceY * frameWidth + sourceX]
            }
        }
        return PopupFingerprint(sampled)
    }

    fun isMeaningfullyDifferent(before: PopupFingerprint, after: PopupFingerprint): Boolean {
        if (before.pixels.size != after.pixels.size || before.pixels.isEmpty()) return true
        var changed = 0
        for (index in before.pixels.indices) {
            val delta = abs(
                (before.pixels[index].toInt() and 0xff) -
                    (after.pixels[index].toInt() and 0xff),
            )
            if (delta >= PIXEL_DELTA) changed += 1
        }
        return changed.toDouble() / before.pixels.size >= CHANGE_RATIO
    }
}
