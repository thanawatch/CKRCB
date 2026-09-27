package com.example.cookierunbot.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class IncidentVideoTimingTest {
    @Test fun stopsTenSecondsAfterRecoveryFinishes() {
        val trigger = 100_000_000_000L
        val finished = trigger + 42_000_000_000L
        assertEquals(finished + IncidentVideoTiming.TAIL_NANOS,
            IncidentVideoTiming.finalStopAt(trigger, finished))
    }

    @Test fun neverWaitsMoreThanThreeMinutesFromTrigger() {
        val trigger = 100_000_000_000L
        assertEquals(trigger + IncidentVideoTiming.MAX_VIDEO_NANOS,
            IncidentVideoTiming.finalStopAt(trigger, trigger + 200_000_000_000L))
    }
}
