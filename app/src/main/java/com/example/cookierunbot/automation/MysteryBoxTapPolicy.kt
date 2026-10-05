package com.example.cookierunbot.automation

internal object MysteryBoxTapPolicy {
    const val MAX_PHASE = 3
    const val REVALIDATE_FRAME_TIMEOUT_MS = 1_000L
    const val GESTURE_CALLBACK_TIMEOUT_MS = 1_500L
    const val FINAL_PHASE_REQUIRED_FRESH_FRAMES = 2

    fun nextPhase(completedPhases: Int): Int? =
        if (completedPhases in 0 until MAX_PHASE) completedPhases + 1 else null

    fun jitterForPhase(phase: Int): Int =
        if (phase == MAX_PHASE) 0 else AutomationConfig.DEFAULT_SAFE_JITTER

    fun shouldRearmSameStageAfterSuccess(phase: Int): Boolean =
        phase < MAX_PHASE

    fun requiredFreshFrames(phase: Int): Int =
        if (phase == MAX_PHASE) FINAL_PHASE_REQUIRED_FRESH_FRAMES else 1
}
