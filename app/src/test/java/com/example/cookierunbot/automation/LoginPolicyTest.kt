package com.example.cookierunbot.automation

import com.example.cookierunbot.detection.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginPolicyTest {
    @Test
    fun tapCenterUsesDetectedTemplateBounds() {
        val result = DetectionResult(
            state = BotStage.LOGIN,
            score = 1.0,
            matched = true,
            x = 415,
            y = 593,
            width = 447,
            height = 84,
        )

        assertEquals(LogicalPoint(638, 635), LoginPolicy.tapCenter(result))
    }

    @Test
    fun googleAccountFollowUpUsesProvidedScreenCoordinateAndFiveSecondDelay() {
        assertEquals(LogicalPoint(657, 294), LoginPolicy.GOOGLE_LOGO_POINT)
        assertEquals(5_000L, LoginPolicy.GOOGLE_ACCOUNT_DELAY_MS)
    }

    @Test
    fun accountPickerUsesSuppliedTemplateRoiProfilePointAndOneMinuteTimeout() {
        assertEquals("LOGIN_ACCOUNT_PICKER_1.png", LoginPolicy.ACCOUNT_PICKER_TEMPLATE)
        assertEquals(0.8, LoginPolicy.ACCOUNT_PICKER_MATCH_THRESHOLD, 0.0)
        assertEquals(270, LoginPolicy.ACCOUNT_PICKER_ROI_LEFT)
        assertEquals(75, LoginPolicy.ACCOUNT_PICKER_ROI_TOP)
        assertEquals(1010, LoginPolicy.ACCOUNT_PICKER_ROI_RIGHT)
        assertEquals(285, LoginPolicy.ACCOUNT_PICKER_ROI_BOTTOM)
        assertEquals(LogicalPoint(323, 299), LoginPolicy.ACCOUNT_PROFILE_POINT)
        assertEquals(60_000L, LoginPolicy.ACCOUNT_PICKER_SEARCH_TIMEOUT_MS)
        assertFalse(LoginPolicy.accountPickerSearchExpired(1_000L, 60_999L))
        assertTrue(LoginPolicy.accountPickerSearchExpired(1_000L, 61_000L))
    }

    @Test
    fun retryWaitsThreeSecondsAfterAcceptedTap() {
        assertFalse(LoginPolicy.retryDue(nowMs = 3_999L, lastTapAtMs = 1_000L))
        assertTrue(LoginPolicy.retryDue(nowMs = 4_000L, lastTapAtMs = 1_000L))
        assertTrue(LoginPolicy.retryDue(nowMs = 1_000L, lastTapAtMs = 0L))
    }
}
