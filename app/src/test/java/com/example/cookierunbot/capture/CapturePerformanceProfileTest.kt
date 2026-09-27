package com.example.cookierunbot.capture

import com.example.cookierunbot.automation.PerformanceProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.opencv.core.Rect

class CapturePerformanceProfileTest {
    private val exact = ContentTransform(1280, 720, Rect(0, 0, 1280, 720), ContentInsets())

    @Test
    fun exactGeometryIsGatedByExplicitProfileSelection() {
        assertFalse(CapturePerformanceProfile.canUseExactSizeFastPath(PerformanceProfile.LEGACY, exact))
        assertTrue(
            CapturePerformanceProfile.canUseExactSizeFastPath(
                PerformanceProfile.REDFINGER_OPTIMIZED,
                exact,
            ),
        )
    }

    @Test
    fun redfingerProfileFallsBackForNonQualifyingGeometry() {
        val cropped = ContentTransform(2400, 1080, Rect(240, 0, 1920, 1080), ContentInsets())
        val offset = ContentTransform(1280, 720, Rect(1, 0, 1279, 720), ContentInsets())

        assertFalse(
            CapturePerformanceProfile.canUseExactSizeFastPath(
                PerformanceProfile.REDFINGER_OPTIMIZED,
                cropped,
            ),
        )
        assertFalse(
            CapturePerformanceProfile.canUseExactSizeFastPath(
                PerformanceProfile.REDFINGER_OPTIMIZED,
                offset,
            ),
        )
    }

    @Test
    fun staleStopCannotResetAQuicklyStartedNewRun() {
        CapturePerformanceProfile.activate(10L, PerformanceProfile.LEGACY)
        CapturePerformanceProfile.activate(11L, PerformanceProfile.REDFINGER_OPTIMIZED)

        CapturePerformanceProfile.reset(10L)

        assertEquals(PerformanceProfile.REDFINGER_OPTIMIZED, CapturePerformanceProfile.active)
        CapturePerformanceProfile.reset(11L)
        assertEquals(PerformanceProfile.LEGACY, CapturePerformanceProfile.active)
    }

    @Test
    fun inFlightExactFrameRemainsValidWhenStopResetsProfile() {
        CapturePerformanceProfile.activate(12L, PerformanceProfile.REDFINGER_OPTIMIZED)
        assertTrue(CapturePerformanceProfile.canUseExactSizeFastPath(exact))

        CapturePerformanceProfile.reset(12L)

        // A frame selected before Stop can safely finish its copy. Frames selected after
        // Stop take the Legacy resize path instead.
        assertTrue(CapturePerformanceProfile.hasExactSizeGeometry(exact))
        assertFalse(CapturePerformanceProfile.canUseExactSizeFastPath(exact))
        assertFalse(
            CapturePerformanceProfile.hasExactSizeGeometry(
                ContentTransform(1280, 720, Rect(1, 0, 1279, 720), ContentInsets()),
            ),
        )
    }
}
