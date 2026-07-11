package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DayPreferencesTest {
    @Test
    fun fallbackPreferencesExposeSafeDefaults() {
        assertEquals(8 * 60, DefaultDayPreferences.loadStartMinutes())
        assertEquals(18 * 60, DefaultDayPreferences.loadEndMinutes())
        assertEquals(true, DefaultDayPreferences.loadShowSeconds())
        assertEquals(SoundSettings(), DefaultDayPreferences.loadSoundSettings())
        assertEquals("", DefaultDayPreferences.loadGoalTitle())
        assertNull(DefaultDayPreferences.loadGoalDeadlineMillis())
        assertEquals(25, DefaultDayPreferences.loadPomodoroMinutes())
        assertNull(DefaultDayPreferences.loadPomodoroEndMillis())
        assertEquals("", DefaultDayPreferences.loadFocusIntention())
        assertEquals(NetTimeSettings(), DefaultDayPreferences.loadNetTimeSettings())
    }

    @Test
    fun fallbackPreferencesAcceptWritesWithoutChangingDefaults() {
        DefaultDayPreferences.saveDayRange(0, 1)
        DefaultDayPreferences.saveShowSeconds(false)
        DefaultDayPreferences.saveSoundSettings(SoundSettings(enabled = true))
        DefaultDayPreferences.saveGlobalGoal("Temporaire", 42L, 42L)
        DefaultDayPreferences.savePomodoro(50, 123L)
        DefaultDayPreferences.saveFocusIntention("Terminer le test")
        DefaultDayPreferences.saveNetTimeSettings(
            NetTimeSettings(enabled = true, includedCalendarIds = setOf("travail")),
        )

        assertEquals(8 * 60, DefaultDayPreferences.loadStartMinutes())
        assertEquals(18 * 60, DefaultDayPreferences.loadEndMinutes())
        assertEquals(true, DefaultDayPreferences.loadShowSeconds())
        assertEquals(SoundSettings(), DefaultDayPreferences.loadSoundSettings())
        assertEquals("", DefaultDayPreferences.loadGoalTitle())
        assertNull(DefaultDayPreferences.loadGoalDeadlineMillis())
        assertEquals(25, DefaultDayPreferences.loadPomodoroMinutes())
        assertNull(DefaultDayPreferences.loadPomodoroEndMillis())
        assertEquals("", DefaultDayPreferences.loadFocusIntention())
        assertEquals(NetTimeSettings(), DefaultDayPreferences.loadNetTimeSettings())
    }
}
