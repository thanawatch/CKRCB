package com.example.cookierunbot.notifications

import android.content.Context

object TelegramConfigStore {
    private const val PREFERENCES = "telegram_notifications"

    fun load(context: Context): TelegramConfig {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val secureStore = TelegramSecureTokenStore(context.applicationContext)
        val plainToken = preferences.getString("bot_token", null)
        val token = migrateTelegramToken(
            secureToken = secureStore.loadToken(),
            plainToken = plainToken,
            writeSecure = secureStore::saveToken,
            removePlain = { preferences.edit().remove("bot_token").commit() },
        )
        return TelegramConfig(
            enabled = preferences.getBoolean("enabled", false),
            botToken = token,
            chatId = preferences.getString("chat_id", "").orEmpty(),
            sendDebugImages = preferences.getBoolean("send_debug_images", true),
            sendTextNotifications = preferences.getBoolean("send_text_notifications", true),
        )
    }

    fun save(context: Context, config: TelegramConfig): Boolean {
        val secureSaved = TelegramSecureTokenStore(context.applicationContext)
            .saveToken(config.botToken.trim())
        if (!secureSaved) return false
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", config.enabled)
            .remove("bot_token")
            .putString("chat_id", config.chatId.trim())
            .putBoolean("send_debug_images", config.sendDebugImages)
            .putBoolean("send_text_notifications", config.sendTextNotifications)
            .apply()
        return true
    }
}
