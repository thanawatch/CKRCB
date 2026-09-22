package com.example.cookierunbot

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.cookierunbot.notifications.TelegramConfig
import com.example.cookierunbot.notifications.TelegramConfigStore
import com.example.cookierunbot.notifications.TelegramNotifier

class TelegramSettingsActivity : ComponentActivity() {
    private val notifier by lazy { TelegramNotifier(applicationContext) }
    private lateinit var enabled: Switch
    private lateinit var token: EditText
    private lateinit var chatId: EditText
    private lateinit var sendImages: Switch
    private lateinit var sendText: Switch
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = "Telegram Notifications"
            textSize = 23f
            gravity = Gravity.CENTER_HORIZONTAL
        }, matchWidth())
        enabled = addSwitch(content, "Enable Telegram notifications")
        token = EditText(this).apply {
            hint = "Telegram Bot Token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(token, matchWidth())
        chatId = EditText(this).apply {
            hint = "Telegram Chat ID"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        content.addView(chatId, matchWidth())
        sendImages = addSwitch(content, "Send debug images")
        sendText = addSwitch(content, "Enable text notifications")
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }
        content.addView(status, matchWidth())
        content.addView(Button(this).apply {
            text = "Save Telegram Settings"
            setOnClickListener {
                val config = readConfig()
                if (TelegramConfigStore.save(this@TelegramSettingsActivity, config)) {
                    renderStatus(config)
                    Toast.makeText(this@TelegramSettingsActivity, "Telegram settings saved", Toast.LENGTH_SHORT).show()
                } else {
                    status.text = "Status: Secure storage error"
                    Toast.makeText(this@TelegramSettingsActivity, "Unable to protect Bot Token", Toast.LENGTH_LONG).show()
                }
            }
        }, matchWidth())
        content.addView(Button(this).apply {
            text = "Send Test Message"
            setOnClickListener {
                val config = readConfig()
                val saved = TelegramConfigStore.save(this@TelegramSettingsActivity, config)
                if (saved && config.enabled && config.isValid) {
                    status.text = "Test: Sending…"
                    notifier.sendTestMessage("RobotRun Telegram test successful") { result ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            status.text = if (result.success) {
                                "Test: Success (HTTP ${result.httpCode})"
                            } else {
                                "Test: Failed (${result.reason}${result.httpCode?.let { ", HTTP $it" }.orEmpty()})"
                            }
                        }
                    }
                } else {
                    Toast.makeText(
                        this@TelegramSettingsActivity,
                        "Enable Telegram and enter Bot Token + Chat ID",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }, matchWidth())
        setContentView(ScrollView(this).apply { addView(content) })
        loadConfig()
    }

    private fun loadConfig() {
        val config = TelegramConfigStore.load(this)
        enabled.isChecked = config.enabled
        token.setText(config.botToken)
        chatId.setText(config.chatId)
        sendImages.isChecked = config.sendDebugImages
        sendText.isChecked = config.sendTextNotifications
        renderStatus(config)
    }

    private fun readConfig() = TelegramConfig(
        enabled = enabled.isChecked,
        botToken = token.text.toString().trim(),
        chatId = chatId.text.toString().trim(),
        sendDebugImages = sendImages.isChecked,
        sendTextNotifications = sendText.isChecked,
    )

    private fun renderStatus(config: TelegramConfig) {
        status.text = when {
            !config.enabled -> "Status: Disabled"
            config.isValid -> "Status: Ready"
            else -> "Status: Invalid (${config.invalidReason})"
        }
    }

    private fun addSwitch(parent: LinearLayout, label: String) = Switch(this).also {
        it.text = label
        parent.addView(it, matchWidth())
    }

    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
