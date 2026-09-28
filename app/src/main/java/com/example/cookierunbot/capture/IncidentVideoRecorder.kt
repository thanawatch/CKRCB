package com.example.cookierunbot.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
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
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Best-effort, event-triggered recorder. ARMED during normal capture, but does NO frame
 * sampling, JPEG processing or encoding until a real/manual Watchdog Recovery begins.
 * Captures up to 3 minutes from trigger at target 2 FPS / 640x360, then optionally encodes
 * on a lowest-priority worker. Drops/skips video under capture/resource pressure; NEVER
 * waits for or modifies detection, gameplay, gestures, watchdog timing or Recovery.
 * Uses the existing LatestFrameProvider only (no second MediaProjection/VirtualDisplay).
 */
object IncidentVideoRecorder {
    private const val FPS = 2
    private const val WIDTH = 640
    private const val HEIGHT = 360
    private const val JPEG_QUALITY = 40
    private const val BIT_RATE = 250_000
    private const val SAMPLE_INTERVAL_MS = 1_000L / FPS
    private const val MAX_EVENT_BYTES = 20L * 1024L * 1024L
    private const val MAX_FRAME_AGE_MS = 1_500.0
    private const val SLOW_SAMPLE_MS = 250L
    private const val MAX_CONSECUTIVE_DEGRADED = 10
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    private data class Frame(val atNanos: Long, val jpeg: ByteArray)
    private data class Trigger(val id: String, val output: File, val atNanos: Long)
    private data class Finish(val id: String, val atNanos: Long)
    private data class Recording(
        val trigger: Trigger,
        val frames: File,
        var stopAtNanos: Long,
        var savedBytes: Long = 0L,
        var frameCount: Int = 0,
        var truncated: Boolean = false,
        var lastGeneration: Long = -1L,
        var consecutiveDegraded: Int = 0,
        var skippedSamples: Int = 0,
    )
    private val lock = Any()
    private val sampler = Executors.newSingleThreadScheduledExecutor { work ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST) }
            work.run()
        }, "incident-video-sampler").apply { priority = Thread.MIN_PRIORITY }
    }
    private val encoder = Executors.newSingleThreadExecutor { work ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST) }
            work.run()
        }, "incident-video-encoder").apply { priority = Thread.MIN_PRIORITY }
    }
    private var future: ScheduledFuture<*>? = null
    @Volatile private var active: Recording? = null // written on sampler thread
    private var pendingTrigger: Trigger? = null
    private var pendingFinish: Finish? = null
    @Volatile private var running = false
    @Volatile private var enabled = true

    fun start(context: Context) {
        synchronized(lock) {
            if (running) return
            enabled = context.getSharedPreferences("robotrun_diagnostics", Context.MODE_PRIVATE)
                .getBoolean("incident_video_enabled", true)
            if (!enabled) {
                OvernightLogArchive.log("[INCIDENT_VIDEO] enabled=false")
                return
            }
            // Arm only: zero recurring work during normal gameplay.
            running = true
            OvernightLogArchive.log("[INCIDENT_VIDEO] armed=true mode=event_triggered")
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

    fun trigger(id: String, output: File, atNanos: Long) {
        synchronized(lock) {
            if (!running || pendingTrigger != null || active != null) {
                sampler.execute {
                    writeStatus(output, "Recording skipped: disabled, capture stopped or another incident active.")
                }
                return
            }
            pendingTrigger = Trigger(id, output, atNanos)
            // The ONLY place that starts periodic sampling, after Watchdog triggers.
            if (future == null || future?.isCancelled == true) {
                future = sampler.scheduleWithFixedDelay(::tick, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun finish(id: String, atNanos: Long) {
        synchronized(lock) { pendingFinish = Finish(id, atNanos) }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            future?.cancel(false)
            future = null
        }
        sampler.execute {
            val pending = synchronized(lock) { pendingTrigger.also { pendingTrigger = null } }
            pending?.let { writeStatus(it.output, "Capture stopped before video trigger.") }
            active?.let { finalizeRecording(it, "capture_stopped") }
            active = null
        }
    }

    private fun tick() {
        if (!running) return
        try {
            val next = synchronized(lock) { pendingTrigger.also { pendingTrigger = null } }
            if (next != null) startRecording(next)
            val recording = active ?: run { disarmSamplerIfIdle(); return }
            val finish = synchronized(lock) {
                pendingFinish?.takeIf { it.id == recording.trigger.id }?.also { pendingFinish = null }
            }
            if (finish != null) {
                recording.stopAtNanos = IncidentVideoTiming.finalStopAt(
                    recording.trigger.atNanos, finish.atNanos,
                )
            }
            if (SystemClock.elapsedRealtimeNanos() >= recording.stopAtNanos) {
                finalizeRecording(recording, "completed")
                active = null
                disarmSamplerIfIdle()
                return
            }

            // Avoid cloning the latest frame if capture is already unhealthy.
            val health = LatestFrameProvider.diagnostics()
            if (!health.captureRunning || health.frameAgeMs == null ||
                health.frameAgeMs > MAX_FRAME_AGE_MS ||
                health.frameSequence <= recording.lastGeneration
            ) {
                recording.skippedSamples++
                recording.consecutiveDegraded++
            } else {
                val startedMs = SystemClock.elapsedRealtime()
                val snapshot = LatestFrameProvider.snapshot()
                if (snapshot == null) {
                    recording.skippedSamples++
                    recording.consecutiveDegraded++
                } else {
                    try {
                        if (snapshot.generation > recording.lastGeneration) {
                            recording.lastGeneration = snapshot.generation
                            val downsampled = Mat()
                            val bgr = Mat()
                            val jpeg = MatOfByte()
                            val options = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
                            try {
                                Imgproc.resize(snapshot.mat, downsampled,
                                    Size(WIDTH.toDouble(), HEIGHT.toDouble()),
                                    0.0, 0.0, Imgproc.INTER_AREA)
                                Imgproc.cvtColor(downsampled, bgr, Imgproc.COLOR_RGBA2BGR)
                                if (Imgcodecs.imencode(".jpg", bgr, jpeg, options)) {
                                    val sampledAt = SystemClock.elapsedRealtimeNanos()
                                    if (sampledAt <= recording.stopAtNanos) {
                                        persistFrame(recording, Frame(sampledAt, jpeg.toArray()))
                                    }
                                } else recording.skippedSamples++
                            } finally {
                                options.release(); jpeg.release(); bgr.release(); downsampled.release()
                            }
                        }
                    } finally { snapshot.mat.release() }
                    val elapsedMs = SystemClock.elapsedRealtime() - startedMs
                    if (elapsedMs > SLOW_SAMPLE_MS) {
                        recording.skippedSamples++
                        recording.consecutiveDegraded++
                    } else {
                        recording.consecutiveDegraded = 0
                    }
                }
            }
            if (recording.truncated || recording.consecutiveDegraded >= MAX_CONSECUTIVE_DEGRADED) {
                finalizeRecording(recording, if (recording.truncated) "size_cap" else "resource_pressure")
                active = null
                disarmSamplerIfIdle()
            }
        } catch (error: Exception) {
            active?.let { writeStatus(it.trigger.output,
                "Video skipped after sampling error: ${error.javaClass.simpleName}; available JPEGs retained.") }
            active = null
            disarmSamplerIfIdle()
            OvernightLogArchive.log("[INCIDENT_VIDEO] sampling_failed type=${error.javaClass.simpleName}")
        }
    }

    private fun disarmSamplerIfIdle() {
        synchronized(lock) {
            if (active == null && pendingTrigger == null) {
                future?.cancel(false)
                future = null
            }
        }
    }

    private fun startRecording(trigger: Trigger) {
        if (active != null) {
            writeStatus(trigger.output, "Recording skipped: previous incident is still active.")
            return
        }
        val recording = Recording(
            trigger,
            File(trigger.output.parentFile, "video_frames").apply { mkdirs() },
            IncidentVideoTiming.finalStopAt(trigger.atNanos, Long.MAX_VALUE),
        )
        writeStatus(trigger.output, "Recording in progress: event-triggered 640x360/2fps; recovery + 10s; cap=3min after trigger. Best effort, frames may be skipped.")
        active = recording
        OvernightLogArchive.log("[INCIDENT_VIDEO] started id=${trigger.id} preRollFrames=0")
    }

    private fun persistFrame(recording: Recording, frame: Frame) {
        if (recording.savedBytes + frame.jpeg.size > MAX_EVENT_BYTES) {
            recording.truncated = true
            return
        }
        val name = "%020d.jpg".format(java.util.Locale.US, frame.atNanos)
        File(recording.frames, name).writeBytes(frame.jpeg)
        recording.savedBytes += frame.jpeg.size
        recording.frameCount++
    }

    private fun finalizeRecording(recording: Recording, reason: String) {
        writeStatus(recording.trigger.output,
            "Recording ended ($reason), frames=${recording.frameCount}, skipped=${recording.skippedSamples}.")
        runCatching {
            val files = recording.frames.listFiles()
                ?.filter { it.isFile && it.extension == "jpg" }?.sortedBy { it.name }
                ?: emptyList()
            val firstAt = files.firstOrNull()?.nameWithoutExtension?.toLongOrNull()
            val lastAt = files.lastOrNull()?.nameWithoutExtension?.toLongOrNull()
            File(recording.trigger.output.parentFile, "video_timing.txt").writeText(
                "Incident ID: ${recording.trigger.id}\n" +
                    "Video trigger elapsedRealtimeNanos: ${recording.trigger.atNanos}\n" +
                    "First frame elapsedRealtimeNanos: ${firstAt ?: "none"}\n" +
                    "Last frame elapsedRealtimeNanos: ${lastAt ?: "none"}\n" +
                    "Original JPEG filename is the sample's elapsedRealtimeNanos. " +
                    "MP4 PTS starts at the first JPEG. Compare watchdog_action_timeline.log elapsedNanos " +
                    "to this file to align each action with the video.\n",
            )
        }
        if (reason == "resource_pressure" || recording.frameCount == 0) {
            writeStatus(recording.trigger.output,
                "Video skipped: $reason; frames=${recording.frameCount}, skipped=${recording.skippedSamples}; available JPEGs retained, MP4 encoding not started.")
            OvernightLogArchive.log("[INCIDENT_VIDEO] skipped id=${recording.trigger.id} reason=$reason")
            return
        }
        encoder.execute {
            val target = recording.trigger.output
            try {
                if (captureUnderPressure()) {
                    writeStatus(target, "MP4 skipped: capture under pressure; original JPEGs retained.")
                    OvernightLogArchive.log("[INCIDENT_VIDEO] encoding_skipped id=${recording.trigger.id} reason=capture_pressure")
                    return@execute
                }
                encodeMp4(recording.frames, target)
                writeStatus(target, "MP4 complete; frames=${recording.frameCount}; reason=$reason")
                recording.frames.deleteRecursively()
                OvernightLogArchive.log("[INCIDENT_VIDEO] complete id=${recording.trigger.id} bytes=${target.length()}")
            } catch (error: Exception) {
                // Original JPEG evidence remains on disk if this device's encoder is unsupported.
                target.delete()
                writeStatus(target, "MP4 encoder failed: ${error.javaClass.simpleName}; original JPEGs retained.")
                OvernightLogArchive.log("[INCIDENT_VIDEO] encoding_failed type=${error.javaClass.simpleName}")
            }
        }
    }

    private fun writeStatus(target: File, message: String) {
        runCatching {
            target.parentFile?.mkdirs()
            File(target.parentFile, "incident_video_status.txt").writeText(message + "\n")
        }
    }

    /** Capture health is a conservative pressure signal, not a measurement of Redfinger's total CPU. */
    private fun captureUnderPressure(): Boolean {
        val health = LatestFrameProvider.diagnostics()
        return health.captureRunning &&
            (health.frameAgeMs == null || health.frameAgeMs > MAX_FRAME_AGE_MS)
    }

    private fun encodeMp4(frames: File, destination: File) {
        val sources = frames.listFiles { file -> file.isFile && file.extension == "jpg" }
            ?.sortedBy { it.nameWithoutExtension.toLongOrNull() ?: 0L } ?: emptyList()
        require(sources.isNotEmpty()) { "No video frames available." }
        val firstAt = sources.first().nameWithoutExtension.toLong()
        val codec = MediaCodec.createEncoderByType(MIME)
        val temporary = File(destination.parentFile, "${destination.name}.part")
        var codecStarted = false
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true
            muxer = MediaMuxer(temporary.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var queued = 0
            var sentEnd = false
            var outputEnded = false
            var loopsWithoutOutput = 0
            var lastPresentationUs = -1L
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                // Encoding is expendable. Stop if it coincides with capture starvation.
                check(!captureUnderPressure()) { "Capture under pressure; skip MP4." }
                if (!sentEnd) {
                    val index = codec.dequeueInputBuffer(5_000L)
                    if (index >= 0) {
                        if (queued < sources.size) {
                            val source = sources[queued]
                            val jpeg = MatOfByte(*source.readBytes())
                            val bgr = Imgcodecs.imdecode(jpeg, Imgcodecs.IMREAD_COLOR)
                            val rgba = Mat()
                            val yuv = Mat()
                            try {
                                require(!bgr.empty() && bgr.cols() == WIDTH && bgr.rows() == HEIGHT)
                                Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA)
                                Imgproc.cvtColor(rgba, yuv, Imgproc.COLOR_RGBA2YUV_I420)
                                val planar = ByteArray(WIDTH * HEIGHT * 3 / 2)
                                yuv.get(0, 0, planar)
                                val image = requireNotNull(codec.getInputImage(index)) {
                                    "Encoder does not expose flexible YUV input."
                                }
                                try { fillYuv420Image(image, planar) } finally { image.close() }
                                val originalUs = source.nameWithoutExtension.toLong() / 1000L
                                val pts = (originalUs - firstAt / 1000L).coerceAtLeast(lastPresentationUs + 1L)
                                lastPresentationUs = pts
                                codec.queueInputBuffer(index, 0, planar.size, pts, 0)
                                queued++
                            } finally {
                                yuv.release(); rgba.release(); bgr.release(); jpeg.release()
                            }
                        } else {
                            codec.queueInputBuffer(index, 0, 0, lastPresentationUs + 1L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sentEnd = true
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000L)
                when (output) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted)
                        track = requireNotNull(muxer).addTrack(codec.outputFormat)
                        requireNotNull(muxer).start()
                        muxerStarted = true
                        loopsWithoutOutput = 0
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        loopsWithoutOutput++
                        check(loopsWithoutOutput < 2_000) { "Video encoder did not finish." }
                    }
                    else -> if (output >= 0) {
                        loopsWithoutOutput = 0
                        val data = codec.getOutputBuffer(output)
                        if (data != null && info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                            muxerStarted
                        ) {
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            requireNotNull(muxer).writeSampleData(track, data, info)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(output, false)
                    }
                }
            }
            check(muxerStarted)
            requireNotNull(muxer).stop()
            muxerStarted = false
            requireNotNull(muxer).release()
            muxer = null
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            require(destination.isFile && destination.length() > 0L)
        } finally {
            if (codecStarted) runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            temporary.delete()
        }
    }

    private fun fillYuv420Image(image: android.media.Image, planar: ByteArray) {
        val ySize = WIDTH * HEIGHT
        val uvSize = ySize / 4
        require(image.planes.size == 3)
        for (planeIndex in 0..2) {
            val plane = image.planes[planeIndex]
            val planeWidth = if (planeIndex == 0) WIDTH else WIDTH / 2
            val planeHeight = if (planeIndex == 0) HEIGHT else HEIGHT / 2
            val offset = when (planeIndex) { 0 -> 0; 1 -> ySize; else -> ySize + uvSize }
            val target = plane.buffer
            for (row in 0 until planeHeight) {
                for (column in 0 until planeWidth) {
                    target.put(row * plane.rowStride + column * plane.pixelStride,
                        planar[offset + row * planeWidth + column])
                }
            }
        }
    }
}
