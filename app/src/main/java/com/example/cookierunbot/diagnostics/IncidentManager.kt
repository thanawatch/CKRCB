package com.example.cookierunbot.diagnostics

import android.content.Context
import android.os.SystemClock
import com.example.cookierunbot.automation.BotControllerDiagnostics
import com.example.cookierunbot.automation.GameProgressStatus
import com.example.cookierunbot.capture.Diagnostics
import com.example.cookierunbot.capture.LatestFrameProvider
import com.example.cookierunbot.notifications.TelegramScreenshotEncoder
import com.example.cookierunbot.capture.IncidentVideoRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One incident per reserved genuine watchdog Recovery. The 30-minute prelude is frozen before
 * the Recovery worker is invoked; later action events and the video are separate, append-only.
 * No incident I/O is performed on the watchdog scheduler or the gameplay worker.
 */
object IncidentManager {
    private data class Incident(val generation: Long, val id: String, val directory: File, val startedAt: Long)
    private val lock = Any()
    @Volatile private var active: Incident? = null
    private val cleanupStarted = AtomicBoolean(false)
    private val cleanupScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "robotrun-incident-retention").apply { priority = Thread.MIN_PRIORITY }
    }
    private val executor = Executors.newSingleThreadExecutor { job ->
        Thread(job, "robotrun-incident-archive").apply { priority = Thread.MIN_PRIORITY }
    }

    fun initialize(context: Context) {
        if (!cleanupStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        cleanupScheduler.scheduleWithFixedDelay({
            executor.execute {
                runCatching { cleanup(appContext, System.currentTimeMillis()) }
            }
        }, 0L, 1L, TimeUnit.HOURS)
    }

    fun begin(
        context: Context, generation: Long, status: GameProgressStatus,
        bot: BotControllerDiagnostics, capture: Diagnostics,
    ) {
        if (generation < 0L) return // Manual test is not a real overdue episode.
        val at = System.currentTimeMillis()
        val id = "incident_${at}_${generation}"
        val directory = File(context.filesDir, "diagnostics/incidents/$id")
        val incident = synchronized(lock) {
            if (active != null) return
            Incident(generation, id, directory, at).also { active = it }
        }
        // Trigger only queues work to a separate recorder. Never wait for an encoder here.
        val triggerElapsedNanos = SystemClock.elapsedRealtimeNanos()
        IncidentVideoRecorder.trigger(id, File(directory, "incident_video.mp4"), triggerElapsedNanos)
        val still = LatestFrameProvider.snapshot()
        val info = buildString {
            appendLine("Incident ID: $id")
            appendLine("Wall-clock start (epoch millis): $at")
            appendLine("Trigger elapsedRealtimeNanos: $triggerElapsedNanos")
            appendLine("Last progress reason: ${status.lastProgressReason}")
            appendLine("Last progress age ms: ${status.ageMs}")
            appendLine("Overdue: ${status.overdue}")
            appendLine("Bot diagnostics: $bot")
            appendLine("Capture diagnostics: $capture")
            appendLine("Video: 30s prelude + recovery + 10s; hard cap 3 minutes; unavailable if capture/encoder stops.")
            appendLine("Physical coordinates in recovery logs refer to the contemporaneous transform.")
        }
        executor.execute {
            runCatching {
                directory.mkdirs()
                File(directory, "incident_status.txt").writeText(info)
                if (still != null) {
                    try {
                        File(directory, "watchdog_screenshot.png").writeBytes(
                            TelegramScreenshotEncoder.encodeNormalizedPng(still.mat),
                        )
                    }
                }
                OvernightLogArchive.copyWindow(
                    at - OvernightLogPolicy.BEFORE_INCIDENT_MS,
                    at,
                    File(directory, "before_30_minutes.log"),
                )
                cleanup(context, at)
            }.onFailure { error ->
                OvernightLogArchive.log("[INCIDENT] snapshot_failed type=${error.javaClass.simpleName}")
            }
            still?.mat?.release()
        }
        OvernightLogArchive.log("[INCIDENT] created=$id")
    }

    /** Called only for recovery-tagged messages on the Recovery worker; event order is preserved. */
    fun recoveryLine(message: String) {
        if (!message.startsWith("[GAME_PROGRESS_")) return
        val incident = active ?: return
        val at = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        executor.execute {
            runCatching {
                incident.directory.mkdirs()
                File(incident.directory, "watchdog_action_timeline.log").appendText(
                    "[$at][elapsedNanos=$elapsedNanos] ${OvernightLogPolicy.sanitized(message)}\n",
                )
            }
        }
    }

    fun finish(generation: Long, outcome: String) {
        val incident = synchronized(lock) {
            val item = active
            if (item == null || item.generation != generation) return
            active = null
            item
        }
        val at = System.currentTimeMillis()
        executor.execute {
            runCatching {
                incident.directory.mkdirs()
                File(incident.directory, "recovery_result.txt").writeText(
                    "Incident ID: ${incident.id}\nRecovery finished (epoch millis): $at\n" +
                        "Outcome: ${OvernightLogPolicy.sanitized(outcome)}\n" +
                        "Video closes 10 seconds after this, or at the 3-minute hard limit.\n",
                )
            }
        }
        IncidentVideoRecorder.finish(incident.id, SystemClock.elapsedRealtimeNanos())
        OvernightLogArchive.log("[INCIDENT] recovery_finished=${incident.id} outcome=$outcome")
    }

    fun recentIncidents(context: Context): List<File> {
        val now = System.currentTimeMillis()
        return File(context.filesDir, "diagnostics/incidents").listFiles()
            ?.filter { it.isDirectory && incidentTimestamp(it)?.let { at ->
                at <= now && now - at <= OvernightLogPolicy.INCIDENT_RETENTION_MS
            } == true }
            ?.sortedByDescending(::incidentTimestamp) ?: emptyList()
    }

    private fun incidentTimestamp(directory: File): Long? =
        directory.name.removePrefix("incident_").substringBefore('_').toLongOrNull()

    private fun cleanup(context: Context, now: Long) {
        val root = File(context.filesDir, "diagnostics/incidents")
        var directories = root.listFiles()?.filter { it.isDirectory }?.sortedBy(::incidentTimestamp) ?: return
        for (dir in directories) {
            val createdAt = incidentTimestamp(dir) ?: 0L
            if (createdAt > now || now - createdAt > OvernightLogPolicy.INCIDENT_RETENTION_MS) {
                if (dir != active?.directory) dir.deleteRecursively()
            }
        }
        directories = root.listFiles()?.filter { it.isDirectory }?.sortedBy(::incidentTimestamp) ?: return
        fun size(file: File): Long =
            if (file.isFile) file.length() else file.listFiles()?.sumOf(::size) ?: 0L
        var remaining = directories.sumOf(::size)
        for (dir in directories) {
            if (remaining <= OvernightLogPolicy.MAX_INCIDENT_BYTES) break
            if (dir == active?.directory) continue
            val bytes = size(dir)
            if (dir.deleteRecursively()) remaining -= bytes
        }
    }
}
