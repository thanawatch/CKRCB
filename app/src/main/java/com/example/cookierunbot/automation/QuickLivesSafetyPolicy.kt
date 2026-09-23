package com.example.cookierunbot.automation

/** Safety decisions for the synchronous Mail Box workflow. */
internal object QuickLivesSafetyPolicy {
    fun timedOut(nowMs: Long, deadlineMs: Long): Boolean = nowMs >= deadlineMs

    fun interruptState(
        alwaysState: BotStage?,
        popupState: BotStage?,
        mainMenuMatched: Boolean,
    ): BotStage? = alwaysState ?: popupState ?: BotStage.MAINMENU.takeIf { mainMenuMatched }
}
