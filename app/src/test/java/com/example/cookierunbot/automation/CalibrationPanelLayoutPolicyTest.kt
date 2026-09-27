package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPanelLayoutPolicyTest {
    @Test fun portraitAndLandscapeFitTheirCurrentAvailableArea() {
        val portrait = CalibrationPanelLayoutPolicy.fit(1080, 2200, 1860)
        assertEquals(CalibrationPanelSize(1015, 1860), portrait)
        val landscape = CalibrationPanelLayoutPolicy.fit(2200, 1010, 1860)
        assertEquals(CalibrationPanelSize(1860, 949), landscape)
        assertEquals(portrait, CalibrationPanelLayoutPolicy.fit(1080, 2200, 1860))
        assertTrue(portrait.width <= 1080 && portrait.height <= 2200)
        assertTrue(landscape.width <= 2200 && landscape.height <= 1010)
    }

    @Test fun narrowDisplayRemainsInsideItsInsetsAdjustedViewport() {
        val narrow = CalibrationPanelLayoutPolicy.fit(320, 520, 620)
        assertEquals(CalibrationPanelSize(300, 488), narrow)
        val tiny = CalibrationPanelLayoutPolicy.fit(1, 1, 620)
        assertEquals(CalibrationPanelSize(1, 1), tiny)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidViewport() {
        CalibrationPanelLayoutPolicy.fit(0, 1200, 620)
    }
}
