package com.example.cookierunbot.automation

/** Raw physical targets are calibrated only for the legacy device geometry. */
object PhysicalTargetPolicy {
    fun isCalibrated(profile: PerformanceProfile): Boolean = profile == PerformanceProfile.LEGACY
}
