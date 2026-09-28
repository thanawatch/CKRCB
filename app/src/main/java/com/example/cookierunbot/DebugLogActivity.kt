package com.example.cookierunbot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.cookierunbot.diagnostics.DebugLogStore
import com.example.cookierunbot.diagnostics.LogPackageBuilder
import com.example.cookierunbot.capture.IncidentVideoRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugLogActivity : ComponentActivity() {
    private lateinit var lineCount: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var renderedRevision = -1L

    private val refresh = object : Runnable {
        override fun run() {
            renderLog()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
    }

    override fun onStart() {
        super.onStart()
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    private fun buildContentView(): LinearLayout {
        val padding = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = "Debug Log"
            textSize = 22f
            gravity = Gravity.CENTER
        }, matchWidth())
        lineCount = TextView(this).apply { text = "0 / 500 lines" }
        root.addView(lineCount, matchWidth())

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton("Copy Log", ::copyLog))
        buttons.addView(actionButton("Clear Log", ::clearLog))
        buttons.addView(actionButton("Save Log", ::saveLog))
        buttons.addView(actionButton("Save 10h ZIP", ::save10Hours))
        val videoButton = actionButton(if (IncidentVideoRecorder.isEnabled(this)) "Video ON" else "Video OFF") {}
        videoButton.setOnClickListener {
            val newValue = !IncidentVideoRecorder.isEnabled(this)
            IncidentVideoRecorder.setEnabled(this, newValue)
            videoButton.text = if (newValue) "Video ON" else "Video OFF"
            Toast.makeText(this, "Incident video ${if (newValue) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
        }
        buttons.addView(videoButton)
        buttons.addView(actionButton("Close") { finish() })
        root.addView(HorizontalScrollView(this).apply { addView(buttons) }, matchWidth())

        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logText, matchWidth()) }
        root.addView(
            logScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun renderLog(force: Boolean = false) {
        val snapshot = DebugLogStore.snapshot()
        if (!force && snapshot.revision == renderedRevision) return
        renderedRevision = snapshot.revision
        lineCount.text = "${snapshot.lineCount} / 500 lines"
        logText.text = snapshot.text.ifEmpty { "No debug events yet." }
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun copyLog() {
        val text = DebugLogStore.snapshot().text
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("RobotRun Debug Log", text))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }

    private fun clearLog() {
        DebugLogStore.clear()
        renderLog(force = true)
        Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
    }

    private fun saveLog() {
        val snapshot = DebugLogStore.snapshot()
        val fileName = "bot_log_${FILE_TIMESTAMP.format(Date())}.txt"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/RobotRun",
                    )
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Unable to create log file")
                contentResolver.openOutputStream(uri, "w")!!.bufferedWriter(Charsets.UTF_8).use {
                    it.write(snapshot.text)
                    it.newLine()
                    it.flush()
                }
                "Download/RobotRun/$fileName"
            } else {
                val directory = File(
                    getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                    "RobotRun",
                ).apply { mkdirs() }
                File(directory, fileName).also { it.writeText(snapshot.text, Charsets.UTF_8) }.absolutePath
            }
        }.onSuccess { path ->
            Toast.makeText(this, "Saved: $path", Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            Toast.makeText(this, "Save failed: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun save10Hours() {
        Thread({
            var archive: File? = null
            try {
                archive = LogPackageBuilder.create(applicationContext, Long.MAX_VALUE)
                val file = requireNotNull(archive)
                val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RobotRun")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Unable to create ZIP")
                    contentResolver.openOutputStream(uri, "w")!!.use { out ->
                        file.inputStream().use { it.copyTo(out, 32 * 1024) }
                    }
                    "Download/RobotRun/${file.name}"
                } else {
                    val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "RobotRun")
                        .apply { mkdirs() }
                    file.copyTo(File(dir, file.name), overwrite = true).absolutePath
                }
                runOnUiThread { Toast.makeText(this, "Saved: $saved", Toast.LENGTH_LONG).show() }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "10h export failed: ${error.javaClass.simpleName}",
                        Toast.LENGTH_LONG).show()
                }
            } finally {
                archive?.let(LogPackageBuilder::cleanup)
            }
        }, "robotrun-local-log-export").start()
    }

    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        private const val REFRESH_INTERVAL_MS = 750L
        private val FILE_TIMESTAMP = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }
}
