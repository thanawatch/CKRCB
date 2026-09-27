package com.example.cookierunbot.automation

/** WindowManager overlay dimensions in the *current* usable display area, not capture pixels. */
internal data class CalibrationPanelSize(val width: Int, val height: Int)

internal object CalibrationPanelLayoutPolicy {
    fun fit(availableWidth: Int, availableHeight: Int, maximumSizePx: Int): CalibrationPanelSize {
        require(availableWidth > 0 && availableHeight > 0 && maximumSizePx > 0)
        // Insets have already been removed by the UI. Reserve 6% for system/OEM margins.
        return CalibrationPanelSize(
            width = minOf(maximumSizePx, (availableWidth * 94L / 100L).toInt()).coerceAtLeast(1),
            height = minOf(maximumSizePx, (availableHeight * 94L / 100L).toInt()).coerceAtLeast(1),
        )
    }
}
