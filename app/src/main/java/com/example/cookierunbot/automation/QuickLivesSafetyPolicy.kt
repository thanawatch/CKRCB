package com.example.cookierunbot.automation

/** Safety decisions for the synchronous Mail Box workflow. */
internal object QuickLivesSafetyPolicy {
    fun timedOut(nowMs: Long, deadlineMs: Long): Boolean = nowMs >= deadlineMs

    data class ScreenSignal(
        val alwaysState: BotStage?,
        val popupState: BotStage?,
        val mainMenuMatched: Boolean,
    )

    fun interruptState(first: ScreenSignal, confirmation: ScreenSignal? = null): BotStage? {
        // MAINMENU can remain visible beneath the Mail overlay. It is not an interrupt.
        first.alwaysState?.let { return it }
        confirmation?.alwaysState?.let { return it }
        return first.popupState?.takeIf {
            it == confirmation?.popupState && confirmation?.mainMenuMatched == false
        }
    }
}
