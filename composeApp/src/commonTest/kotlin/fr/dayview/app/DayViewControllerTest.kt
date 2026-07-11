package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

private fun testController(
    preferences: InMemoryDayPreferences,
    nowMillis: Long,
) = DayViewController(
    preferences,
    CoroutineScope(Dispatchers.Unconfined),
    initialSnapshot = preferences.current,
    initialNowMillis = nowMillis,
)

class DayViewControllerTest {
    @Test
    fun controllerBuildsAConsistentStateFromPreferences() {
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(
                startMinutes = 7 * 60 + 30,
                endMinutes = 19 * 60,
                showSeconds = false,
                goalTitle = "Livrer la v2",
                pomodoroMinutes = 45,
                focusIntention = "Finaliser le parcours",
            ),
        )

        val state = testController(preferences, 1_000L).state

        assertEquals(1_000L, state.nowMillis)
        assertEquals(7 * 60 + 30, state.startMinutes)
        assertEquals(19 * 60, state.endMinutes)
        assertEquals(false, state.showSeconds)
        assertEquals("Livrer la v2", state.goalTitle)
        assertEquals(45, state.pomodoroProgress.durationMinutes)
        assertEquals("Finaliser le parcours", state.focusIntention)
    }

    @Test
    fun controllerPersistsDayAndFocusEdits() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 10_000L)

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
        val controller = testController(preferences, 10_000L)
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
        val controller = testController(preferences, 10_000L)

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
        val controller = testController(preferences, 10_000L)

        controller.setGoalDeadlineText("")
        controller.commitGoalDeadline()

        assertEquals(null, controller.state.goalDeadlineMillis)
        assertEquals(null, preferences.current.goalDeadlineMillis)
    }

    @Test
    fun controllerConstrainsTimesSelectedByThePlatformPicker() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 10_000L)

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
        val controller = testController(preferences, 10_000L)
        controller.setFocusIntention("Préparer la démonstration")

        controller.startPomodoro()
        val started = controller.state
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

    @Test
    fun onPreferencesChangedAdoptsExternalPersistedFields() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 10_000L)
        val focusEnd = 10_000L + 50 * 60_000L

        controller.onPreferencesChanged(
            DayPreferencesSnapshot(
                startMinutes = 9 * 60,
                endMinutes = 17 * 60,
                pomodoroMinutes = 50,
                pomodoroEndMillis = focusEnd,
                focusIntention = "Depuis la tuile",
            ),
        )

        assertEquals(9 * 60, controller.state.startMinutes)
        assertEquals(17 * 60, controller.state.endMinutes)
        assertEquals(focusEnd, controller.state.pomodoroEndMillis)
        assertEquals("Depuis la tuile", controller.state.focusIntention)
        assertEquals(true, controller.state.focusIsActive)
    }

    @Test
    fun onPreferencesChangedPreservesTransientUiState() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 10_000L)
        controller.openSettings()
        controller.setGoalDeadlineText("25/12/2026 19:45")

        controller.onPreferencesChanged(
            DayPreferencesSnapshot(focusIntention = "Depuis la tuile", pomodoroEndMillis = 20_000L),
        )

        assertEquals(DayViewDestination.SETTINGS, controller.state.destination)
        assertEquals("25/12/2026 19:45", controller.state.goalDeadlineText)
        assertEquals(10_000L, controller.state.nowMillis)
        assertEquals("Depuis la tuile", controller.state.focusIntention)
    }

    @Test
    fun committingADeadlineDefaultsTheStartToNow() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 5_000L)

        controller.setGoalDeadlineText("24/12/2026 18:30")
        controller.commitGoalDeadline()

        assertEquals(5_000L, controller.state.goalStartMillis)
        assertEquals(5_000L, preferences.current.goalStartMillis)
    }

    @Test
    fun loadingADeadlineWithoutAStartBackfillsAndPersistsTheStart() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalTitle = "Livrer la Plaie", goalDeadlineMillis = deadline),
        )

        val controller = testController(preferences, 5_000L)

        assertEquals(5_000L, controller.state.goalStartMillis)
        assertEquals(formatGoalDeadline(5_000L), controller.state.goalStartText)
        assertEquals(5_000L, preferences.current.goalStartMillis)
    }

    @Test
    fun clearingTheDeadlineClearsTheStart() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadlineMillis = deadline, goalStartMillis = 1_000L),
        )
        val controller = testController(preferences, 5_000L)

        controller.setGoalDeadlineText("")
        controller.commitGoalDeadline()

        assertEquals(null, controller.state.goalStartMillis)
        assertEquals(null, preferences.current.goalStartMillis)
    }

    @Test
    fun editingAnExistingDeadlineKeepsTheStart() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadlineMillis = deadline, goalStartMillis = 1_000L),
        )
        val controller = testController(preferences, 5_000L)

        controller.setGoalDeadlineText("26/12/2026 18:30")
        controller.commitGoalDeadline()

        assertEquals(1_000L, controller.state.goalStartMillis)
    }

    @Test
    fun editingTheDeadlineBeforeAnExistingStartResetsTheStartToNow() {
        val start = parseGoalDeadline("20/12/2026 09:00")!!
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadlineMillis = deadline, goalStartMillis = start),
        )
        val controller = testController(preferences, 5_000L)

        controller.setGoalDeadlineText("15/12/2026 18:30")
        controller.commitGoalDeadline()

        assertEquals(5_000L, controller.state.goalStartMillis)
        assertEquals(5_000L, preferences.current.goalStartMillis)
    }

    @Test
    fun commitGoalStartPersistsAValidEarlierStart() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadlineMillis = deadline, goalStartMillis = 5_000L),
        )
        val controller = testController(preferences, 5_000L)

        controller.setGoalStartText("01/12/2026 09:00")
        controller.commitGoalStart()

        val expected = parseGoalDeadline("01/12/2026 09:00")!!
        assertEquals(expected, controller.state.goalStartMillis)
        assertEquals(expected, preferences.current.goalStartMillis)
    }

    @Test
    fun commitGoalStartRejectsAStartOnOrAfterTheDeadline() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadlineMillis = deadline, goalStartMillis = 5_000L),
        )
        val controller = testController(preferences, 5_000L)

        controller.setGoalStartText("25/12/2026 09:00")
        controller.commitGoalStart()

        assertEquals(5_000L, controller.state.goalStartMillis)
        assertEquals(5_000L, preferences.current.goalStartMillis)
    }

    @Test
    fun commitGoalStartIsANoOpWhenNoDeadlineIsSet() {
        val preferences = InMemoryDayPreferences(DayPreferencesSnapshot(goalStartMillis = 5_000L))
        val controller = testController(preferences, 5_000L)

        controller.setGoalStartText("01/12/2026 09:00")
        controller.commitGoalStart()

        assertEquals(5_000L, controller.state.goalStartMillis)
        assertEquals(5_000L, preferences.current.goalStartMillis)
    }

    @Test
    fun commitGoalStartIgnoresUnparseableInput() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadlineMillis = deadline, goalStartMillis = 5_000L),
        )
        val controller = testController(preferences, 5_000L)

        controller.setGoalStartText("pas une date")
        controller.commitGoalStart()

        assertEquals(5_000L, controller.state.goalStartMillis)
        assertEquals(5_000L, preferences.current.goalStartMillis)
    }

    @Test
    fun externalSaveReachesTheControllerThroughObserve() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 10_000L)
        val observerScope = CoroutineScope(Dispatchers.Unconfined)
        val observerJob = observerScope.launch {
            preferences.snapshots.collect { controller.onPreferencesChanged(it) }
        }

        preferences.emitExternal(preferences.current.copy(pomodoroMinutes = 50, pomodoroEndMillis = 1_800_000_000_000L))
        preferences.emitExternal(preferences.current.copy(focusIntention = "Depuis la tuile"))

        assertEquals(1_800_000_000_000L, controller.state.pomodoroEndMillis)
        assertEquals("Depuis la tuile", controller.state.focusIntention)

        observerJob.cancel()
    }

    @Test
    fun selfWritesReconcileWithoutClobberingStateOrDrafts() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 10_000L)
        val observerScope = CoroutineScope(Dispatchers.Unconfined)
        val observerJob = observerScope.launch {
            preferences.snapshots.collect { controller.onPreferencesChanged(it) }
        }

        // An unsaved draft plus a persisting edit: the persisting edit's echo is
        // dropped by the pending-self-write guard, so both the draft and the
        // just-written value survive.
        controller.setGoalDeadlineText("25/12/2026 19:45")
        controller.setGoalTitle("Publier la version 1")

        assertEquals("Publier la version 1", controller.state.goalTitle)
        assertEquals("25/12/2026 19:45", controller.state.goalDeadlineText)

        // closePomodoro now persists a single atomic snapshot; its echo must not
        // revert the final intention/outcome.
        controller.setFocusIntention("Préparer la démonstration")
        controller.startPomodoro()
        controller.closePomodoro(FocusClosureOutcome.COMPLETED)

        assertEquals("", controller.state.focusIntention)
        assertEquals(FocusClosureOutcome.COMPLETED, controller.state.lastFocusClosure)
        assertEquals(null, controller.state.pomodoroEndMillis)

        observerJob.cancel()
    }
}

private class InMemoryDayPreferences(
    initial: DayPreferencesSnapshot = DayPreferencesSnapshot(),
) : DayPreferences {
    private val state = MutableStateFlow(initial)

    override val snapshots: Flow<DayPreferencesSnapshot> = state.asStateFlow()

    override suspend fun persist(snapshot: DayPreferencesSnapshot) {
        state.value = snapshot
    }

    val current: DayPreferencesSnapshot get() = state.value

    /** Simulates a write that lands from outside this process (tile/widget/alarm). */
    fun emitExternal(snapshot: DayPreferencesSnapshot) {
        state.value = snapshot
    }
}
