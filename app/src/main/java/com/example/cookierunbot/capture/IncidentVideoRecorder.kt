package com.example.cookierunbot.capture

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.example.cookierunbot.diagnostics.OvernightLogArchive
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Legacy internal name retained for existing capture/Incident callers and preference migration.
 * This is now an optional JPEG SEQUENCE recorder: NO MP4 codec, muxer or second capture session.
 * ARMED during gameplay with zero recurring work. Only real/manual Incident triggers start a
 * lowest-priority sampler. Its work is disposable and is never awaited by Recovery or detection.
 */
object IncidentVideoRecorder {
    private const val WIDTH = 480
    private const val HEIGHT = 270
    private const val JPEG_QUALITY = 35
    private const val SAMPLE_INTERVAL_MS = 2_000L
    private const val MAX_IMAGES = 90
    private const val MAX_EVENT_BYTES = 20L * 1024L * 1024L
    private const val MAX_FRAME_AGE_MS = 1_500.0

    private data class Trigger(val id: String, val directory: File, val atNanos: Long, val session: Long)
    private data class Finish(val id: String, val atNanos: Long)
    private data class Recording(
        val trigger: Trigger,
        val frames: File,
        var stopAtNanos: Long,
        var savedBytes: Long = 0L,
        var sizeCapped: Boolean = false,
        var imageCount: Int = 0,
        var lastGeneration: Long = -1L,
        var degradedTicks: Int = 0,
        var skippedSamples: Int = 0,
        var expensiveSamples: Int = 0,
        var backoffTicks: Int = 0,
        val cpuWindow: CpuWindow = CpuWindow(),
    )

    /** App process CPU, not cloud VM CPU. Only the optional sampler uses this signal. */
    private class CpuWindow {
        private var wallStartMs = -1L
        private var cpuStartMs = -1L
        private var busy = false

        fun isBusy(): Boolean {
            val wallMs = SystemClock.elapsedRealtime()
            val cpuMs = Process.getElapsedCpuTime()
            if (wallStartMs < 0L || wallMs < wallStartMs || cpuMs < cpuStartMs) {
                wallStartMs = wallMs
                cpuStartMs = cpuMs
                busy = false
            } else if (wallMs - wallStartMs >= IncidentVideoLoadPolicy.CPU_WINDOW_MS) {
                busy = IncidentVideoLoadPolicy.processCpuTooBusy(cpuMs - cpuStartMs, wallMs - wallStartMs)
                wallStartMs = wallMs
                cpuStartMs = cpuMs
            }
            return busy
        }
    }

    private val lock = Any()
    private val sampler = Executors.newSingleThreadScheduledExecutor { work ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST) }
            work.run()
        }, "incident-image-sampler").apply { priority = Thread.MIN_PRIORITY }
    }
    private var future: ScheduledFuture<*>? = null
    @Volatile private var active: Recording? = null // sampler worker owns mutations
    private var pendingTrigger: Trigger? = null
    private var pendingFinish: Finish? = null
    @Volatile private var running = false
    @Volatile private var sessionId = 0L

    fun start(context: Context) {
        synchronized(lock) {
            if (running) return
            // Preserve the old setting when upgrading from optional incident video.
            val enabled = context.getSharedPreferences("robotrun_diagnostics", Context.MODE_PRIVATE)
                .getBoolean("incident_video_enabled", true)
            if (!enabled) {
                OvernightLogArchive.log("[INCIDENT_IMAGES] enabled=false")
                return
            }
            sessionId++
            running = true
            OvernightLogArchive.log("[INCIDENT_IMAGES] armed=true mode=event_triggered")
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("robotrun_diagnostics", Context.MODE_PRIVATE)
            .edit().putBoolean("incident_video_enabled", value).apply()
        if (value) {
            if (LatestFrameProvider.diagnostics().captureRunning) start(context)
        } else stop()
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("robotrun_diagnostics", Context.MODE_PRIVATE)
            .getBoolean("incident_video_enabled", true)

    fun trigger(id: String, directory: File, atNanos: Long) {
        synchronized(lock) {
            if (!running || pendingTrigger != null || active != null) {
                sampler.execute {
                    writeStatus(directory, "Images skipped: disabled, capture stopped or another incident active.")
                }
                return
            }
            val session = sessionId
            pendingTrigger = Trigger(id, directory, atNanos, session)
            // No periodic sampler during normal gameplay.
            if (future == null || future?.isDone == true) {
                future = sampler.scheduleWithFixedDelay({ tick(session) }, 0,
                    SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun finish(id: String, atNanos: Long) {
        synchronized(lock) {
            if (pendingTrigger?.id == id || active?.trigger?.id == id) {
                pendingFinish = Finish(id, atNanos)
            }
        }
    }

    fun stop() {
        val (oldSession, pending) = synchronized(lock) {
            val oldSession = sessionId
            sessionId++ // Old queued sampler cannot consume the new session's trigger.
            running = false
            future?.cancel(false)
            future = null
            val pending = pendingTrigger
            pendingTrigger = null
            pendingFinish = null
            oldSession to pending
        }
        sampler.execute {
            pending?.let { writeStatus(it.directory, "Images skipped: capture stopped before trigger.") }
            active?.takeIf { it.trigger.session == oldSession }?.let {
                active = null
                finishRecording(it, "capture_stopped")
            }
        }
    }

    private fun tick(session: Long) {
        if (!running || sessionId != session) return
        try {
            val trigger = synchronized(lock) {
                pendingTrigger?.takeIf { it.session == session }?.also { pendingTrigger = null }
            }
            if (trigger != null) startRecording(trigger)
            val recording = active?.takeIf { it.trigger.session == session }
                ?: run { disarmIfIdle(session); return }

            synchronized(lock) {
                pendingFinish?.takeIf { it.id == recording.trigger.id }?.also {
                    recording.stopAtNanos = IncidentVideoTiming.finalStopAt(
                        recording.trigger.atNanos, it.atNanos)
                    pendingFinish = null
                }
            }
            if (SystemClock.elapsedRealtimeNanos() >= recording.stopAtNanos) {
                finishRecording(recording, "completed")
                active = null
                disarmIfIdle(session)
                return
            }
            if (!running || sessionId != session) return

            // Skip first. Only clone the normalized Mat when the capture has spare headroom.
            val health = LatestFrameProvider.diagnostics()
            val reason = when {
                recording.backoffTicks > 0 -> {
                    recording.backoffTicks--
                    "sample_backoff"
                }
                !health.captureRunning -> "capture_stopped"
                health.frameAgeMs == null || health.frameAgeMs > MAX_FRAME_AGE_MS -> "stale_capture"
                IncidentVideoLoadPolicy.captureTooSlow(health.captureFps, 1) -> "low_capture_fps"
                health.frameSequence <= recording.lastGeneration -> "repeated_frame"
                recording.cpuWindow.isBusy() -> "process_cpu_pressure"
                else -> null
            }
            if (reason != null) {
                recording.skippedSamples++
                recording.degradedTicks++
                if (recording.degradedTicks == 1 || recording.degradedTicks % 5 == 0) {
                    OvernightLogArchive.log(
                        "[INCIDENT_IMAGES] sample_skipped id=${recording.trigger.id} reason=$reason " +
                            "degraded=${recording.degradedTicks} captureFps=${health.captureFps} " +
                            "frameAgeMs=${health.frameAgeMs}"
                    )
                }
            } else {
                sample(recording, session)
            }

            val stopReason = when {
                recording.imageCount >= MAX_IMAGES -> "image_limit"
                recording.sizeCapped || recording.savedBytes >= MAX_EVENT_BYTES -> "size_cap"
                IncidentVideoLoadPolicy.shouldStopSampling(recording.degradedTicks) -> "sustained_resource_pressure"
                else -> null
            }
            if (stopReason != null) {
                finishRecording(recording, stopReason)
                active = null
                disarmIfIdle(session)
            }
        } catch (error: Exception) {
            active?.takeIf { it.trigger.session == session }?.let {
                finishRecording(it, "sampling_error_${error.javaClass.simpleName}")
                active = null
            }
            disarmIfIdle(session)
            OvernightLogArchive.log("[INCIDENT_IMAGES] sampling_failed type=${error.javaClass.simpleName}")
        }
    }

    private fun sample(recording: Recording, session: Long) {
        val startedMs = SystemClock.elapsedRealtime()
        val startedCpu = Debug.threadCpuTimeNanos()
        val snapshot = LatestFrameProvider.snapshot()
        if (snapshot == null) {
            recording.skippedSamples++
            recording.degradedTicks++
            return
        }
        var saved = false
        try {
            if (snapshot.generation <= recording.lastGeneration) return
            recording.lastGeneration = snapshot.generation
            val resized = Mat()
            val bgr = Mat()
            val jpeg = MatOfByte()
            val options = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
            try {
                Imgproc.resize(snapshot.mat, resized, Size(WIDTH.toDouble(), HEIGHT.toDouble()),
                    0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.cvtColor(resized, bgr, Imgproc.COLOR_RGBA2BGR)
                if (Imgcodecs.imencode(".jpg", bgr, jpeg, options)) {
                    val capturedAt = SystemClock.elapsedRealtimeNanos()
                    if (running && sessionId == session && capturedAt < recording.stopAtNanos) {
                        saved = saveImage(recording, jpeg.toArray(), capturedAt, snapshot.generation)
                    }
                }
            } finally {
                options.release()
                jpeg.release()
                bgr.release()
                resized.release()
            }
        } finally {
            snapshot.mat.release()
            val elapsedMs = SystemClock.elapsedRealtime() - startedMs
            val finishedCpu = Debug.threadCpuTimeNanos()
            val cpuNanos = if (startedCpu >= 0L && finishedCpu >= startedCpu) {
                finishedCpu - startedCpu
            } else -1L
            val expensive = IncidentVideoLoadPolicy.sampleTooExpensive(elapsedMs, cpuNanos)
            if (expensive) {
                recording.expensiveSamples++
                recording.backoffTicks = 1 // Yield a full 2-second interval, never retry immediately.
            }
            if (!saved) recording.skippedSamples++
            recording.degradedTicks = if (!saved || expensive) recording.degradedTicks + 1 else 0
            OvernightLogArchive.log(
                "[INCIDENT_IMAGES] sample id=${recording.trigger.id} saved=$saved " +
                    "wallMs=$elapsedMs threadCpuNanos=$cpuNanos expensive=$expensive " +
                    "degraded=${recording.degradedTicks} images=${recording.imageCount}"
            )
        }
    }

    private fun saveImage(recording: Recording, bytes: ByteArray, atNanos: Long, generation: Long): Boolean {
        if (recording.imageCount >= MAX_IMAGES) return false
        if (recording.savedBytes + bytes.size > MAX_EVENT_BYTES) {
            recording.sizeCapped = true
            return false
        }
        val name = "%06d.jpg".format(Locale.US, recording.imageCount + 1)
        val image = File(recording.frames, name)
        val temp = File(recording.frames, "$name.part")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(image)) throw IOException("Unable to publish JPEG")
            recording.imageCount++
            recording.savedBytes += bytes.size
            File(recording.trigger.directory, "images_timing.txt").appendText(
                "$name elapsedNanos=$atNanos frameGeneration=$generation bytes=${bytes.size}\n",
                Charsets.UTF_8,
            )
            return true
        } finally {
            temp.delete()
        }
    }

    private fun startRecording(trigger: Trigger) {
        if (!running || trigger.session != sessionId) {
            writeStatus(trigger.directory, "Images skipped: capture session changed before start.")
            return
        }
        if (active != null) {
            writeStatus(trigger.directory, "Images skipped: previous incident still active.")
            return
        }
        val health = LatestFrameProvider.diagnostics()
        if (!health.captureRunning || health.frameAgeMs == null ||
            health.frameAgeMs > MAX_FRAME_AGE_MS ||
            IncidentVideoLoadPolicy.captureTooSlow(health.captureFps, 1)
        ) {
            writeStatus(trigger.directory, "Images skipped: capture unhealthy or FPS below 2 at trigger.")
            OvernightLogArchive.log("[INCIDENT_IMAGES] skipped id=${trigger.id} reason=capture_unhealthy_at_trigger")
            return
        }
        val frames = File(trigger.directory, "screenshots").apply { mkdirs() }
        File(trigger.directory, "images_timing.txt").writeText(
            "Incident ID: ${trigger.id}\n" +
                "Trigger elapsedRealtimeNanos: ${trigger.atNanos}\n" +
                "JPEG filenames are ordered samples. Compare elapsedNanos with " +
                "watchdog_action_timeline.txt from an exported ZIP.\n",
            Charsets.UTF_8,
        )
        val recording = Recording(trigger, frames,
            IncidentVideoTiming.finalStopAt(trigger.atNanos, Long.MAX_VALUE))
        active = recording
        writeStatus(trigger.directory,
            "Images in progress: 480x270 JPEG quality 35, every 2s, up to 90; " +
                "Recovery + 10s or 3min from trigger. Best effort.")
        OvernightLogArchive.log("[INCIDENT_IMAGES] started id=${trigger.id} preroll=0")
    }

    private fun finishRecording(recording: Recording, reason: String) {
        writeStatus(recording.trigger.directory,
            "Images ended: reason=$reason; images=${recording.imageCount}; " +
                "skipped=${recording.skippedSamples}; expensive=${recording.expensiveSamples}; " +
                "bytes=${recording.savedBytes}; no MP4 encoding.\n")
        OvernightLogArchive.log(
            "[INCIDENT_IMAGES] ended id=${recording.trigger.id} reason=$reason " +
                "images=${recording.imageCount} skipped=${recording.skippedSamples} " +
                "expensive=${recording.expensiveSamples}"
        )
    }

    private fun writeStatus(directory: File, message: String) {
        runCatching {
            directory.mkdirs()
            File(directory, "incident_image_status.txt").writeText(message.trimEnd() + "\n")
        }
    }

    private fun disarmIfIdle(session: Long) {
        synchronized(lock) {
            if (sessionId == session && active == null && pendingTrigger == null) {
                future?.cancel(false)
                future = null
                pendingFinish = null
            }
        }
    }
}
