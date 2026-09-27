package com.example.cookierunbot.notifications

import com.example.cookierunbot.update.UpdateDescriptor
import com.example.cookierunbot.update.UpdatePolicy

/** Read-only Telegram update status. Download/install stays inside the Android app. */
internal fun formatTelegramUpdateReply(
    installedVersion: String,
    installedCode: Long,
    available: UpdateDescriptor?,
    feedConfigured: Boolean,
    checkFailed: Boolean = false,
): String {
    val header = "📦 RobotRun Update\n\nInstalled: $installedVersion (code $installedCode)"
    return when {
        !feedConfigured -> "$header\n\nOnline update feed: not configured.\n" +
            "Open RobotRun → Update App to set an HTTPS update feed, or select a downloaded APK."
        checkFailed -> "$header\n\nCould not check the configured update feed. " +
            "Open RobotRun → Update App to verify the feed URL or select a downloaded APK."
        available == null -> "$header\n\nNo update data available. Open RobotRun → Update App."
        UpdatePolicy.hasNewerVersion(installedCode, available.versionCode) ->
            "$header\nAvailable: ${available.versionName} (code ${available.versionCode})\n" +
                "New update available.\n\nWhat's new:\n" +
                available.notes.ifBlank { "No release notes." } +
                "\n\nTo download and install: open RobotRun → Update App. " +
                "Android installation requires your confirmation."
        else -> "$header\nLatest feed version: ${available.versionName} " +
            "(code ${available.versionCode})\n\nRobotRun is up to date."
    }
}
