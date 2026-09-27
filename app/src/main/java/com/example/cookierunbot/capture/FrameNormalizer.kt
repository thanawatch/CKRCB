package com.example.cookierunbot.capture

import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

data class ContentInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

/** Immutable transform shared by frame normalization and logical tap mapping. */
data class ContentTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val contentRect: Rect,
    val displayCutoutInsets: ContentInsets,
) {
    val scaleX: Double get() = contentRect.width.toDouble() / FrameNormalizer.LOGICAL_WIDTH
    val scaleY: Double get() = contentRect.height.toDouble() / FrameNormalizer.LOGICAL_HEIGHT
    val offsetX: Int get() = contentRect.x
    val offsetY: Int get() = contentRect.y
}

object FrameNormalizer {
    const val LOGICAL_WIDTH = 1280
    const val LOGICAL_HEIGHT = 720

    /**
     * Derives a 16:9 game viewport inside the current display safe area.
     * The cutout can move between landscape edges when orientation flips.
     */
    fun contentTransform(
        width: Int,
        height: Int,
        cutoutInsets: ContentInsets = ContentInsets(),
    ): ContentTransform {
        require(width > 0 && height > 0) { "Source size must be positive" }

        val safeLeft = cutoutInsets.left.coerceIn(0, width - 1)
        val safeTop = cutoutInsets.top.coerceIn(0, height - 1)
        val safeRight = (width - cutoutInsets.right.coerceAtLeast(0)).coerceIn(safeLeft + 1, width)
        val safeBottom = (height - cutoutInsets.bottom.coerceAtLeast(0)).coerceIn(safeTop + 1, height)
        val safeWidth = safeRight - safeLeft
        val safeHeight = safeBottom - safeTop
        val targetAspect = LOGICAL_WIDTH.toDouble() / LOGICAL_HEIGHT
        val safeAspect = safeWidth.toDouble() / safeHeight

        val rect = if (safeAspect > targetAspect) {
            val cropWidth = (safeHeight * targetAspect).roundToInt().coerceAtMost(safeWidth)
            Rect(safeLeft + (safeWidth - cropWidth) / 2, safeTop, cropWidth, safeHeight)
        } else {
            val cropHeight = (safeWidth / targetAspect).roundToInt().coerceAtMost(safeHeight)
            Rect(safeLeft, safeTop + (safeHeight - cropHeight) / 2, safeWidth, cropHeight)
        }
        return ContentTransform(width, height, rect, cutoutInsets)
    }

    fun normalize(sourceRgba: Mat, outputRgba: Mat, transform: ContentTransform) {
        require(!sourceRgba.empty()) { "Source frame is empty" }
        require(
            sourceRgba.cols() == transform.sourceWidth && sourceRgba.rows() == transform.sourceHeight,
        ) { "Content transform does not match source frame" }
        val cropped = sourceRgba.submat(transform.contentRect)
        try {
            Imgproc.resize(
                cropped,
                outputRgba,
                Size(LOGICAL_WIDTH.toDouble(), LOGICAL_HEIGHT.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
        } finally {
            cropped.release()
        }
    }

    /** Pixel-exact copy used only by the explicitly selected Redfinger session profile. */
    fun normalizeExactSize(sourceRgba: Mat, outputRgba: Mat, transform: ContentTransform) {
        require(CapturePerformanceProfile.hasExactSizeGeometry(transform)) {
            "Exact-size normalization requires 1280x720 source and content rectangle"
        }
        require(sourceRgba.cols() == LOGICAL_WIDTH && sourceRgba.rows() == LOGICAL_HEIGHT)
        sourceRgba.copyTo(outputRgba)
    }

    /** Center-crop fallback for callers without display inset metadata. */
    fun normalize(sourceRgba: Mat, outputRgba: Mat) = normalize(
        sourceRgba,
        outputRgba,
        contentTransform(sourceRgba.cols(), sourceRgba.rows()),
    )

    fun cropRect(width: Int, height: Int): Rect = contentTransform(width, height).contentRect
}
