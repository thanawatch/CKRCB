package com.example.cookierunbot.automation

/** Safety decisions for the synchronous Mail Box workflow. */
internal object QuickLivesSafetyPolicy {
    fun timedOut(nowMs: Long, deadlineMs: Long): Boolean = nowMs >= deadlineMs

    enum class Phase { AFTER_MAIL_BOX, AFTER_LIVES_TAB, WAITING_FOR_RESULT, BEFORE_MAIL_CLOSE }

    /** A small per-workflow visual reference, reset at each expected Mail transition. */
    class MailContext {
        private var phase: Phase? = null
        private var reference: PopupFingerprint? = null

        fun expectedScreenVisible(
            currentPhase: Phase,
            fingerprint: PopupFingerprint?,
            popupCandidate: BotStage?,
            mainMenuMatched: Boolean,
            knownMailTemplateMatched: Boolean,
        ): Boolean {
            if (phase != currentPhase) {
                phase = currentPhase
                reference = null
            }
            if (popupCandidate == null) {
                if (fingerprint != null) reference = fingerprint
                return knownMailTemplateMatched
            }
            if (knownMailTemplateMatched) return true
            if (!mainMenuMatched || fingerprint == null) return false
            val prior = reference
            if (prior == null) {
                // The first settled frame after a successful Mail action is the
                // reference when this phase has no dedicated Mail template.
                reference = fingerprint
                return true
            }
            return !PopupFingerprinting.isMeaningfullyDifferent(prior, fingerprint)
        }
    }

    data class ScreenSignal(
        val phase: Phase,
        val alwaysState: BotStage?,
        val popupState: BotStage?,
        val mainMenuMatched: Boolean,
        val expectedMailContextValid: Boolean = false,
    )

    fun interruptState(first: ScreenSignal, confirmation: ScreenSignal? = null): BotStage? {
        // MAINMENU can remain visible beneath the Mail overlay. It is not an interrupt.
        first.alwaysState?.let { return it }
        confirmation?.alwaysState?.let { return it }
        if (first.phase != confirmation?.phase) return null
        val confirmedMailContext = confirmation?.mainMenuMatched == true &&
            confirmation?.expectedMailContextValid == true
        return first.popupState?.takeIf {
            it == confirmation?.popupState && !confirmedMailContext
        }
    }
}
