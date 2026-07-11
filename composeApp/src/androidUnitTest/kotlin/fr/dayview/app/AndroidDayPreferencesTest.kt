package fr.dayview.app

import android.content.Context
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class AndroidDayPreferencesTest {
    private lateinit var context: Context
    private lateinit var preferences: AndroidDayPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(STORAGE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        preferences = AndroidDayPreferences(context, notifyWidgets = false)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(STORAGE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun freshStorageUsesExpectedDefaults() {
        assertEquals(8 * 60, preferences.loadStartMinutes())
        assertEquals(18 * 60, preferences.loadEndMinutes())
        assertEquals(true, preferences.loadShowSeconds())
        assertEquals(SoundSettings(), preferences.loadSoundSettings())
        assertEquals("", preferences.loadGoalTitle())
        assertNull(preferences.loadGoalDeadlineMillis())
        assertEquals(25, preferences.loadPomodoroMinutes())
        assertNull(preferences.loadPomodoroEndMillis())
        assertEquals("", preferences.loadFocusIntention())
    }

    @Test
    fun valuesSurviveANewPreferencesInstance() {
        val sounds = SoundSettings(
            enabled = true,
            startCueEnabled = false,
            intervalMinutes = 90,
            volumePercent = 70,
        )
        preferences.saveDayRange(7 * 60 + 30, 19 * 60)
        preferences.saveShowSeconds(false)
        preferences.saveSoundSettings(sounds)
        preferences.saveGlobalGoal("Livrer DayView", 1_800_000_000_000L)
        preferences.savePomodoro(45, 1_800_000_100_000L)
        preferences.saveFocusIntention("Finaliser la présentation")

        val reloaded = AndroidDayPreferences(context, notifyWidgets = false)

        assertEquals(7 * 60 + 30, reloaded.loadStartMinutes())
        assertEquals(19 * 60, reloaded.loadEndMinutes())
        assertEquals(false, reloaded.loadShowSeconds())
        assertEquals(sounds, reloaded.loadSoundSettings())
        assertEquals("Livrer DayView", reloaded.loadGoalTitle())
        assertEquals(1_800_000_000_000L, reloaded.loadGoalDeadlineMillis())
        assertEquals(45, reloaded.loadPomodoroMinutes())
        assertEquals(1_800_000_100_000L, reloaded.loadPomodoroEndMillis())
        assertEquals("Finaliser la présentation", reloaded.loadFocusIntention())
    }

    @Test
    fun nullableDeadlinesCanBeClearedWithoutLosingOtherValues() {
        preferences.saveGlobalGoal("Objectif sans date", 1_800_000_000_000L)
        preferences.saveGlobalGoal("Objectif sans date", null)
        preferences.savePomodoro(35, 1_800_000_100_000L)
        preferences.savePomodoro(35, null)

        val reloaded = AndroidDayPreferences(context, notifyWidgets = false)

        assertEquals("Objectif sans date", reloaded.loadGoalTitle())
        assertNull(reloaded.loadGoalDeadlineMillis())
        assertEquals(35, reloaded.loadPomodoroMinutes())
        assertNull(reloaded.loadPomodoroEndMillis())
    }

    @Test
    fun soundSettingsAreNormalizedBeforePersistence() {
        preferences.saveSoundSettings(SoundSettings(intervalMinutes = 5, volumePercent = 500))

        assertEquals(
            SoundSettings(intervalMinutes = 30, volumePercent = 100),
            AndroidDayPreferences(context, notifyWidgets = false).loadSoundSettings(),
        )
    }

    private companion object {
        const val STORAGE_NAME = "dayview_preferences"
    }
}
