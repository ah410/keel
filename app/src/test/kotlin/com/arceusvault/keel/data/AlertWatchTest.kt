package com.arceusvault.keel.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertWatchTest {
    private fun alert(uuid: String, level: String, dismissed: Boolean = false) = Alert(uuid = uuid, level = level, text = uuid, dismissed = dismissed)

    @Test
    fun `only new, undismissed alerts at or above the threshold notify`() {
        val alerts = listOf(
            alert("old", "CRITICAL"),
            alert("new-crit", "CRITICAL"),
            alert("new-warn", "WARNING"),
            alert("new-info", "INFO"),
            alert("new-dismissed", "ERROR", dismissed = true),
        )
        assertEquals(listOf("new-crit", "new-warn"), AlertWatch.select(alerts, setOf("old"), "WARNING").map { it.uuid })
        assertEquals(listOf("new-crit"), AlertWatch.select(alerts, setOf("old"), "ERROR").map { it.uuid })
    }

    @Test
    fun `unknown levels and thresholds fall back safely`() {
        val alerts = listOf(alert("x", "SOMETHING_NEW"), alert("y", "warning"))
        // An unrecognized level ranks below INFO, so it never notifies; lowercase levels still match.
        assertEquals(listOf("y"), AlertWatch.select(alerts, emptySet(), "bogus").map { it.uuid })
    }
}
