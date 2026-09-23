package com.example.cookierunbot.automation

/** Run-owned gate; BotController holds its lock for each transition. */
internal class ProgressRecoveryGate {
    private var ownerRunGeneration: Long? = null

    fun isActive(): Boolean = ownerRunGeneration != null

    fun begin(runGeneration: Long): Boolean {
        if (ownerRunGeneration != null) return false
        ownerRunGeneration = runGeneration
        return true
    }

    fun clearIfOwner(runGeneration: Long): Boolean {
        if (ownerRunGeneration != runGeneration) return false
        ownerRunGeneration = null
        return true
    }

    fun clearAfterFreshFrame(runGeneration: Long, baseline: Long, freshGeneration: Long): Boolean {
        if (freshGeneration <= baseline) return false
        return clearIfOwner(runGeneration)
    }

    fun reset() {
        ownerRunGeneration = null
    }
}
