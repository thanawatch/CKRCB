package com.example.cookierunbot.automation

internal object LeagueEndedRestartPolicy {
    const val TEMPLATE = "LEAGUE_ENDED_RESTART_1.png"

    // Normalized 1280x720 detector ROI. The supplied 564x211 template matches at
    // (350,181); the small surrounding margin tolerates minor popup movement.
    const val ROI_X = 330
    const val ROI_Y = 160
    const val ROI_WIDTH = 604
    const val ROI_HEIGHT = 252

    // Center of the visible green Confirm button in the supplied normalized frame.
    val CONFIRM_POINT = LogicalPoint(640, 458)

    fun eligibleGroup(group: DetectionGroup): Boolean =
        group == DetectionGroup.PRE_GAME || group == DetectionGroup.POST_GAME ||
            group == DetectionGroup.IN_GAME
}
