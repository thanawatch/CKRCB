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
                // Only a popup-free frame may establish the trusted visual
                // reference for this Mail phase.
                if (fingerprint != null) reference = fingerprint
                return knownMailTemplateMatched
            }
            if (!mainMenuMatched || fingerprint == null) return false
            // Never learn an untrusted popup candidate as the Mail reference.
            val prior = reference ?: return false
            // A Mail template can remain visible behind a real popup, so visual
            // consistency with the already-trusted reference remains required.
            if (PopupFingerprinting.isMeaningfullyDifferent(prior, fingerprint)) return false
            return true
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
