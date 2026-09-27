package com.example.cookierunbot.automation

/** Calibrated on the Redfinger 1280x720 normalized capture, not raw device pixels. */
internal object PostRelayTargetPolicy {
    fun optimizedLogicalTargets(profile: PerformanceProfile): List<LogicalPoint>? =
        if (profile == PerformanceProfile.REDFINGER_OPTIMIZED) {
            listOf(
                LogicalPoint(1198, 35), // POST_RELAY_1
                LogicalPoint(647, 425), // POST_RELAY_2
                LogicalPoint(637, 389), // POST_RELAY_3
            )
        } else {
            null // Legacy keeps its three original absolute 2400x1080 physical taps.
        }
}
