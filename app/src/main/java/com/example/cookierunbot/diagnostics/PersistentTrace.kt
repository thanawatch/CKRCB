package com.example.cookierunbot.diagnostics

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small, durable lifecycle trace that survives reboot and does not depend on USB Logcat. */
object PersistentTrace {
    private val lock = Any()
    private var traceFile: File? = null
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun initialize(context: Context) = synchronized(lock) {
        if (traceFile != null) return
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val directory = File(root, "diagnostics").apply { mkdirs() }
        val file = File(directory, "coldstart_trace.txt")
        if (file.length() > MAX_FILE_BYTES) {
            File(directory, "coldstart_trace_previous.txt").let { previous ->
                if (previous.exists()) previous.delete()
                file.renameTo(previous)
            }
        }
        traceFile = File(directory, "coldstart_trace.txt")
        appendLocked("===== PROCESS START elapsed=${SystemClock.elapsedRealtime()}ms =====")
    }

    fun log(message: String) = synchronized(lock) {
        appendLocked(message)
    }

    fun path(): String? = synchronized(lock) { traceFile?.absolutePath }

    private fun appendLocked(message: String) {
        val file = traceFile ?: return
        runCatching {
            file.appendText("[${timestamp.format(Date())}] $message\n", Charsets.UTF_8)
        }
    }

    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
}
