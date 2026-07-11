package fr.dayview.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DayPreferencesBridgeTest {
    @Test
    fun snapshotsEmitsCurrentState() = runTest {
        val prefs = FakeBridgedPreferences(DayPreferencesSnapshot(startMinutes = 9 * 60, goalTitle = "G"))
        val snapshot = prefs.snapshots.first()
        assertEquals(9 * 60, snapshot.startMinutes)
        assertEquals("G", snapshot.goalTitle)
    }

    @Test
    fun persistBridgesToIndividualSaves() = runTest {
        val prefs = FakeBridgedPreferences()
        prefs.persist(
            DayPreferencesSnapshot(
                startMinutes = 7 * 60,
                endMinutes = 19 * 60,
                showSeconds = false,
                soundSettings = SoundSettings(enabled = true, volumePercent = 55),
                goalTitle = "Ship",
                goalDeadlineMillis = 123L,
                pomodoroMinutes = 45,
                pomodoroEndMillis = 456L,
                focusIntention = "Focus",
            ),
        )
        val stored = prefs.snapshots.first()
        assertEquals(7 * 60, stored.startMinutes)
        assertEquals(19 * 60, stored.endMinutes)
        assertEquals(false, stored.showSeconds)
        assertEquals(SoundSettings(enabled = true, volumePercent = 55), stored.soundSettings)
        assertEquals("Ship", stored.goalTitle)
        assertEquals(123L, stored.goalDeadlineMillis)
        assertEquals(45, stored.pomodoroMinutes)
        assertEquals(456L, stored.pomodoroEndMillis)
        assertEquals("Focus", stored.focusIntention)
    }
}

private class FakeBridgedPreferences(
    private var snap: DayPreferencesSnapshot = DayPreferencesSnapshot(),
) : DayPreferences {
    override fun loadStartMinutes() = snap.startMinutes
    override fun loadEndMinutes() = snap.endMinutes
    override fun saveDayRange(startMinutes: Int, endMinutes: Int) {
        snap = snap.copy(startMinutes = startMinutes, endMinutes = endMinutes)
    }
    override fun loadShowSeconds() = snap.showSeconds
    override fun saveShowSeconds(showSeconds: Boolean) {
        snap = snap.copy(showSeconds = showSeconds)
    }
    override fun loadSoundSettings() = snap.soundSettings
    override fun saveSoundSettings(settings: SoundSettings) {
        snap = snap.copy(soundSettings = settings)
    }
    override fun loadGoalTitle() = snap.goalTitle
    override fun loadGoalDeadlineMillis() = snap.goalDeadlineMillis
    override fun loadGoalStartMillis() = snap.goalStartMillis
    override fun saveGlobalGoal(title: String, deadlineMillis: Long?, startMillis: Long?) {
        snap = snap.copy(goalTitle = title, goalDeadlineMillis = deadlineMillis, goalStartMillis = startMillis)
    }
    override fun loadPomodoroMinutes() = snap.pomodoroMinutes
    override fun loadPomodoroEndMillis() = snap.pomodoroEndMillis
    override fun savePomodoro(durationMinutes: Int, endMillis: Long?) {
        snap = snap.copy(pomodoroMinutes = durationMinutes, pomodoroEndMillis = endMillis)
    }
    override fun loadFocusIntention() = snap.focusIntention
    override fun saveFocusIntention(intention: String) {
        snap = snap.copy(focusIntention = intention)
    }
    override fun loadNetTimeSettings() = snap.netTimeSettings
    override fun saveNetTimeSettings(settings: NetTimeSettings) {
        snap = snap.copy(netTimeSettings = settings)
    }
}
