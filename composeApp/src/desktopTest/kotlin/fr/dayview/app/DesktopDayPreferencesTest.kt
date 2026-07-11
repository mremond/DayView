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
        assertEquals(true, preferences.loadShowSeconds())
        assertEquals(false, preferences.loadMonochromeMenuBarIcon())
        assertEquals(SoundSettings(), preferences.loadSoundSettings())
        assertEquals("", preferences.loadGoalTitle())
        assertNull(preferences.loadGoalDeadlineMillis())
        assertEquals(25, preferences.loadPomodoroMinutes())
        assertNull(preferences.loadPomodoroEndMillis())
        assertEquals("", preferences.loadFocusIntention())
    }

    @Test
    fun dayRangeSurvivesANewPreferencesInstance() {
        preferences.saveDayRange(7 * 60 + 30, 19 * 60)

        val reloaded = DesktopDayPreferences(storage)

        assertEquals(7 * 60 + 30, reloaded.loadStartMinutes())
        assertEquals(19 * 60, reloaded.loadEndMinutes())
    }

    @Test
    fun secondsDisplayPreferenceSurvivesANewPreferencesInstance() {
        preferences.saveShowSeconds(false)

        val reloaded = DesktopDayPreferences(storage)

        assertEquals(false, reloaded.loadShowSeconds())
    }

    @Test
    fun monochromeMenuBarIconPreferenceSurvivesANewPreferencesInstance() {
        preferences.saveMonochromeMenuBarIcon(true)

        val reloaded = DesktopDayPreferences(storage)

        assertEquals(true, reloaded.loadMonochromeMenuBarIcon())
    }

    @Test
    fun soundSettingsSurviveANewPreferencesInstance() {
        val settings = SoundSettings(
            enabled = true,
            startCueEnabled = false,
            intervalMinutes = 90,
            volumePercent = 60,
        )
        preferences.saveSoundSettings(settings)

        val reloaded = DesktopDayPreferences(storage)

        assertEquals(settings, reloaded.loadSoundSettings())
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

    @Test
    fun activeFocusSlotSurvivesANewPreferencesInstance() {
        preferences.savePomodoro(45, 1_800_000_000_000L)

        val reloaded = DesktopDayPreferences(storage)

        assertEquals(45, reloaded.loadPomodoroMinutes())
        assertEquals(1_800_000_000_000L, reloaded.loadPomodoroEndMillis())
    }

    @Test
    fun stoppingFocusSlotKeepsPreferredDuration() {
        preferences.savePomodoro(35, 1_800_000_000_000L)
        preferences.savePomodoro(35, null)

        val reloaded = DesktopDayPreferences(storage)

        assertEquals(35, reloaded.loadPomodoroMinutes())
        assertNull(reloaded.loadPomodoroEndMillis())
    }

    @Test
    fun focusIntentionSurvivesANewPreferencesInstance() {
        preferences.saveFocusIntention("Finaliser la présentation")

        val reloaded = DesktopDayPreferences(storage)

        assertEquals("Finaliser la présentation", reloaded.loadFocusIntention())
    }

    @Test
    fun observersReceiveSnapshotsUntilTheyUnsubscribe() {
        val observed = mutableListOf<DayPreferencesSnapshot>()
        val stopObserving = preferences.observe(observed::add)

        preferences.saveDayRange(7 * 60, 19 * 60)
        preferences.saveFocusIntention("Préparer la démonstration")

        assertEquals(3, observed.size)
        assertEquals(7 * 60, observed.last().startMinutes)
        assertEquals("Préparer la démonstration", observed.last().focusIntention)

        stopObserving()
        preferences.saveShowSeconds(false)
        assertEquals(3, observed.size)
    }
}
