package fr.dayview.app

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDayPreferencesTest {
    private val storage = Preferences.userRoot().node("fr/dayview/app/tests/${UUID.randomUUID()}")
    private val preferences = DesktopDayPreferences(storage)

    @AfterTest
    fun removeIsolatedStorage() {
        storage.removeNode()
        Preferences.userRoot().flush()
    }

    @Test
    fun freshStorageUsesExpectedDefaults() {
        assertEquals(8 * 60, preferences.loadStartMinutes())
        assertEquals(18 * 60, preferences.loadEndMinutes())
        assertEquals("", preferences.loadGoalTitle())
        assertNull(preferences.loadGoalDeadlineMillis())
    }

    @Test
    fun dayRangeSurvivesANewPreferencesInstance() {
        preferences.saveDayRange(7 * 60 + 30, 19 * 60)

        val reloaded = DesktopDayPreferences(storage)

        assertEquals(7 * 60 + 30, reloaded.loadStartMinutes())
        assertEquals(19 * 60, reloaded.loadEndMinutes())
    }

    @Test
    fun globalGoalSurvivesANewPreferencesInstance() {
        preferences.saveGlobalGoal("Livrer DayView", 1_800_000_000_000L)

        val reloaded = DesktopDayPreferences(storage)

        assertEquals("Livrer DayView", reloaded.loadGoalTitle())
        assertEquals(1_800_000_000_000L, reloaded.loadGoalDeadlineMillis())
    }

    @Test
    fun clearingDeadlineKeepsTitleAndPersistsNull() {
        preferences.saveGlobalGoal("Objectif sans date", 1_800_000_000_000L)
        preferences.saveGlobalGoal("Objectif sans date", null)

        val reloaded = DesktopDayPreferences(storage)

        assertEquals("Objectif sans date", reloaded.loadGoalTitle())
        assertNull(reloaded.loadGoalDeadlineMillis())
    }
}
