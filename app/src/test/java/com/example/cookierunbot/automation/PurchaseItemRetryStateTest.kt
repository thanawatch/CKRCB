package com.example.cookierunbot.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseItemRetryStateTest {
    @Test fun rejectedPlayRetriesWithoutRepurchasingOrStartingRound() {
        val retry = PurchaseItemRetryState()
        assertTrue(retry.shouldPrepareBoost(true))
        retry.boostPrepared()
        assertFalse(retry.onPlayDispatch(false))
        retry.onDetectedStage(BotStage.PURCHASE_ITEM)
        assertFalse(retry.shouldPrepareBoost(true))
        assertTrue(retry.onPlayDispatch(true))
        assertTrue(retry.shouldPrepareBoost(true))
    }

    @Test fun newPurchaseFlowCanPrepareBoostAgain() {
        val retry = PurchaseItemRetryState()
        retry.boostPrepared()
        retry.onDetectedStage(BotStage.MAINMENU)
        assertTrue(retry.shouldPrepareBoost(true))
        assertFalse(retry.shouldPrepareBoost(false))
    }
}
