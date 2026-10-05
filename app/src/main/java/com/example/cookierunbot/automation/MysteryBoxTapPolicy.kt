package com.example.cookierunbot.automation

internal object MysteryBoxTapPolicy {
    const val MAX_PHASE = 3
    const val REVALIDATE_FRAME_TIMEOUT_MS = 1_000L
    const val GESTURE_CALLBACK_TIMEOUT_MS = 1_500L
    const val FINAL_PHASE_REQUIRED_FRESH_FRAMES = 2
    const val MAX_AMBIGUOUS_MAINMENU_FRAMES = 2

    enum class FrameDecision {
        ALLOW_TAP,
        WAIT_FOR_UNAMBIGUOUS_FRAME,
        STOP_MAINMENU_VISIBLE,
        DEFER_MYSTERY_BOX_GONE,
    }

    fun nextPhase(completedPhases: Int): Int? =
        if (completedPhases in 0 until MAX_PHASE) completedPhases + 1 else null

    fun jitterForPhase(phase: Int): Int =
        if (phase == MAX_PHASE) 0 else AutomationConfig.DEFAULT_SAFE_JITTER

    fun shouldRearmSameStageAfterSuccess(phase: Int): Boolean =
        phase < MAX_PHASE

    fun requiredFreshFrames(phase: Int): Int =
        if (phase == MAX_PHASE) FINAL_PHASE_REQUIRED_FRESH_FRAMES else 1

    fun frameDecision(
        mysteryBoxMatched: Boolean,
        mainMenuMatched: Boolean,
        ambiguousMainMenuFrames: Int,
    ): FrameDecision = when {
        mysteryBoxMatched && !mainMenuMatched -> FrameDecision.ALLOW_TAP
        mysteryBoxMatched && mainMenuMatched &&
            ambiguousMainMenuFrames < MAX_AMBIGUOUS_MAINMENU_FRAMES ->
            FrameDecision.WAIT_FOR_UNAMBIGUOUS_FRAME
        mysteryBoxMatched && mainMenuMatched ->
            FrameDecision.STOP_MAINMENU_VISIBLE
        !mysteryBoxMatched && mainMenuMatched ->
            FrameDecision.STOP_MAINMENU_VISIBLE
        else -> FrameDecision.DEFER_MYSTERY_BOX_GONE
    }
}
