package com.example.cookierunbot.automation

import kotlin.math.abs

internal data class AntiBotCardLayoutFingerprint(val pixels: ByteArray)

internal data class AntiBotPostTestState(
    val followUpActive: Boolean,
    val awaitingExit: Boolean,
)

internal fun antiBotPostTestState(testIndexInChallenge: Int): AntiBotPostTestState =
    AntiBotPostTestState(
        followUpActive = testIndexInChallenge in 1..2,
        awaitingExit = testIndexInChallenge == 3,
    )

internal class AntiBotExitConfirmationTracker(
    private val requiredConsecutiveMisses: Int = 2,
) {
    init {
        require(requiredConsecutiveMisses >= 1)
    }

    private var consecutiveMisses = 0

    fun reset() {
        consecutiveMisses = 0
    }

    fun observe(antiBotMatched: Boolean): Boolean {
        if (antiBotMatched) {
            consecutiveMisses = 0
            return false
        }
        consecutiveMisses += 1
        return consecutiveMisses >= requiredConsecutiveMisses
    }
}

internal enum class AntiBotLayoutDecision {
    SAME_ACCEPTED_LAYOUT,
    WAIT_FOR_STABLE_NEW_LAYOUT,
    ACCEPT_NEW_LAYOUT,
    FINGERPRINT_UNAVAILABLE,
}

/**
 * Card-content fingerprint dedicated to the six Anti-bot cards.
 *
 * Sampling stays inside each card so border/highlight animation is ignored. Each card sample is
 * mean-normalized, making uniform selection tint/brightness changes much less likely to look like
 * a new test layout. In addition to the whole-layout ratio, the comparison can recognize a stable
 * permutation of the same artwork: a moved slot must closely match artwork from another baseline
 * slot, and those mappings must form a real cycle. This catches shuffled tests without treating
 * two selected-card highlights as a new layout.
 */
internal object AntiBotCardLayoutFingerprinting {
    private const val SAMPLE_WIDTH = 16
    private const val SAMPLE_HEIGHT = 20
    private const val CARD_SAMPLE_SIZE = SAMPLE_WIDTH * SAMPLE_HEIGHT
    private const val CARD_WIDTH = 183
    private const val CARD_HEIGHT = 241
    private const val INSET_X = 28
    private const val INSET_Y = 30
    private const val PIXEL_DELTA = 18
    private const val GLOBAL_CHANGE_RATIO = 0.12
    private const val MOVED_SLOT_CHANGE_RATIO = 0.12
    private const val SAME_ARTWORK_MATCH_RATIO = 0.08
    private const val REORDER_ADVANTAGE_RATIO = 0.04

    private val CARD_ORIGINS = listOf(
        346 to 179, 542 to 179, 738 to 179,
        346 to 435, 542 to 435, 738 to 435,
    )

    fun sample(
        gray: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
    ): AntiBotCardLayoutFingerprint? {
        if (frameWidth <= 0 || frameHeight <= 0 || gray.size < frameWidth * frameHeight) return null
        val innerWidth = CARD_WIDTH - INSET_X * 2
        val innerHeight = CARD_HEIGHT - INSET_Y * 2
        if (innerWidth <= 0 || innerHeight <= 0) return null

        val output = ByteArray(CARD_ORIGINS.size * SAMPLE_WIDTH * SAMPLE_HEIGHT)
        var outputOffset = 0

        for ((originX, originY) in CARD_ORIGINS) {
            val left = originX + INSET_X
            val top = originY + INSET_Y
            val right = originX + CARD_WIDTH - INSET_X
            val bottom = originY + CARD_HEIGHT - INSET_Y
            if (left < 0 || top < 0 || right > frameWidth || bottom > frameHeight) return null

            val samples = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
            var sum = 0
            var index = 0
            for (y in 0 until SAMPLE_HEIGHT) {
                val sourceY = top +
                    ((y + 0.5) * innerHeight / SAMPLE_HEIGHT).toInt().coerceAtMost(innerHeight - 1)
                for (x in 0 until SAMPLE_WIDTH) {
                    val sourceX = left +
                        ((x + 0.5) * innerWidth / SAMPLE_WIDTH).toInt().coerceAtMost(innerWidth - 1)
                    val value = gray[sourceY * frameWidth + sourceX].toInt() and 0xff
                    samples[index++] = value
                    sum += value
                }
            }

            val mean = sum / samples.size
            for (value in samples) {
                val normalized = (value - mean).coerceIn(-127, 127) + 128
                output[outputOffset++] = normalized.toByte()
            }
        }
        return AntiBotCardLayoutFingerprint(output)
    }

    fun isMeaningfullyDifferent(
        before: AntiBotCardLayoutFingerprint,
        after: AntiBotCardLayoutFingerprint,
    ): Boolean {
        val expectedSize = CARD_ORIGINS.size * CARD_SAMPLE_SIZE
        if (
            before.pixels.size != after.pixels.size ||
            before.pixels.size != expectedSize
        ) {
            return true
        }

        val sameSlotDifferences = DoubleArray(CARD_ORIGINS.size) { cardIndex ->
            cardDifferenceRatio(before, cardIndex, after, cardIndex)
        }
        if (sameSlotDifferences.average() >= GLOBAL_CHANGE_RATIO) return true

        return hasReorderedCardCycle(before, after, sameSlotDifferences)
    }

    private fun hasReorderedCardCycle(
        before: AntiBotCardLayoutFingerprint,
        after: AntiBotCardLayoutFingerprint,
        sameSlotDifferences: DoubleArray,
    ): Boolean {
        val mapping = IntArray(CARD_ORIGINS.size) { -1 }
        for (candidateIndex in CARD_ORIGINS.indices) {
            val ownDifference = sameSlotDifferences[candidateIndex]
            if (ownDifference < MOVED_SLOT_CHANGE_RATIO) continue

            var bestBaselineIndex = -1
            var bestDifference = Double.POSITIVE_INFINITY
            for (baselineIndex in CARD_ORIGINS.indices) {
                if (baselineIndex == candidateIndex) continue
                val difference = cardDifferenceRatio(
                    before,
                    baselineIndex,
                    after,
                    candidateIndex,
                )
                if (difference < bestDifference) {
                    bestDifference = difference
                    bestBaselineIndex = baselineIndex
                }
            }

            if (
                bestBaselineIndex >= 0 &&
                bestDifference <= SAME_ARTWORK_MATCH_RATIO &&
                ownDifference - bestDifference >= REORDER_ADVANTAGE_RATIO
            ) {
                mapping[candidateIndex] = bestBaselineIndex
            }
        }

        for (start in mapping.indices) {
            if (mapping[start] < 0) continue
            val seen = BooleanArray(mapping.size)
            var current = start
            var steps = 0
            while (current in mapping.indices && mapping[current] >= 0 && !seen[current]) {
                seen[current] = true
                current = mapping[current]
                steps += 1
                if (current == start && steps >= 2) return true
            }
        }
        return false
    }

    private fun cardDifferenceRatio(
        before: AntiBotCardLayoutFingerprint,
        beforeCardIndex: Int,
        after: AntiBotCardLayoutFingerprint,
        afterCardIndex: Int,
    ): Double {
        val beforeStart = beforeCardIndex * CARD_SAMPLE_SIZE
        val afterStart = afterCardIndex * CARD_SAMPLE_SIZE
        var changed = 0
        for (offset in 0 until CARD_SAMPLE_SIZE) {
            val delta = abs(
                (before.pixels[beforeStart + offset].toInt() and 0xff) -
                    (after.pixels[afterStart + offset].toInt() and 0xff),
            )
            if (delta >= PIXEL_DELTA) changed += 1
        }
        return changed.toDouble() / CARD_SAMPLE_SIZE
    }
}

/**
 * A different layout must be observed twice on fresh frames before it is accepted as the next
 * Anti-bot test. This prevents click highlights/transition animation from turning Test 1 into a
 * false Test 2.
 */
internal class AntiBotLayoutTransitionGuard(
    private val requiredStableSamples: Int = 2,
) {
    init {
        require(requiredStableSamples >= 2)
    }

    private var accepted: AntiBotCardLayoutFingerprint? = null
    private var pending: AntiBotCardLayoutFingerprint? = null
    private var pendingSamples = 0

    fun reset() {
        accepted = null
        pending = null
        pendingSamples = 0
    }

    fun recordAccepted(fingerprint: AntiBotCardLayoutFingerprint?) {
        accepted = fingerprint
        pending = null
        pendingSamples = 0
    }

    fun evaluateFollowUp(candidate: AntiBotCardLayoutFingerprint?): AntiBotLayoutDecision {
        val baseline = accepted ?: return AntiBotLayoutDecision.FINGERPRINT_UNAVAILABLE
        candidate ?: return AntiBotLayoutDecision.FINGERPRINT_UNAVAILABLE

        if (!AntiBotCardLayoutFingerprinting.isMeaningfullyDifferent(baseline, candidate)) {
            pending = null
            pendingSamples = 0
            return AntiBotLayoutDecision.SAME_ACCEPTED_LAYOUT
        }

        val pendingFingerprint = pending
        if (
            pendingFingerprint == null ||
            AntiBotCardLayoutFingerprinting.isMeaningfullyDifferent(pendingFingerprint, candidate)
        ) {
            pending = candidate
            pendingSamples = 1
            return AntiBotLayoutDecision.WAIT_FOR_STABLE_NEW_LAYOUT
        }

        pendingSamples += 1
        return if (pendingSamples >= requiredStableSamples) {
            AntiBotLayoutDecision.ACCEPT_NEW_LAYOUT
        } else {
            AntiBotLayoutDecision.WAIT_FOR_STABLE_NEW_LAYOUT
        }
    }
}
