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
    private const val OPTIONAL_MEDIA_BUDGET = 35L * 1024L * 1024L

    fun create(context: Context, maxBytes: Long = OvernightLogPolicy.TELEGRAM_MAX_BYTES): File {
        val now = System.currentTimeMillis()
        val staging = File(context.cacheDir, "overnight-log-export/${now}").apply { mkdirs() }
        val output = File(
            staging,
            "RobotRun-Logs-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))}.zip",
        )
        try {
            val log = File(staging, "robotrun_10h.txt")
            OvernightLogArchive.copyWindow(now - OvernightLogPolicy.RETENTION_MS, now, log)
            val incidents = IncidentManager.recentIncidents(context).take(MAX_INCIDENTS)
            var mediaBytes = 0L
            val omitted = mutableListOf<String>()
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                add(zip, log, "robotrun_10h.txt")
                for (incident in incidents) {
                    val prefix = "incidents/${incident.name}/"
                    val files = incident.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
                        ?: emptyList()
                    for (file in files) {
                        if (file.name.endsWith(".part")) continue
                        if (file.extension.equals("mp4", ignoreCase = true)) {
                            if (file.length() <= 0L ||
                                mediaBytes + file.length() > OPTIONAL_MEDIA_BUDGET
                            ) {
                                omitted.add("${incident.name}/${file.name}: optional media budget/unfinished")
                                continue
                            }
                            mediaBytes += file.length()
                        }
                        add(zip, file, prefix + OvernightLogPolicy.exportedFileName(file.name))
                    }
                    // New screenshot sequence is always exported; retain legacy JPEG fallback for
                    // older Incident packages. Never add partial .part files or exceed media budget.
                    val imageDirs = listOf("screenshots", "video_frames")
                    for (directoryName in imageDirs) {
                        if (directoryName == "video_frames" &&
                            File(incident, "incident_video.mp4").isFile) continue
                        val images = File(incident, directoryName).listFiles()
                            ?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                            ?.sortedBy { it.name } ?: emptyList()
                        for (image in images) {
                            if (mediaBytes + image.length() > OPTIONAL_MEDIA_BUDGET) {
                                omitted.add("${incident.name}/$directoryName: exceeded optional media budget")
                                break
                            }
                            add(zip, image, prefix + directoryName + "/" + image.name)
                            mediaBytes += image.length()
                        }
                    }
                }
                val manifest = buildString {
                    appendLine("RobotRun overnight diagnostic bundle")
                    appendLine("Window: epochMs ${now - OvernightLogPolicy.RETENTION_MS} .. $now")
                    appendLine("Archive: up to 10 hours or 200 MiB (whichever is reached first)")
                    appendLine("Incident retention: 72 hours, separate 200 MiB cap; newest $MAX_INCIDENTS included")
                    appendLine("Images: optional event-triggered 480x270 JPEG q35 every 2s, recovery + 10s, max 3min / 90 frames from trigger; no MP4 encoding. Legacy incident MP4s remain exportable.")
                    appendLine("Screenshots and legacy videos may contain visible app/game information. Keep this ZIP private.")
                    appendLine("Exported text logs use .txt filenames, including previously stored .log incident files.")
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
            File(staging, "robotrun_10h.txt").delete()
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
