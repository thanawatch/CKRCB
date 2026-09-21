package com.example.cookierunbot.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainMenuWorkflowPolicyTest {
    @Test
    fun mainMenuRemainingVisibleAllowsMailBoxWorkflow() {
        assertTrue(MainMenuWorkflowPolicy.isMainMenuValid(BotStage.MAINMENU))
    }

    @Test
    fun previousResultsDuringWaitBlocksMailBoxWorkflow() {
        assertFalse(MainMenuWorkflowPolicy.isMainMenuValid(BotStage.PREVIOUS_RESULTS))
    }

    @Test
    fun connectionLostDuringWaitBlocksMailBoxWorkflow() {
        assertFalse(MainMenuWorkflowPolicy.isMainMenuValid(BotStage.CONNECTION_LOST))
    }

    @Test
    fun abortedWorkflowPreservesPendingLivesIntent() {
        assertTrue(
            MainMenuWorkflowPolicy.livesPendingAfterCheckpoint(
                currentlyPending = true,
                workflowCompleted = false,
            ),
        )
    }

    @Test
    fun genuineMainMenuCanCompletePendingLivesWorkflow() {
        assertTrue(MainMenuWorkflowPolicy.isMainMenuValid(BotStage.MAINMENU))
        assertFalse(
            MainMenuWorkflowPolicy.livesPendingAfterCheckpoint(
                currentlyPending = true,
                workflowCompleted = true,
            ),
        )
    }
}
