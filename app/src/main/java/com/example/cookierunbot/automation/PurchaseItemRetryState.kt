package com.example.cookierunbot.automation

/** Remembers a prepared boost only while retrying Play on the same purchase screen. */
internal class PurchaseItemRetryState {
    private var boostPrepared = false

    fun shouldPrepareBoost(enabled: Boolean): Boolean = enabled && !boostPrepared

    fun boostPrepared() {
        boostPrepared = true
    }

    fun onDetectedStage(stage: BotStage) {
        if (stage != BotStage.PURCHASE_ITEM) boostPrepared = false
    }

    fun onPlayDispatch(accepted: Boolean): Boolean {
        if (accepted) boostPrepared = false
        return accepted
    }

    fun reset() {
        boostPrepared = false
    }
}
