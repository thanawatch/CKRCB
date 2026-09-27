package com.example.cookierunbot.capture

import com.example.cookierunbot.automation.PerformanceProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
