package com.example.cookierunbot.automation

/** Remembers an accepted boost purchase while retrying Play on the same purchase screen. */
internal class PurchaseItemRetryState {
    private var boostPurchasePerformed = false

    fun shouldPrepareBoost(enabled: Boolean): Boolean = enabled && !boostPurchasePerformed

    fun boostPurchasePerformed() {
        boostPurchasePerformed = true
    }

    fun onDetectedStage(stage: BotStage) {
        // Temporary PRE_GAME popups do not start a new purchase transaction.
        if (stage == BotStage.MAINMENU) boostPurchasePerformed = false
    }

    fun onPlayDispatch(accepted: Boolean): Boolean {
        if (accepted) boostPurchasePerformed = false
        return accepted
    }

    fun reset() {
        boostPurchasePerformed = false
    }
}
