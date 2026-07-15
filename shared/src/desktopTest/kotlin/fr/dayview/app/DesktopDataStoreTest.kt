package fr.dayview.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class DesktopDataStoreTest {
    private fun t(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "dayview-ds-test-${System.nanoTime()}").apply { mkdirs() }
    private val legacyNode = Preferences.userRoot().node("fr/dayview/app/test-${System.nanoTime()}")

    @AfterTest
    fun cleanUp() {
        legacyNode.removeNode()
        tempDir.deleteRecursively()
    }

    @Test
    fun migratesLegacyJavaPrefs() = runTest {
        legacyNode.putInt("start_minutes", 9 * 60)
        legacyNode.put("goal_title", "Legacy goal")
        legacyNode.putLong("goal_start", 123_456_789L)
        legacyNode.putBoolean("net_time_enabled", true)
        legacyNode.put("net_time_calendars", "cal-a\ncal-b")
        legacyNode.putBoolean("monochrome_menu_bar_icon", true)
        legacyNode.flush()

        val prefs = desktopDayPreferences(
            legacy = legacyNode,
            file = File(tempDir, "dayview.preferences_pb"),
        )
        val snapshot = prefs.snapshots.first()

        assertEquals(9 * 60, snapshot.startMinutes)
        assertEquals("Legacy goal", snapshot.goalTitle)
        assertEquals(t(123_456_789L), snapshot.goalStart)
        assertTrue(snapshot.netTimeSettings.enabled)
        assertEquals(setOf("cal-a", "cal-b"), snapshot.netTimeSettings.includedCalendarIds)
        assertTrue(prefs.loadMonochromeMenuBarIcon())
    }

    @Test
    fun focusPresenceRoundTripsWithDayKey() = runTest {
        val prefs = desktopDayPreferences(
            legacy = legacyNode,
            file = File(tempDir, "dayview.preferences_pb"),
        )
        val intervals = listOf(
            FocusPresenceInterval(t(1_000L), t(2_000L)),
            FocusPresenceInterval(t(5_000L), t(9_000L)),
        )
        prefs.saveFocusPresence(19_000L, intervals)

        val (day, loaded) = prefs.loadFocusPresence()
        assertEquals(19_000L, day)
        assertEquals(intervals, loaded)
    }

    @Test
    fun savesAndLoadsFocusSessionIntervals() = runTest {
        val prefs = desktopDayPreferences(
            legacy = legacyNode,
            file = File(tempDir, "dayview.preferences_pb"),
        )
        val intervals = listOf(
            FocusPresenceInterval(t(10_000L), t(70_000L)),
        )
        prefs.saveFocusSession(dayKey = 42L, intervals = intervals)
        assertEquals(42L to intervals, prefs.loadFocusSession())
    }

    @Test
    fun loadFocusSessionDefaultsToEmpty() = runTest {
        val prefs = desktopDayPreferences(
            legacy = legacyNode,
            file = File(tempDir, "dayview.preferences_pb"),
        )
        assertEquals(-1L to emptyList<FocusPresenceInterval>(), prefs.loadFocusSession())
    }
}
