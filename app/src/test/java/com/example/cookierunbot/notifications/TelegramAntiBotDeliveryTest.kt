package com.example.cookierunbot.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramAntiBotDeliveryTest {
    @Test fun antiBotRetryUsesRetryAfterFor429() {
        val decision = telegramAntiBotRetryDecision(
            attempt = 1,
            result = TelegramSendResult(
                success = false,
                reason = "http_error",
                httpCode = 429,
                retryAfterSeconds = 7,
            ),
        )
        assertTrue(decision.retry)
        assertEquals(7_000L, decision.delayMs)
        assertEquals(2, decision.nextAttempt)
    }

    @Test fun antiBotRetryUsesFallbackForServerAndIoFailures() {
        val server = telegramAntiBotRetryDecision(
            attempt = 1,
            result = TelegramSendResult(false, "http_error", httpCode = 503),
        )
        assertTrue(server.retry)
        assertEquals(2_000L, server.delayMs)
        assertEquals(2, server.nextAttempt)

        val io = telegramAntiBotRetryDecision(
            attempt = 2,
            result = TelegramSendResult(false, "io_exception:SocketTimeoutException"),
        )
        assertTrue(io.retry)
        assertEquals(5_000L, io.delayMs)
        assertEquals(3, io.nextAttempt)
    }

    @Test fun antiBotRetryStopsForPermanentFailureSuccessAndThirdAttempt() {
        assertFalse(
            telegramAntiBotRetryDecision(
                1,
                TelegramSendResult(false, "http_error", httpCode = 400),
            ).retry,
        )
        assertFalse(
            telegramAntiBotRetryDecision(
                1,
                TelegramSendResult(true, "success", httpCode = 200),
            ).retry,
        )
        assertFalse(
            telegramAntiBotRetryDecision(
                3,
                TelegramSendResult(false, "http_error", httpCode = 503),
            ).retry,
        )
    }
}
