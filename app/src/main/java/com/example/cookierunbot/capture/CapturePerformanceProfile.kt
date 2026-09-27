package com.example.cookierunbot.capture

import com.example.cookierunbot.automation.PerformanceProfile

/** Session-scoped capture setting. It is changed only by Start/Stop Bot. */
object CapturePerformanceProfile {
    private val lock = Any()
    private var activeRunGeneration: Long? = null

    @Volatile
    var active: PerformanceProfile = PerformanceProfile.LEGACY
        private set

    fun activate(runGeneration: Long, profile: PerformanceProfile) = synchronized(lock) {
        activeRunGeneration = runGeneration
        active = profile
    }

    fun reset(runGeneration: Long) = synchronized(lock) {
        if (activeRunGeneration == runGeneration) {
            activeRunGeneration = null
            active = PerformanceProfile.LEGACY
        }
    }

    fun canUseExactSizeFastPath(transform: ContentTransform): Boolean =
        canUseExactSizeFastPath(active, transform)

    fun canUseExactSizeFastPath(profile: PerformanceProfile, transform: ContentTransform): Boolean =
        profile == PerformanceProfile.REDFINGER_OPTIMIZED &&
            transform.sourceWidth == FrameNormalizer.LOGICAL_WIDTH &&
            transform.sourceHeight == FrameNormalizer.LOGICAL_HEIGHT &&
            transform.contentRect.x == 0 && transform.contentRect.y == 0 &&
            transform.contentRect.width == FrameNormalizer.LOGICAL_WIDTH &&
            transform.contentRect.height == FrameNormalizer.LOGICAL_HEIGHT
}
