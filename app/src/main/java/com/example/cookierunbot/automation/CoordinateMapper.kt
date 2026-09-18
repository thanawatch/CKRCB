package com.example.cookierunbot.automation

import com.example.cookierunbot.capture.FrameNormalizer
import com.example.cookierunbot.capture.ContentGeometryRegistry
import kotlin.math.roundToInt

object CoordinateMapper {
    /**
     * Maps logical game coordinates through the exact viewport used by detection.
     */
    fun logicalToPhysical(
        logicalX: Int,
        logicalY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): PhysicalPoint {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source display size is unavailable" }
        require(sourceWidth > 0 && sourceHeight > 0) { "Source display size is unavailable" }
        val crop = ContentGeometryRegistry.current(sourceWidth, sourceHeight)
            ?.contentRect
            ?: FrameNormalizer.contentTransform(sourceWidth, sourceHeight).contentRect
        val x = (crop.x + logicalX.toDouble() * crop.width / FrameNormalizer.LOGICAL_WIDTH)
            .roundToInt()
            .coerceIn(0, sourceWidth - 1)
        val y = (crop.y + logicalY.toDouble() * crop.height / FrameNormalizer.LOGICAL_HEIGHT)
            .roundToInt()
            .coerceIn(0, sourceHeight - 1)
        return PhysicalPoint(x, y)
    }

    fun detectorLogicalToPhysical(
        logicalX: Int,
        logicalY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): PhysicalPoint = logicalToPhysical(logicalX, logicalY, sourceWidth, sourceHeight)

    /** Mapping retained only for coordinates explicitly calibrated as phone pixels. */
    fun calibratedLogicalToPhysical(
        logicalX: Int,
        logicalY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): PhysicalPoint {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source display size is unavailable" }
        val scale = sourceHeight.toDouble() / FrameNormalizer.LOGICAL_HEIGHT
        val offsetX = REFERENCE_OFFSET_X * sourceHeight / REFERENCE_PHONE_HEIGHT
        return PhysicalPoint(
            (offsetX + logicalX * scale).roundToInt().coerceIn(0, sourceWidth - 1),
            (logicalY * scale).roundToInt().coerceIn(0, sourceHeight - 1),
        )
    }

    private const val REFERENCE_PHONE_HEIGHT = 1080.0
    private const val REFERENCE_OFFSET_X = 178.0
}

data class PhysicalPoint(val x: Int, val y: Int)
