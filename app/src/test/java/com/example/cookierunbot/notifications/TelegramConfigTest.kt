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

    @Test fun commandReplyRetriesSameImmutableTextThenSucceeds() {
        val original = TelegramCommandReplyAttempt("fixed status snapshot")
        val first = telegramCommandRetryDecision(
            original,
            TelegramSendResult(false, "io_exception:SocketTimeoutException"),
        )
        assertTrue(first.retry)
        assertEquals(2_000L, first.delayMs)
        assertEquals("fixed status snapshot", first.nextAttempt?.replyText)
        assertEquals(2, first.nextAttempt?.attempt)

        val delivered = telegramCommandRetryDecision(
            requireNotNull(first.nextAttempt),
            TelegramSendResult(true, "success", 200),
        )
        assertFalse(delivered.retry)
    }

    @Test fun commandReplyRetriesAreBoundedToThreeTotalAttempts() {
        var state = TelegramCommandReplyAttempt("same reply")
        var attempts = 1
        while (true) {
            val decision = telegramCommandRetryDecision(
                state,
                TelegramSendResult(false, "http_error", 500),
            )
            if (!decision.retry) break
            state = requireNotNull(decision.nextAttempt)
            attempts += 1
        }
        assertEquals(TELEGRAM_COMMAND_MAX_ATTEMPTS, attempts)
        assertEquals("same reply", state.replyText)
    }

    @Test fun telegram429RetryAfterIsParsedAndRespected() {
        val body = """{"ok":false,"error_code":429,"parameters":{"retry_after":12}}"""
        val retryAfter = parseTelegramRetryAfterSeconds(body)
        assertEquals(12L, retryAfter)
        val decision = telegramCommandRetryDecision(
            TelegramCommandReplyAttempt("reply"),
            TelegramSendResult(false, "http_error", 429, retryAfter),
        )
        assertTrue(decision.retry)
        assertTrue(decision.delayMs >= 12_000L)
    }

    @Test fun permanentClientErrorsAreNotRetried() {
        listOf(400, 401, 403).forEach { code ->
            val decision = telegramCommandRetryDecision(
                TelegramCommandReplyAttempt("reply"),
                TelegramSendResult(false, "http_error", code),
            )
            assertFalse("HTTP $code", decision.retry)
        }
    }
}
