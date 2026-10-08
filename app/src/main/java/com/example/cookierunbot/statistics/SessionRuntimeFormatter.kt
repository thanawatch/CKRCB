package com.example.cookierunbot.statistics

internal fun formatSessionRuntime(runtimeMs: Long): String {
    val totalSeconds = runtimeMs.coerceAtLeast(0L) / 1_000L
    val days = totalSeconds / 86_400L
    val hours = totalSeconds % 86_400L / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L

    if (days > 0L) {
        return buildList {
            add("$days ${if (days == 1L) "day" else "days"}")
            if (hours > 0L) add("${hours}h")
            if (minutes > 0L) add("${minutes}m")
        }.joinToString(" ")
    }

    return when {
        hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
