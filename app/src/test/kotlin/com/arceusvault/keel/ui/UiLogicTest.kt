package com.arceusvault.keel.ui

import com.arceusvault.keel.data.CronSchedule
import com.arceusvault.keel.ui.components.formatAgo
import com.arceusvault.keel.ui.components.formatBitRate
import com.arceusvault.keel.ui.storage.describeCron
import com.arceusvault.keel.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class UiLogicTest {
    @Test
    fun `cron descriptions`() {
        assertEquals("Daily at 00:00", describeCron(CronSchedule("0", "0", "*", "*", "*")))
        assertEquals("Hourly", describeCron(CronSchedule("15", "*", "*", "*", "*")))
        assertEquals("Weekly on Sunday at 03:30", describeCron(CronSchedule("30", "3", "*", "*", "7")))
        assertEquals("Monthly on day 1 at 02:00", describeCron(CronSchedule("00", "2", "1", "*", "*")))
        assertEquals("cron */5 * * * *", describeCron(CronSchedule("*/5", "*", "*", "*", "*")))
    }

    @Test
    fun `unknown or old theme names follow the system`() {
        assertEquals("dark", Palette.resolve("system", systemDark = true).name)
        assertEquals("light", Palette.resolve("ix-dark", systemDark = false).name)
        assertEquals("black", Palette.resolve("black", systemDark = false).name)
        assertFalse(Palette.resolve(null, systemDark = false).dark)
    }

    @Test
    fun `bit rates use decimal units like the web UI`() {
        assertEquals("8 b/s", formatBitRate(1.0))
        assertEquals("10.0 Mb/s", formatBitRate(1_250_000.0))
    }

    @Test
    fun `relative times`() {
        val now = Instant.parse("2026-10-11T12:00:00Z")
        assertEquals("never", formatAgo(null, now))
        assertEquals("5 min ago", formatAgo(now - Duration.ofMinutes(5), now))
        assertEquals("3 h ago", formatAgo(now - Duration.ofHours(3), now))
        assertEquals("2 days ago", formatAgo(now - Duration.ofDays(2), now))
    }

    @Test
    fun `navigator keeps a stack per tab and survives save and restore`() {
        val nav = Navigator(Tab.DASHBOARD, emptyMap())
        nav.go(Tab.APPS, Route("app", "plex"))
        nav.open(Route("app-logs", "plex${LOG_SEP}abc"))
        nav.select(Tab.STORAGE)
        nav.open(Route("datasets"))

        val restored = Navigator.saver.restore(with(Navigator.saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(nav) }!!)!!
        assertEquals(Tab.STORAGE, restored.tab)
        assertEquals(Route("datasets"), restored.current)
        restored.select(Tab.APPS)
        assertEquals(Route("app-logs", "plex${LOG_SEP}abc"), restored.current)
        assertTrue(restored.back())
        assertEquals(Route("app", "plex"), restored.current)
        assertTrue(restored.back())
        assertEquals(null, restored.current)
        assertTrue(restored.back())
        assertEquals(Tab.DASHBOARD, restored.tab)
        assertFalse(restored.back())
    }
}
