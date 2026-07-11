package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DayViewControllerTest {
    @Test
    fun controllerBuildsAConsistentStateFromPreferences() {
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(
                startMinutes = 7 * 60 + 30,
                endMinutes = 19 * 60,
                showSeconds = false,
                goalTitle = "Livrer DayView",
                pomodoroMinutes = 45,
                focusIntention = "Finaliser le parcours",
            ),
        )

        val state = DayViewController(preferences, initialNowMillis = 1_000L).state

        assertEquals(1_000L, state.nowMillis)
        assertEquals(7 * 60 + 30, state.startMinutes)
        assertEquals(19 * 60, state.endMinutes)
        assertEquals(false, state.showSeconds)
        assertEquals("Livrer DayView", state.goalTitle)
        assertEquals(45, state.pomodoroProgress.durationMinutes)
        assertEquals("Finaliser le parcours", state.focusIntention)
    }

    @Test
    fun controllerPersistsDayAndFocusEdits() {
        val preferences = InMemoryDayPreferences()
        val controller = DayViewController(preferences, initialNowMillis = 10_000L)

        controller.setStartMinutes(7 * 60 + 30)
        controller.setEndMinutes(18 * 60 + 30)
        controller.setShowSeconds(false)
        controller.setGoalTitle("Publier la version 1")
        controller.setFocusIntention("Écrire les notes de version")
        controller.changePomodoroDuration(5)

        assertEquals(7 * 60 + 30, preferences.current.startMinutes)
        assertEquals(18 * 60 + 30, preferences.current.endMinutes)
        assertEquals(false, preferences.current.showSeconds)
        assertEquals("Publier la version 1", preferences.current.goalTitle)
        assertEquals("Écrire les notes de version", preferences.current.focusIntention)
        assertEquals(30, preferences.current.pomodoroMinutes)
    }

    @Test
    fun goalDeadlineIsPersistedOnlyWhenTheDraftIsCommitted() {
        val initialDeadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadlineMillis = initialDeadline),
        )
        val controller = DayViewController(preferences, initialNowMillis = 10_000L)
        val updatedText = "25/12/2026 19:45"
        val updatedDeadline = parseGoalDeadline(updatedText)!!

        controller.setGoalDeadlineText(updatedText)

        assertEquals(updatedText, controller.state.goalDeadlineText)
        assertEquals(initialDeadline, controller.state.goalDeadlineMillis)
        assertEquals(initialDeadline, preferences.current.goalDeadlineMillis)

        controller.commitGoalDeadline()

        assertEquals(updatedDeadline, controller.state.goalDeadlineMillis)
        assertEquals(updatedDeadline, preferences.current.goalDeadlineMillis)
    }

    @Test
    fun invalidDeadlineDraftDoesNotReplaceTheCommittedDeadline() {
        val initialDeadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadlineMillis = initialDeadline),
        )
        val controller = DayViewController(preferences, initialNowMillis = 10_000L)

        controller.setGoalDeadlineText("25/12")
        controller.commitGoalDeadline()

        assertEquals("25/12", controller.state.goalDeadlineText)
        assertEquals(initialDeadline, controller.state.goalDeadlineMillis)
        assertEquals(initialDeadline, preferences.current.goalDeadlineMillis)
    }

    @Test
    fun blankDeadlineDraftClearsTheCommittedDeadline() {
        val initialDeadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadlineMillis = initialDeadline),
        )
        val controller = DayViewController(preferences, initialNowMillis = 10_000L)

        controller.setGoalDeadlineText("")
        controller.commitGoalDeadline()

        assertEquals(null, controller.state.goalDeadlineMillis)
        assertEquals(null, preferences.current.goalDeadlineMillis)
    }

    @Test
    fun controllerConstrainsTimesSelectedByThePlatformPicker() {
        val preferences = InMemoryDayPreferences()
        val controller = DayViewController(preferences, initialNowMillis = 10_000L)

        controller.setStartMinutes(20 * 60)
        assertEquals(17 * 60 + 30, controller.state.startMinutes)

        controller.setEndMinutes(6 * 60)
        assertEquals(18 * 60, controller.state.endMinutes)

        controller.setEndMinutes(23 * 60 + 59)
        assertEquals(23 * 60 + 59, controller.state.endMinutes)
    }

    @Test
    fun controllerOwnsTheCompleteFocusLifecycle() {
        val preferences = InMemoryDayPreferences()
        val controller = DayViewController(preferences, initialNowMillis = 10_000L)
        controller.setFocusIntention("Préparer la démonstration")

        val started = controller.startPomodoro()
        val expectedEnd = 10_000L + 25 * 60_000L

        assertEquals(expectedEnd, started.pomodoroEndMillis)
        assertEquals(expectedEnd, preferences.current.pomodoroEndMillis)

        controller.tick(expectedEnd)
        assertEquals(PomodoroStatus.BREAK, controller.state.pomodoroProgress.status)

        controller.closePomodoro(FocusClosureOutcome.TO_RESUME)
        assertEquals("Préparer la démonstration", controller.state.focusIntention)
        assertEquals(null, controller.state.pomodoroEndMillis)

        controller.startPomodoro()
        controller.closePomodoro(FocusClosureOutcome.COMPLETED)
        assertEquals("", controller.state.focusIntention)
        assertEquals("", preferences.current.focusIntention)
    }
}

private class InMemoryDayPreferences(
    initial: DayPreferencesSnapshot = DayPreferencesSnapshot(),
) : DayPreferences {
    var current: DayPreferencesSnapshot = initial
        private set

    override fun snapshot(): DayPreferencesSnapshot = current
    override fun loadStartMinutes(): Int = current.startMinutes
    override fun loadEndMinutes(): Int = current.endMinutes
    override fun loadShowSeconds(): Boolean = current.showSeconds
    override fun loadSoundSettings(): SoundSettings = current.soundSettings
    override fun loadGoalTitle(): String = current.goalTitle
    override fun loadGoalDeadlineMillis(): Long? = current.goalDeadlineMillis
    override fun loadPomodoroMinutes(): Int = current.pomodoroMinutes
    override fun loadPomodoroEndMillis(): Long? = current.pomodoroEndMillis
    override fun loadFocusIntention(): String = current.focusIntention

    override fun saveDayRange(startMinutes: Int, endMinutes: Int) {
        current = current.copy(startMinutes = startMinutes, endMinutes = endMinutes)
    }

    override fun saveShowSeconds(showSeconds: Boolean) {
        current = current.copy(showSeconds = showSeconds)
    }

    override fun saveSoundSettings(settings: SoundSettings) {
        current = current.copy(soundSettings = settings)
    }

    override fun saveGlobalGoal(title: String, deadlineMillis: Long?) {
        current = current.copy(goalTitle = title, goalDeadlineMillis = deadlineMillis)
    }

    override fun savePomodoro(durationMinutes: Int, endMillis: Long?) {
        current = current.copy(pomodoroMinutes = durationMinutes, pomodoroEndMillis = endMillis)
    }

    override fun saveFocusIntention(intention: String) {
        current = current.copy(focusIntention = intention)
    }
}
