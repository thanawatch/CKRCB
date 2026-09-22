package com.example.cookierunbot.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramConfigTest {
    @Test fun missingTokenIsInvalid() {
        val config = TelegramConfig(enabled = true, chatId = "123")
        assertFalse(config.isValid)
        assertEquals("missing_bot_token", config.invalidReason)
    }

    @Test fun missingChatIdIsInvalid() {
        val config = TelegramConfig(enabled = true, botToken = "secret")
        assertFalse(config.isValid)
        assertEquals("missing_chat_id", config.invalidReason)
    }

    @Test fun completeConfigIsValid() {
        assertTrue(TelegramConfig(enabled = true, botToken = "secret", chatId = "123").isValid)
    }

    @Test fun manualTestMessageIgnoresAutomaticTextPreference() {
        val config = TelegramConfig(
            enabled = true,
            botToken = "secret",
            chatId = "123",
            sendTextNotifications = false,
        )
        assertFalse(shouldSendTelegramText(config, manualTest = false))
        assertTrue(shouldSendTelegramText(config, manualTest = true))
    }
}
