package com.example.cookierunbot.automation

import com.example.cookierunbot.capture.ContentGeometryRegistry
import com.example.cookierunbot.capture.ContentInsets
import com.example.cookierunbot.capture.FrameNormalizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateMapperTest {
    @After
    fun clearGeometry() = ContentGeometryRegistry.clear()

    @Test
    fun fixedActionsRetainVerifiedPhoneCalibration() {
        val center = CoordinateMapper.calibratedLogicalToPhysical(640, 360, 2400, 1080)
        assertEquals(1138, center.x)
        assertEquals(540, center.y)

        val outside = CoordinateMapper.calibratedLogicalToPhysical(1335, 37, 2400, 1080)
        assertEquals(2181, outside.x)
        assertEquals(56, outside.y)
    }

    @Test
    fun detectorPointUsesLeftCutoutContentRect() {
        ContentGeometryRegistry.update(
            FrameNormalizer.contentTransform(2400, 1080, ContentInsets(left = 111)),
        )
        val center = CoordinateMapper.detectorLogicalToPhysical(640, 360, 2400, 1080)
        assertEquals(1255, center.x)
        assertEquals(540, center.y)
    }

    @Test
    fun slideTargetUsesNormalizedContentRectInsteadOfLegacyPhoneCalibration() {
        ContentGeometryRegistry.update(
            FrameNormalizer.contentTransform(2400, 1080, ContentInsets(left = 111)),
        )
        val slide = CoordinateMapper.logicalToPhysical(
            AutomationConfig.AUTO_SLIDE.x,
            AutomationConfig.AUTO_SLIDE.y,
            2400,
            1080,
        )
        assertEquals(1968, slide.x)
        assertEquals(929, slide.y)
    }

    @Test
    fun detectorPointUsesRightCutoutContentRect() {
        ContentGeometryRegistry.update(
            FrameNormalizer.contentTransform(2400, 1080, ContentInsets(right = 111)),
        )
        val center = CoordinateMapper.detectorLogicalToPhysical(640, 360, 2400, 1080)
        assertEquals(1144, center.x)
        assertEquals(540, center.y)
    }
}
