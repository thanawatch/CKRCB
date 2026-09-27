package com.example.cookierunbot.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Thread-safe in-memory log for important bot events shown by DebugLogActivity. */
object DebugLogStore {
    private const val MAX_LINES = 500
    private val lock = Any()
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var revision = 0L

    fun log(message: String) = synchronized(lock) {
        val clean = message.replace('\n', ' ').replace('\r', ' ')
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast("[${timestamp.format(Date())}] $clean")
        OvernightLogArchive.log(clean)
        revision += 1L
    }

    fun snapshot(): DebugLogSnapshot = synchronized(lock) {
        DebugLogSnapshot(lines.joinToString("\n"), lines.size, revision)
    }

    fun clear() = synchronized(lock) {
        lines.clear()
        revision += 1L
    }
}

data class DebugLogSnapshot(
    val text: String,
    val lineCount: Int,
    val revision: Long,
)
