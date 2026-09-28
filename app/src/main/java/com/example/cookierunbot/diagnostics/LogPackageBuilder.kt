package com.example.cookierunbot.diagnostics

import android.content.Context
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A user-requested, private ZIP. Built on a separate worker; no capture or detector blocking. */
object LogPackageBuilder {
    private const val MAX_INCIDENTS = 5
    private const val VIDEO_RAW_BUDGET = 35L * 1024L * 1024L

    fun create(context: Context, maxBytes: Long = OvernightLogPolicy.TELEGRAM_MAX_BYTES): File {
        val now = System.currentTimeMillis()
        val staging = File(context.cacheDir, "overnight-log-export/${now}").apply { mkdirs() }
        val output = File(
            staging,
            "RobotRun-Logs-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))}.zip",
        )
        try {
            val log = File(staging, "robotrun_10h.log")
            OvernightLogArchive.copyWindow(now - OvernightLogPolicy.RETENTION_MS, now, log)
            val incidents = IncidentManager.recentIncidents(context).take(MAX_INCIDENTS)
            var videoBytes = 0L
            val omitted = mutableListOf<String>()
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                add(zip, log, "robotrun_10h.log")
                for (incident in incidents) {
                    val prefix = "incidents/${incident.name}/"
                    val files = incident.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
                        ?: emptyList()
                    for (file in files) {
                        if (file.name.endsWith(".part")) continue
                        if (file.extension.equals("mp4", ignoreCase = true)) {
                            if (file.length() <= 0L ||
                                videoBytes + file.length() > VIDEO_RAW_BUDGET
                            ) {
                                omitted.add("${incident.name}/${file.name}: video budget/unfinished")
                                continue
                            }
                            videoBytes += file.length()
                        }
                        add(zip, file, prefix + file.name)
                    }
                    // A process/encoder failure can leave original JPEG evidence instead of MP4.
                    if (!File(incident, "incident_video.mp4").isFile) {
                        val raw = File(incident, "video_frames").listFiles()
                            ?.filter { it.isFile && it.extension == "jpg" }?.sortedBy { it.name }
                            ?: emptyList()
                        for (frame in raw) {
                            if (videoBytes + frame.length() > VIDEO_RAW_BUDGET) {
                                omitted.add("${incident.name}/video_frames: exceeded video budget")
                                break
                            }
                            add(zip, frame, prefix + "video_frames/" + frame.name)
                            videoBytes += frame.length()
                        }
                    }
                }
                val manifest = buildString {
                    appendLine("RobotRun overnight diagnostic bundle")
                    appendLine("Window: epochMs ${now - OvernightLogPolicy.RETENTION_MS} .. $now")
                    appendLine("Archive: up to 10 hours or 200 MiB (whichever is reached first)")
                    appendLine("Incident retention: 72 hours, separate 200 MiB cap; newest $MAX_INCIDENTS included")
                    appendLine("Video: optional event-triggered 640x360/2fps, recovery + 10s, max 3min from trigger; may be skipped under load")
                    appendLine("Screenshot/video may contain visible app/game information. Keep this ZIP private.")
                    if (omitted.isNotEmpty()) {
                        appendLine("Omitted files:")
                        omitted.forEach { appendLine(it) }
                    }
                }
                zip.putNextEntry(ZipEntry("manifest.txt"))
                zip.write(manifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            if (output.length() !in 1L..maxBytes) {
                throw IOException("Log ZIP exceeds Telegram upload cap; use local export instead.")
            }
            return output
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        } finally {
            // ZIP can be kept until uploaded; the uncompressed 10h text is no longer needed.
            File(staging, "robotrun_10h.log").delete()
        }
    }

    private fun add(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip, 32 * 1024) }
        zip.closeEntry()
    }

    fun cleanup(output: File) {
        output.parentFile?.deleteRecursively()
    }
}
