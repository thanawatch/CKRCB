package com.example.cookierunbot.automation

/** Pure decisions used by the long-running MAINMENU workflow. */
internal object MainMenuWorkflowPolicy {
    fun isMainMenuValid(alwaysState: BotStage?, mainMenuMatched: Boolean): Boolean =
        alwaysState == null && mainMenuMatched

    fun livesPendingAfterCheckpoint(currentlyPending: Boolean, workflowCompleted: Boolean): Boolean =
        currentlyPending && !workflowCompleted
}
