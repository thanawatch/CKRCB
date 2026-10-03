package com.example.cookierunbot.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class LogPackageBuilderTest {
    @Test fun exportedEntriesStayUnderSingleArchiveRoot() {
        val root = "RobotRun-Logs-20261004-050000"

        assertEquals(
            "$root/robotrun_10h.txt",
            logPackageEntry(root, "robotrun_10h.txt"),
        )
        assertEquals(
            "$root/manifest.txt",
            logPackageEntry(root, "/manifest.txt"),
        )
        assertEquals(
            "$root/incidents/incident-1/",
            logPackageEntry("$root/", "incidents/incident-1/"),
        )
    }
}
