package com.example.cookierunbot.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainMenuWorkflowPolicyTest {
    @Test
    fun mainMenuRemainingVisibleAllowsMailBoxWorkflow() {
        assertTrue(MainMenuWorkflowPolicy.isMainMenuValid(alwaysState = null, mainMenuMatched = true))
    }

    @Test
    fun missingMainMenuBlocksMailBoxWorkflow() {
        assertFalse(MainMenuWorkflowPolicy.isMainMenuValid(alwaysState = null, mainMenuMatched = false))
    }

    @Test
    fun connectionLostDuringWaitBlocksMailBoxWorkflow() {
        assertFalse(
            MainMenuWorkflowPolicy.isMainMenuValid(
                alwaysState = BotStage.CONNECTION_LOST,
                mainMenuMatched = true,
            ),
        )
    }

    @Test
    fun strongMainMenuMatchWinsOverNonAlwaysPopupFalsePositive() {
        assertTrue(MainMenuWorkflowPolicy.isMainMenuValid(alwaysState = null, mainMenuMatched = true))
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
        assertTrue(MainMenuWorkflowPolicy.isMainMenuValid(alwaysState = null, mainMenuMatched = true))
        assertFalse(
            MainMenuWorkflowPolicy.livesPendingAfterCheckpoint(
                currentlyPending = true,
                workflowCompleted = true,
            ),
        )
    }
}
