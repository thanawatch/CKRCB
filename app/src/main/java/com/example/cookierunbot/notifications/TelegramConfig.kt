package com.example.cookierunbot.notifications

data class TelegramConfig(
    val enabled: Boolean = false,
    val botToken: String = "",
    val chatId: String = "",
    val sendDebugImages: Boolean = true,
    val sendTextNotifications: Boolean = true,
) {
    val isValid: Boolean get() = botToken.isNotBlank() && chatId.isNotBlank()
    val invalidReason: String?
        get() = when {
            botToken.isBlank() -> "missing_bot_token"
            chatId.isBlank() -> "missing_chat_id"
            else -> null
        }
}
