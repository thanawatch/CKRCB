package com.example.cookierunbot.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
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
 * Opt-in independent 3 FPS normalized-frame sampler. Keeps the last 30s as compressed JPEGs
 * in a bounded ring; when a real watchdog incident starts, persists those frames and samples
 * through recovery + 10s (up to 3 minutes from trigger). Only AFTER recording stops does a
 * separate worker encode MP4 (H.264 MediaCodec + MediaMuxer). No new MediaProjection or VD.
 * Nothing in this object taps, controls, waits for, or changes the detector/game/watchdog timer.
 */
object IncidentVideoRecorder {
    private const val FPS = 3
    private const val WIDTH = 960
    private const val HEIGHT = 540
    private const val SAMPLE_INTERVAL_MS = 1_000L / FPS
    private const val MAX_RING_BYTES = 12L * 1024L * 1024L
    private const val MAX_EVENT_BYTES = 45L * 1024L * 1024L
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
    )
    private val lock = Any()
    private val sampler = Executors.newSingleThreadScheduledExecutor { work ->
        Thread(work, "incident-video-sampler").apply { priority = Thread.MIN_PRIORITY }
    }
    private val encoder = Executors.newSingleThreadExecutor { work ->
        Thread(work, "incident-video-encoder").apply { priority = Thread.MIN_PRIORITY }
    }
    private var future: ScheduledFuture<*>? = null
    private var ring = ArrayDeque<Frame>()
    private var ringBytes = 0L
    private var active: Recording? = null // sampler thread only
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
            running = true
            future = sampler.scheduleWithFixedDelay(::tick, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS)
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
            if (!running || pendingTrigger != null) {
                writeStatus(output, "Recording unavailable: capture/recorder stopped or another incident pending.")
                return
            }
            pendingTrigger = Trigger(id, output, atNanos)
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
            pendingTrigger?.let { writeStatus(it.output, "Capture stopped before video trigger.") }
            pendingTrigger = null
            active?.let { finalizeRecording(it, "capture_stopped") }
            active = null
            ring.clear()
            ringBytes = 0L
        }
    }

    private fun tick() {
        if (!running) return
        try {
            val snapshot = LatestFrameProvider.snapshot()
            val frame = snapshot?.let { source ->
                try {
                    val downsampled = Mat()
                    val bgr = Mat()
                    val jpeg = MatOfByte()
                    val options = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 55)
                    try {
                        Imgproc.resize(source.mat, downsampled, Size(WIDTH.toDouble(), HEIGHT.toDouble()),
                            0.0, 0.0, Imgproc.INTER_AREA)
                        Imgproc.cvtColor(downsampled, bgr, Imgproc.COLOR_RGBA2BGR)
                        if (Imgcodecs.imencode(".jpg", bgr, jpeg, options)) {
                            Frame(SystemClock.elapsedRealtimeNanos(), jpeg.toArray())
                        } else null
                    } finally {
                        options.release(); jpeg.release(); bgr.release(); downsampled.release()
                    }
                } finally { source.mat.release() }
            }
            if (frame != null) {
                ring.addLast(frame)
                ringBytes += frame.jpeg.size
                while (ring.isNotEmpty() &&
                    (frame.atNanos - ring.first().atNanos > IncidentVideoTiming.PRELUDE_NANOS + 1_000_000_000L ||
                        ringBytes > MAX_RING_BYTES)
                ) ringBytes -= ring.removeFirst().jpeg.size
            }
            val next = synchronized(lock) { pendingTrigger.also { pendingTrigger = null } }
            if (next != null) startRecording(next)
            val recording = active
            if (recording != null) {
                val finish = synchronized(lock) {
                    pendingFinish?.takeIf { it.id == recording.trigger.id }?.also { pendingFinish = null }
                }
                if (finish != null) {
                    recording.stopAtNanos = IncidentVideoTiming.finalStopAt(
                        recording.trigger.atNanos, finish.atNanos,
                    )
                }
                val now = SystemClock.elapsedRealtimeNanos()
                if (frame != null && frame.atNanos > recording.trigger.atNanos &&
                    frame.atNanos <= recording.stopAtNanos
                ) persistFrame(recording, frame)
                if (now >= recording.stopAtNanos || recording.truncated) {
                    finalizeRecording(recording, if (recording.truncated) "size_cap" else "completed")
                    active = null
                }
            }
        } catch (error: Exception) {
            active?.let {
                writeStatus(it.trigger.output, "Video recording failed: ${error.javaClass.simpleName}")
                // Keep captured frame files for a later diagnostic, but do not interrupt capture.
            }
            active = null
            OvernightLogArchive.log("[INCIDENT_VIDEO] sampling_failed type=${error.javaClass.simpleName}")
        }
    }

    private fun startRecording(trigger: Trigger) {
        if (active != null) {
            writeStatus(trigger.output, "Recording unavailable: previous incident is still active.")
            return
        }
        val recording = Recording(
            trigger,
            File(trigger.output.parentFile, "video_frames").apply { mkdirs() },
            trigger.atNanos + IncidentVideoTiming.MAX_VIDEO_NANOS,
        )
        writeStatus(trigger.output, "Recording in progress; 30s pre-roll + recovery + 10s; cap=3min.")
        ring.filter { it.atNanos >= trigger.atNanos - IncidentVideoTiming.PRELUDE_NANOS &&
            it.atNanos <= trigger.atNanos
        }.forEach { persistFrame(recording, it) }
        active = recording
        OvernightLogArchive.log("[INCIDENT_VIDEO] started id=${trigger.id} preRollFrames=${recording.frameCount}")
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
            "Recording ended ($reason), frames=${recording.frameCount}; encoding MP4 in background.")
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
        encoder.execute {
            val target = recording.trigger.output
            try {
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
                setInteger(MediaFormat.KEY_BIT_RATE, 450_000)
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
