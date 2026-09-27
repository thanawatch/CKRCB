package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationCoordinatePolicyTest {
    @Test fun touchConvertsThroughRenderedCropAndZoom() {
        assertEquals(CalibrationPoint(640, 360), CalibrationCoordinatePolicy.fromImageTouch(340f, 200f, 20f, 20f, 0.5f))
        assertEquals(CalibrationPoint(100, 200), CalibrationCoordinatePolicy.fromImageTouch(70f, 120f, 20f, 20f, 0.5f))
        assertNull(CalibrationCoordinatePolicy.fromImageTouch(19f, 200f, 20f, 20f, 0.5f))
        assertNull(CalibrationCoordinatePolicy.fromImageTouch(20f, 200f, 20f, 20f, 0f))
    }

    @Test fun selectionClampsToPixelBounds() {
        assertEquals(CalibrationPoint(1279, 719), CalibrationCoordinatePolicy.fromImageTouch(1279.8f, 719.8f, 0f, 0f, 1f))
        assertNull(CalibrationCoordinatePolicy.fromImageTouch(1280f, 100f, 0f, 0f, 1f))
    }

    @Test fun captureMustBeFreshAndAfterOverlayWasHidden() {
        assertTrue(CalibrationCoordinatePolicy.freshAfterHidden(9, 10, 100, 110, 100_000_000))
        assertFalse(CalibrationCoordinatePolicy.freshAfterHidden(9, 9, 100, 110, 100_000_000))
        assertFalse(CalibrationCoordinatePolicy.freshAfterHidden(9, 10, 100, 99, 100_000_000))
        assertFalse(CalibrationCoordinatePolicy.freshAfterHidden(9, 10, 100, 110, 1_000_000_001))
    }
}
