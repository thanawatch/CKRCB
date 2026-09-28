package com.example.cookierunbot.diagnostics

/** Pure policies: use wall-clock UTC hours for archive rotation and millisecond timestamps for windows. */
internal object OvernightLogPolicy {
    const val RETENTION_MS = 10L * 60L * 60L * 1000L
    const val MAX_ARCHIVE_BYTES = 200L * 1024L * 1024L
    const val MAX_INCIDENT_BYTES = 200L * 1024L * 1024L
    const val INCIDENT_RETENTION_MS = 72L * 60L * 60L * 1000L
    const val BEFORE_INCIDENT_MS = 30L * 60L * 1000L
    const val TELEGRAM_MAX_BYTES = 49_000_000L

    fun shouldKeep(eventMs: Long, nowMs: Long): Boolean =
        eventMs <= nowMs && eventMs >= nowMs - RETENTION_MS

    fun inWindow(eventMs: Long, startMs: Long, endMs: Long): Boolean =
        eventMs in startMs..endMs

    fun sanitized(message: String): String =
        message.replace(Regex("""bot[0-9]+:[A-Za-z0-9_-]+"""), "bot[REDACTED]")
            .replace(Regex("""(?i)(botToken|token|password|authorization)=([^\s&]+)"""), "$1=[REDACTED]")
            .replace('\r', ' ').replace('\n', ' ')

    fun lineTimestamp(line: String): Long? =
        line.takeIf { it.startsWith('[') }?.substringAfter('[')?.substringBefore(']')?.toLongOrNull()
}
