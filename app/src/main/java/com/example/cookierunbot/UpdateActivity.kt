package com.example.cookierunbot

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.example.cookierunbot.automation.BotRuntime
import com.example.cookierunbot.capture.LatestFrameProvider
import com.example.cookierunbot.update.UpdateDescriptor
import com.example.cookierunbot.update.UpdateFeedClient
import com.example.cookierunbot.update.UpdatePolicy
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** User-driven, Telegram-independent updater. No background install or implicit bot stop. */
class UpdateActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "robotrun-update")
    }
    private lateinit var feedInput: EditText
    private lateinit var message: TextView
    private lateinit var checkButton: Button
    private lateinit var downloadButton: Button
    private lateinit var installButton: Button
    private lateinit var localApkButton: Button
    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSelectedApk(uri)
    }
    private var candidate: UpdateDescriptor? = null
    private var verifiedApk: File? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val margin = dp(18)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin, margin, margin)
        }
        layout.addView(TextView(this).apply {
            text = "📦 RobotRun — Update App"
            textSize = 22f
            gravity = Gravity.CENTER
        }, fill())
        layout.addView(TextView(this).apply {
            text = "Installed: ${installedVersionName()} (code ${installedVersionCode()})\n" +
                "Updates are checked here, without Telegram. The APK must be signed with " +
                "the same key as the installed app. Android will ask for installation confirmation."
            textSize = 16f
        }, fill())
        layout.addView(TextView(this).apply {
            text = "\nHTTPS update feed URL"
            textSize = 17f
        }, fill())
        feedInput = EditText(this).apply {
            hint = "https://your-update-host.example/update.json"
            setSingleLine(true)
            setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(FEED_KEY, ""))
        }
        layout.addView(feedInput, fill())
        layout.addView(TextView(this).apply {
            text = "The private GitHub Actions build artifact is not a permanent public URL. " +
                "Publish only a signed APK and update.json to a trusted HTTPS distribution host. " +
                "Do not put a GitHub token or password in this URL or in the APK."
            textSize = 13f
        }, fill())
        checkButton = Button(this).apply {
            text = "Check for updates"
            setOnClickListener { checkForUpdate() }
        }
        layout.addView(checkButton, fill())
        downloadButton = Button(this).apply {
            text = "Download verified APK"
            setOnClickListener { downloadUpdate() }
        }
        layout.addView(downloadButton, fill())
        layout.addView(TextView(this).apply {
            text = "No HTTPS feed yet? You can select a downloaded APK from this device. " +
                "Only newer APKs with RobotRun's installed signing certificate will be accepted."
            textSize = 13f
        }, fill())
        localApkButton = Button(this).apply {
            text = "Choose downloaded APK (offline)"
            setOnClickListener { if (!busy) apkPicker.launch(arrayOf("*/*")) }
        }
        layout.addView(localApkButton, fill())
        installButton = Button(this).apply {
            text = "Open Android installer"
            setOnClickListener { installUpdate() }
        }
        layout.addView(installButton, fill())
        message = TextView(this).apply {
            textSize = 16f
            text = "Set the update feed URL, then tap Check for updates."
            setPadding(0, margin, 0, margin)
        }
        layout.addView(message, fill())
        setContentView(ScrollView(this).apply { addView(layout) })
        refreshButtons()
    }

    private fun checkForUpdate() {
        val url = feedInput.text.toString().trim()
        try {
            UpdatePolicy.secureUrl(url)
        } catch (_: Exception) {
            message.text = "Enter a valid HTTPS update.json URL without credentials."
            return
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(FEED_KEY, url).apply()
        candidate = null
        verifiedApk = null
        busy = true
        message.text = "Checking update feed…"
        refreshButtons()
        executor.execute {
            try {
                val result = UpdateFeedClient(applicationContext).check(url)
                showOnUi {
                    if (UpdatePolicy.hasNewerVersion(installedVersionCode(), result.versionCode)) {
                        candidate = result
                        message.text = "Available: ${result.versionName} (code ${result.versionCode})\n" +
                            "What's new: ${result.notes.ifBlank { "No release notes." }}\n" +
                            "Download the verified APK to proceed."
                    } else {
                        message.text = "RobotRun is up to date. Feed: ${result.versionName} " +
                            "(code ${result.versionCode})."
                    }
                    busy = false
                    refreshButtons()
                }
            } catch (error: Exception) {
                showOnUi {
                    message.text = "Update check failed: ${friendlyError(error)}"
                    busy = false
                    refreshButtons()
                }
            }
        }
    }

    private fun downloadUpdate() {
        val release = candidate ?: return
        busy = true
        message.text = "Downloading and verifying ${release.versionName}…"
        refreshButtons()
        executor.execute {
            try {
                val file = UpdateFeedClient(applicationContext).download(release)
                showOnUi {
                    verifiedApk = file
                    message.text = "Verified ${release.versionName}: SHA-256, package name, " +
                        "version, and signing certificate match. Stop Bot and Screen Capture " +
                        "before opening the Android installer."
                    busy = false
                    refreshButtons()
                }
            } catch (error: Exception) {
                showOnUi {
                    verifiedApk = null
                    message.text = "Update download/verification failed: ${friendlyError(error)}"
                    busy = false
                    refreshButtons()
                }
            }
        }
    }

    private fun importSelectedApk(uri: Uri) {
        candidate = null
        verifiedApk = null
        busy = true
        message.text = "Checking selected APK's package, version and signing certificate…"
        refreshButtons()
        executor.execute {
            try {
                val imported = UpdateFeedClient(applicationContext).importLocal(uri)
                showOnUi {
                    verifiedApk = imported.file
                    message.text = "Selected APK verified: ${imported.versionName} " +
                        "(code ${imported.versionCode}). Stop Bot and Screen Capture, " +
                        "then open Android installer to approve this update."
                    busy = false
                    refreshButtons()
                }
            } catch (error: Exception) {
                showOnUi {
                    message.text = "Selected APK rejected: ${friendlyError(error)}"
                    busy = false
                    refreshButtons()
                }
            }
        }
    }

    private fun installUpdate() {
        val file = verifiedApk?.takeIf { it.isFile } ?: run {
            message.text = "Download and verify the APK first."
            return
        }
        if (BotRuntime.isRunning() || LatestFrameProvider.diagnostics().captureRunning) {
            AlertDialog.Builder(this)
                .setTitle("RobotRun is active")
                .setMessage("Stop Bot and Stop Screen Capture manually before installing. " +
                    "The updater will not stop your current game or restart the app.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            message.text = "Allow RobotRun to install apps in Android settings, then tap Install again."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Install RobotRun update?")
            .setMessage("Android will ask you to confirm installation. " +
                "This operation may close RobotRun. Continue?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open installer") { _, _ ->
                try {
                    val uri = FileProvider.getUriForFile(this,
                        "$packageName.updatefileprovider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        clipData = ClipData.newRawUri("RobotRun APK", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    message.text = "Android installer could not be opened on this device."
                }
            }.show()
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun installedVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

    private fun friendlyError(error: Exception): String = when (error) {
        is IOException -> error.message?.take(160) ?: "Network or storage error."
        is IllegalArgumentException -> error.message?.take(160) ?: "Invalid update data."
        else -> "Invalid feed, unavailable network, or incompatible APK."
    }

    private fun showOnUi(action: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) action() }
    }

    private fun refreshButtons() {
        checkButton.isEnabled = !busy
        feedInput.isEnabled = !busy
        downloadButton.isEnabled = !busy && candidate != null
        localApkButton.isEnabled = !busy
        installButton.isEnabled = !busy && verifiedApk?.isFile == true
    }

    private fun fill() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val PREFS = "robotrun_update"
        const val FEED_KEY = "https_update_feed"
    }
}
