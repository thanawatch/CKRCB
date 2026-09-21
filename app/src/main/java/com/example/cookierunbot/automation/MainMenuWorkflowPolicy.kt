package com.example.cookierunbot.automation

/** Pure decisions used by the long-running MAINMENU workflow. */
internal object MainMenuWorkflowPolicy {
    fun isMainMenuValid(detectedState: BotStage?): Boolean = detectedState == BotStage.MAINMENU

    fun livesPendingAfterCheckpoint(currentlyPending: Boolean, workflowCompleted: Boolean): Boolean =
        currentlyPending && !workflowCompleted
}
