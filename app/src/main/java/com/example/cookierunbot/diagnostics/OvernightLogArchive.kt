package com.example.cookierunbot.diagnostics

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded background-only text archive. Do not run disk I/O or redact strings on capture/detector
 * callers. Each line is epoch-millis-prefixed so exports can select an exact rolling 24h window.
 * Size caps are secondary safety bounds; if exceeded the archive reports a loss of completeness.
 */
object OvernightLogArchive {
    private val rejected = AtomicLong(0)
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(8_192),
        { task -> Thread(task, "robotrun-log-archive").apply { priority = Thread.MIN_PRIORITY } },
        { _, _ -> rejected.incrementAndGet() },
    )
    @Volatile private var directory: File? = null
    private var currentHour = -1L // archive worker only
    private var stream: FileOutputStream? = null
    private var writer: BufferedWriter? = null
    private var lastCleanupHour = -1L

    fun initialize(context: Context) {
        if (directory != null) return
        synchronized(this) {
            if (directory != null) return
            directory = File(context.filesDir, "diagnostics/rolling").apply { mkdirs() }
        }
        log("[ARCHIVE] PROCESS_START")
    }

    fun log(message: String) {
        if (directory == null) return
        val at = System.currentTimeMillis()
        worker.execute {
            try {
                openHour(at)
                val dropped = rejected.getAndSet(0L)
                if (dropped > 0L) append(at, "[ARCHIVE_LOSS] queue_dropped=$dropped")
                append(at, message)
            } catch (_: Exception) {
                rejected.incrementAndGet()
            }
        }
    }

    private fun append(at: Long, message: String) {
        writer?.apply {
            write("[$at] ")
            write(OvernightLogPolicy.sanitized(message))
            newLine()
            flush()
        }
    }

    private fun openHour(at: Long) {
        val hour = Math.floorDiv(at, 3_600_000L)
        if (hour != currentHour || writer == null) {
            writer?.close()
            stream?.close()
            val name = "log_${hour}.txt"
            val nextStream = FileOutputStream(File(requireNotNull(directory), name), true)
            stream = nextStream
            writer = BufferedWriter(OutputStreamWriter(nextStream, Charsets.UTF_8), 16 * 1024)
            currentHour = hour
        }
        if (lastCleanupHour != hour) {
            lastCleanupHour = hour
            cleanup(at)
        }
    }

    private fun cleanup(nowMs: Long) {
        val root = directory ?: return
        var files = root.listFiles { f -> f.isFile && f.name.matches(Regex("log_[0-9]+\\.txt")) }
            ?.sortedBy { it.name.removePrefix("log_").removeSuffix(".txt").toLongOrNull() ?: 0L }
            ?: return
        files.filter { f ->
            val hour = f.name.removePrefix("log_").removeSuffix(".txt").toLongOrNull() ?: 0L
            (hour + 1L) * 3_600_000L < nowMs - OvernightLogPolicy.RETENTION_MS
        }.forEach { f -> if (f.name != "log_$currentHour.txt") f.delete() }
        files = root.listFiles { f -> f.isFile && f.name.startsWith("log_") }?.sortedBy { it.lastModified() }
            ?: return
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            if (bytes <= OvernightLogPolicy.MAX_ARCHIVE_BYTES) break
            if (file.name == "log_$currentHour.txt") continue
            bytes -= file.length()
            file.delete()
            append(nowMs, "[ARCHIVE_LOSS] max_200MiB_pruned=${file.name}")
        }
    }

    /** Called from the incident/export worker, never from gameplay/capture or the archive worker. */
    fun copyWindow(startMs: Long, endMs: Long, destination: File) {
        require(startMs <= endMs)
        val task = worker.submit(Callable {
            writer?.flush()
            destination.parentFile?.mkdirs()
            destination.bufferedWriter(Charsets.UTF_8).use { output ->
                val dir = directory
                val files = dir?.listFiles { file ->
                    file.isFile && file.name.matches(Regex("log_[0-9]+\\.txt"))
                }?.sortedBy { it.name.removePrefix("log_").removeSuffix(".txt").toLongOrNull() ?: 0L }
                    ?: emptyList()
                for (file in files) {
                    file.useLines { lines ->
                        lines.forEach { line ->
                            val timestamp = OvernightLogPolicy.lineTimestamp(line)
                            if (timestamp != null && OvernightLogPolicy.inWindow(timestamp, startMs, endMs)) {
                                output.appendLine(line)
                            }
                        }
                    }
                }
                val dropped = rejected.get()
                if (dropped > 0L) output.appendLine("[ARCHIVE_LOSS] pending_queue_dropped=$dropped")
            }
        })
        task.get(90, TimeUnit.SECONDS)
    }

    fun directory(): File? = directory
}
