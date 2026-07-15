package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private fun t(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

private fun testController(
    preferences: InMemoryDayPreferences,
    nowMillis: Long,
    onLocalWrite: () -> Unit = {},
) = DayViewController(
    preferences,
    CoroutineScope(Dispatchers.Unconfined),
    initialSnapshot = preferences.current,
    initialNow = t(nowMillis),
    onLocalWrite = onLocalWrite,
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

        assertEquals(t(1_000L), state.now)
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
    fun updateNetTimeDataPersistsTheBusyLayerDayTagged() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 10_000L)
        val expectedDay = dayKeyOf(t(10_000L))

        controller.updateNetTimeData(
            hasPermission = true,
            busyIntervals = listOf(BusyInterval(t(11_000L), t(12_000L), listOf("Standup"), "cal-a")),
            availableCalendars = listOf(CalendarInfo("cal-a", "Work")),
        )

        assertEquals(expectedDay, preferences.current.busyDayKey)
        assertEquals(1, preferences.current.busyIntervals.size)
        assertEquals(listOf(CalendarInfo("cal-a", "Work")), preferences.current.availableCalendars)
    }

    @Test
    fun updateNetTimeDataWithoutBusyLayerDoesNotDayTagOrPersist() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 10_000L)

        controller.updateNetTimeData(hasPermission = false, busyIntervals = emptyList(), availableCalendars = emptyList())

        // No busy layer to preserve: the day is not tagged, so an otherwise-empty day is not
        // spuriously made archivable, and no snapshot write happens.
        assertEquals(-1L, preferences.current.busyDayKey)
        assertEquals(emptyList(), preferences.current.busyIntervals)
    }

    @Test
    fun goalDeadlineIsPersistedOnlyWhenTheDraftIsCommitted() {
        val initialDeadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadline = initialDeadline),
        )
        val controller = testController(preferences, 10_000L)
        val updatedText = "25/12/2026 19:45"
        val updatedDeadline = parseGoalDeadline(updatedText)!!

        controller.setGoalDeadlineText(updatedText)

        assertEquals(updatedText, controller.state.goalDeadlineText)
        assertEquals(initialDeadline, controller.state.goalDeadline)
        assertEquals(initialDeadline, preferences.current.goalDeadline)

        controller.commitGoalDeadline()

        assertEquals(updatedDeadline, controller.state.goalDeadline)
        assertEquals(updatedDeadline, preferences.current.goalDeadline)
    }

    @Test
    fun invalidDeadlineDraftDoesNotReplaceTheCommittedDeadline() {
        val initialDeadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadline = initialDeadline),
        )
        val controller = testController(preferences, 10_000L)

        controller.setGoalDeadlineText("25/12")
        controller.commitGoalDeadline()

        assertEquals("25/12", controller.state.goalDeadlineText)
        assertEquals(initialDeadline, controller.state.goalDeadline)
        assertEquals(initialDeadline, preferences.current.goalDeadline)
    }

    @Test
    fun blankDeadlineDraftClearsTheCommittedDeadline() {
        val initialDeadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadline = initialDeadline),
        )
        val controller = testController(preferences, 10_000L)

        controller.setGoalDeadlineText("")
        controller.commitGoalDeadline()

        assertEquals(null, controller.state.goalDeadline)
        assertEquals(null, preferences.current.goalDeadline)
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
        val expectedEnd = t(10_000L + 25 * 60_000L)

        assertEquals(expectedEnd, started.pomodoroEnd)
        assertEquals(expectedEnd, preferences.current.pomodoroEnd)

        controller.tick(expectedEnd)
        assertEquals(PomodoroStatus.BREAK, controller.state.pomodoroProgress.status)

        controller.closePomodoro(FocusClosureOutcome.TO_RESUME)
        assertEquals("Préparer la démonstration", controller.state.focusIntention)
        assertEquals(null, controller.state.pomodoroEnd)

        controller.startPomodoro()
        controller.closePomodoro(FocusClosureOutcome.COMPLETED)
        assertEquals("", controller.state.focusIntention)
        assertEquals("", preferences.current.focusIntention)
    }

    @Test
    fun onPreferencesChangedAdoptsExternalPersistedFields() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 10_000L)
        val focusEnd = t(10_000L + 50 * 60_000L)

        controller.onPreferencesChanged(
            DayPreferencesSnapshot(
                startMinutes = 9 * 60,
                endMinutes = 17 * 60,
                pomodoroMinutes = 50,
                pomodoroEnd = focusEnd,
                focusIntention = "Depuis la tuile",
            ),
        )

        assertEquals(9 * 60, controller.state.startMinutes)
        assertEquals(17 * 60, controller.state.endMinutes)
        assertEquals(focusEnd, controller.state.pomodoroEnd)
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
            DayPreferencesSnapshot(focusIntention = "Depuis la tuile", pomodoroEnd = t(20_000L)),
        )

        assertEquals(DayViewDestination.SETTINGS, controller.state.destination)
        assertEquals("25/12/2026 19:45", controller.state.goalDeadlineText)
        assertEquals(t(10_000L), controller.state.now)
        assertEquals("Depuis la tuile", controller.state.focusIntention)
    }

    @Test
    fun committingADeadlineDefaultsTheStartToNow() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 5_000L)

        controller.setGoalDeadlineText("24/12/2026 18:30")
        controller.commitGoalDeadline()

        assertEquals(t(5_000L), controller.state.goalStart)
        assertEquals(t(5_000L), preferences.current.goalStart)
    }

    @Test
    fun loadingADeadlineWithoutAStartBackfillsAndPersistsTheStart() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalTitle = "Livrer la Plaie", goalDeadline = deadline),
        )

        val controller = testController(preferences, 5_000L)

        assertEquals(t(5_000L), controller.state.goalStart)
        assertEquals(formatGoalDeadline(t(5_000L)), controller.state.goalStartText)
        assertEquals(t(5_000L), preferences.current.goalStart)
    }

    @Test
    fun clearingTheDeadlineClearsTheStart() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadline = deadline, goalStart = t(1_000L)),
        )
        val controller = testController(preferences, 5_000L)

        controller.setGoalDeadlineText("")
        controller.commitGoalDeadline()

        assertEquals(null, controller.state.goalStart)
        assertEquals(null, preferences.current.goalStart)
    }

    @Test
    fun editingAnExistingDeadlineKeepsTheStart() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadline = deadline, goalStart = t(1_000L)),
        )
        val controller = testController(preferences, 5_000L)

        controller.setGoalDeadlineText("26/12/2026 18:30")
        controller.commitGoalDeadline()

        assertEquals(t(1_000L), controller.state.goalStart)
    }

    @Test
    fun editingTheDeadlineBeforeAnExistingStartResetsTheStartToNow() {
        val start = parseGoalDeadline("20/12/2026 09:00")!!
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadline = deadline, goalStart = start),
        )
        val controller = testController(preferences, 5_000L)

        controller.setGoalDeadlineText("15/12/2026 18:30")
        controller.commitGoalDeadline()

        assertEquals(t(5_000L), controller.state.goalStart)
        assertEquals(t(5_000L), preferences.current.goalStart)
    }

    @Test
    fun commitGoalStartPersistsAValidEarlierStart() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadline = deadline, goalStart = t(5_000L)),
        )
        val controller = testController(preferences, 5_000L)

        controller.setGoalStartText("01/12/2026 09:00")
        controller.commitGoalStart()

        val expected = parseGoalDeadline("01/12/2026 09:00")!!
        assertEquals(expected, controller.state.goalStart)
        assertEquals(expected, preferences.current.goalStart)
    }

    @Test
    fun commitGoalStartRejectsAStartOnOrAfterTheDeadline() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadline = deadline, goalStart = t(5_000L)),
        )
        val controller = testController(preferences, 5_000L)

        controller.setGoalStartText("25/12/2026 09:00")
        controller.commitGoalStart()

        assertEquals(t(5_000L), controller.state.goalStart)
        assertEquals(t(5_000L), preferences.current.goalStart)
    }

    @Test
    fun commitGoalStartIsANoOpWhenNoDeadlineIsSet() {
        val preferences = InMemoryDayPreferences(DayPreferencesSnapshot(goalStart = t(5_000L)))
        val controller = testController(preferences, 5_000L)

        controller.setGoalStartText("01/12/2026 09:00")
        controller.commitGoalStart()

        assertEquals(t(5_000L), controller.state.goalStart)
        assertEquals(t(5_000L), preferences.current.goalStart)
    }

    @Test
    fun commitGoalStartIgnoresUnparseableInput() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadline = deadline, goalStart = t(5_000L)),
        )
        val controller = testController(preferences, 5_000L)

        controller.setGoalStartText("pas une date")
        controller.commitGoalStart()

        assertEquals(t(5_000L), controller.state.goalStart)
        assertEquals(t(5_000L), preferences.current.goalStart)
    }

    @Test
    fun externalSaveReachesTheControllerThroughObserve() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 10_000L)
        val observerScope = CoroutineScope(Dispatchers.Unconfined)
        val observerJob = observerScope.launch {
            preferences.snapshots.collect { controller.onPreferencesChanged(it) }
        }

        preferences.emitExternal(preferences.current.copy(pomodoroMinutes = 50, pomodoroEnd = t(1_800_000_000_000L)))
        preferences.emitExternal(preferences.current.copy(focusIntention = "Depuis la tuile"))

        assertEquals(t(1_800_000_000_000L), controller.state.pomodoroEnd)
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
        assertEquals(null, controller.state.pomodoroEnd)

        observerJob.cancel()
    }

    @Test
    fun showSecondsFalseTruncatesTheDayClockToTheMinute() {
        // 12:00:30 in the day-progress timezone, comfortably inside a 00:00–23:59 window.
        val zone = TimeZone.currentSystemDefault()
        val now = LocalDateTime(2026, 7, 11, 12, 0, 30).toInstant(zone).toEpochMilliseconds()
        fun stateWith(showSeconds: Boolean) = testController(
            InMemoryDayPreferences(
                DayPreferencesSnapshot(startMinutes = 0, endMinutes = 23 * 60 + 59, showSeconds = showSeconds),
            ),
            now,
        ).state

        // Full precision keeps the 30-second remainder; truncation drops it to the minute.
        assertEquals(30L, stateWith(showSeconds = true).dayProgress.remainingSeconds)
        assertEquals(0L, stateWith(showSeconds = false).dayProgress.remainingSeconds)
    }

    @Test
    fun addDetourStoresAnEpisodeEndingNowAndPersistsIt() {
        val preferences = InMemoryDayPreferences()
        val now = 1_800_000_000_000L // fixed instant well inside a day
        val controller = testController(preferences, now)

        controller.addDetour(" appel\nurgent ", 30)

        val stored = preferences.current
        assertEquals(dayKeyOf(t(now)), stored.detoursDayKey)
        val episode = stored.detours.single()
        assertEquals("appel urgent", episode.category)
        assertEquals(t(now), episode.end)
        assertEquals(30, episode.duration.inWholeMinutes)
        assertEquals(listOf("appel urgent"), stored.recentDetourCategories)
    }

    @Test
    fun addDetourIgnoresBlankCategories() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 1_800_000_000_000L)
        controller.addDetour("   ", 15)
        assertEquals(emptyList(), controller.state.detoursToday)
    }

    @Test
    fun addDetourOnANewDayReplacesTheStaleList() {
        val now = 1_800_000_000_000L
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(
                detoursDayKey = dayKeyOf(t(now)) - 1L,
                detours = listOf(DetourEpisode(t(1_000L), t(2_000L), "hier")),
            ),
        )
        val controller = testController(preferences, now)

        assertEquals(emptyList(), controller.state.detoursToday)
        controller.addDetour("Slack", 15)
        assertEquals(listOf("Slack"), controller.state.detoursToday.map { it.category })
        assertEquals(1, preferences.current.detours.size)
    }

    @Test
    fun capturingTheSameCategoryTwiceKeepsOneRecentEntry() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 1_800_000_000_000L)
        controller.addDetour("Slack", 15)
        controller.addDetour("slack", 15)
        assertEquals(listOf("slack"), controller.state.recentDetourCategories)
        assertEquals(2, controller.state.detoursToday.size)
    }

    @Test
    fun updateAndRemoveDetourEditTodayList() {
        val now = 1_800_000_000_000L
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, now)
        controller.addDetour("Slack", 15)
        controller.addDetourEpisode(DetourEpisode(t(now - 3_600_000L), t(now - 1_800_000L), "Appels"))

        // Episodes are kept sorted by start: "Appels" (1 h ago) precedes "Slack" (15 min ago).
        assertEquals(listOf("Appels", "Slack"), controller.state.detoursToday.map { it.category })

        controller.updateDetour(0, DetourEpisode(t(now - 3_600_000L), t(now - 900_000L), "Réunion"))
        assertEquals(listOf("Réunion", "Slack"), controller.state.detoursToday.map { it.category })
        assertEquals(45, controller.state.detoursToday.first().duration.inWholeMinutes)

        controller.removeDetour(1)
        assertEquals(listOf("Réunion"), preferences.current.detours.map { it.category })
    }

    @Test
    fun invalidDetourEditsAreRejected() {
        val now = 1_800_000_000_000L
        val controller = testController(InMemoryDayPreferences(), now)
        controller.addDetour("Slack", 15)

        controller.updateDetour(5, DetourEpisode(t(1L), t(2L), "hors limites"))
        controller.updateDetour(0, DetourEpisode(t(2_000L), t(1_000L), "inversé"))
        controller.updateDetour(0, DetourEpisode(t(1_000L), t(2_000L), "  "))

        assertEquals(listOf("Slack"), controller.state.detoursToday.map { it.category })
        assertTrue(controller.state.detoursTotalToday.inWholeMinutes == 15L)
    }

    @Test
    fun editsAndDeletesDoNotTouchRecentCategories() {
        val now = 1_800_000_000_000L
        val controller = testController(InMemoryDayPreferences(), now)
        controller.addDetour("Slack", 15)
        controller.updateDetour(0, DetourEpisode(t(now - 600_000L), t(now), "Réunion"))
        controller.removeDetour(0)
        assertEquals(listOf("Slack"), controller.state.recentDetourCategories)
    }

    @Test
    fun forgetRecentCategoryRemovesItFromStateAndPersists() {
        val preferences = InMemoryDayPreferences()
        val now = 1_800_000_000_000L
        val controller = testController(preferences, now)
        controller.addDetour("Slack", 15)
        controller.addDetour("dsfdsf", 15)

        controller.forgetRecentDetourCategory("DSFDSF")

        assertEquals(listOf("Slack"), controller.state.recentDetourCategories)
        assertEquals(listOf("Slack"), preferences.current.recentDetourCategories)
        // The day's episodes stay put; only the suggestion is forgotten.
        assertEquals(listOf("Slack", "dsfdsf"), controller.state.detoursToday.map { it.category })
    }

    @Test
    fun addPlannedObligationStoresItDayScopedAndCapsAtThree() {
        val preferences = InMemoryDayPreferences()
        val now = 1_800_000_000_000L
        val controller = testController(preferences, now)

        controller.addPlannedObligation(" Appel\nclient ")
        controller.addPlannedObligation("Facture")
        controller.addPlannedObligation("Courses")
        controller.addPlannedObligation("Trop") // over the cap, ignored

        val stored = preferences.current
        assertEquals(dayKeyOf(t(now)), stored.plannedObligationsDayKey)
        assertEquals(listOf("Appel client", "Facture", "Courses"), stored.plannedObligations)
    }

    @Test
    fun addPlannedObligationOnANewDayReplacesTheStaleList() {
        val now = 1_800_000_000_000L
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(
                plannedObligationsDayKey = dayKeyOf(t(now)) - 1L,
                plannedObligations = listOf("Vieux"),
            ),
        )
        val controller = testController(preferences, now)

        controller.addPlannedObligation("Neuf")

        assertEquals(listOf("Neuf"), preferences.current.plannedObligations)
    }

    @Test
    fun loadingSnapshotDedupesCaseInsensitiveDuplicateObligations() {
        val now = 1_800_000_000_000L
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(
                plannedObligationsDayKey = dayKeyOf(t(now)),
                plannedObligations = listOf("Appel", "appel", "Facture"),
            ),
        )
        val controller = testController(preferences, now)

        assertEquals(listOf("Appel", "Facture"), controller.state.plannedObligationsToday)
    }

    @Test
    fun completePlannedObligationMarksItDoneWithoutLoggingADetour() {
        val preferences = InMemoryDayPreferences()
        val now = 1_800_000_000_000L
        val controller = testController(preferences, now)
        controller.addPlannedObligation("Appel client")

        controller.completePlannedObligation("Appel client")

        val stored = preferences.current
        assertEquals(emptyList(), stored.plannedObligations)
        assertEquals(listOf("Appel client"), stored.plannedObligationsCompleted)
        assertEquals(emptyList(), stored.detours)
    }

    @Test
    fun completingAnObligationKeepsTheSlotUsedSoNoFourthCanBeAdded() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 10_000L)
        controller.addPlannedObligation("a")
        controller.addPlannedObligation("b")
        controller.addPlannedObligation("c")
        controller.completePlannedObligation("a")
        assertEquals(listOf("b", "c"), controller.state.plannedObligationsToday)
        assertEquals(3, controller.state.plannedObligationSlotsUsed)

        controller.addPlannedObligation("d")
        assertEquals(listOf("b", "c"), controller.state.plannedObligationsToday) // add refused: 2 active + 1 done = 3
    }

    @Test
    fun deletingAnObligationFreesASlotButCompletingDoesNot() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 10_000L)
        controller.addPlannedObligation("a")
        controller.addPlannedObligation("b")
        controller.completePlannedObligation("a") // active {b}, completed {a} → 2 used
        controller.removePlannedObligation("b") // active {} , completed {a} → 1 used
        assertEquals(1, controller.state.plannedObligationSlotsUsed)
        controller.addPlannedObligation("c")
        assertEquals(listOf("c"), controller.state.plannedObligationsToday)
    }

    @Test
    fun editPlannedObligationRenamesInPlaceAndPersists() {
        val preferences = InMemoryDayPreferences()
        val now = 1_800_000_000_000L
        val controller = testController(preferences, now)
        controller.addPlannedObligation("Appel")
        controller.addPlannedObligation("Facture")

        controller.editPlannedObligation(oldMotif = "Appel", newLabel = "Appel client")

        assertEquals(listOf("Appel client", "Facture"), controller.state.plannedObligationsToday)
        assertEquals(listOf("Appel client", "Facture"), preferences.current.plannedObligations)
    }

    @Test
    fun editPlannedObligationIgnoresRejectedEdits() {
        val preferences = InMemoryDayPreferences()
        val now = 1_800_000_000_000L
        val controller = testController(preferences, now)
        controller.addPlannedObligation("Appel")
        controller.addPlannedObligation("Facture")

        controller.editPlannedObligation(oldMotif = "Appel", newLabel = "  ") // blank
        controller.editPlannedObligation(oldMotif = "Appel", newLabel = "facture") // duplicate

        assertEquals(listOf("Appel", "Facture"), controller.state.plannedObligationsToday)
    }

    @Test
    fun openingSettingsStartsOnTheCategoryList() {
        val controller = testController(InMemoryDayPreferences(), 1_000L)
        controller.openSettingsCategory(SettingsCategory.SOUNDS)

        controller.openSettings()

        assertEquals(DayViewDestination.SETTINGS, controller.state.destination)
        assertEquals(null, controller.state.settingsCategory)
    }

    @Test
    fun openingACategoryDrillsIn() {
        val controller = testController(InMemoryDayPreferences(), 1_000L)
        controller.openSettings()

        controller.openSettingsCategory(SettingsCategory.DAY)

        assertEquals(SettingsCategory.DAY, controller.state.settingsCategory)
    }

    @Test
    fun closingACategoryReturnsToTheList() {
        val controller = testController(InMemoryDayPreferences(), 1_000L)
        controller.openSettings()
        controller.openSettingsCategory(SettingsCategory.DAY)

        controller.closeSettingsCategory()

        assertEquals(DayViewDestination.SETTINGS, controller.state.destination)
        assertEquals(null, controller.state.settingsCategory)
    }

    @Test
    fun setThemeModeUpdatesStateAndPersists() {
        val prefs = InMemoryDayPreferences()
        val controller = testController(prefs, 1_000L)

        controller.setThemeMode(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, controller.state.themeMode)
        assertEquals(ThemeMode.LIGHT, prefs.current.themeMode)
    }

    @Test
    fun completedSessionWithoutDriftRegistersACleanSession() {
        val now = 60L * 60_000L // 1h after epoch, still day 0
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(
                focusIntention = "Réviser le chapitre 3",
                pomodoroMinutes = 25,
                pomodoroEnd = t(now),
            ),
        )
        val controller = testController(preferences, now)

        controller.closePomodoro(FocusClosureOutcome.COMPLETED)

        assertEquals(1, controller.state.cleanSessionsToday)
        assertEquals(1, controller.state.cleanStreakDays)
        assertEquals(1, preferences.current.cleanSessions.cleanToday)
    }

    @Test
    fun progressedSessionDoesNotRegister() {
        val now = 60L * 60_000L
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(pomodoroMinutes = 25, pomodoroEnd = t(now)),
        )
        val controller = testController(preferences, now)

        controller.closePomodoro(FocusClosureOutcome.PROGRESSED)

        assertEquals(0, controller.state.cleanSessionsToday)
    }

    @Test
    fun overlappingDetourBlocksTheCleanSession() {
        val now = 60L * 60_000L
        // A 4-minute detour (24..20 min before now) sits fully inside the 25-minute window.
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(
                pomodoroMinutes = 25,
                pomodoroEnd = t(now),
                detoursDayKey = dayKeyOf(t(now)),
                detours = listOf(DetourEpisode(t(now - 24 * 60_000L), t(now - 20 * 60_000L), "call")),
            ),
        )
        val controller = testController(preferences, now)

        controller.closePomodoro(FocusClosureOutcome.COMPLETED)

        assertEquals(0, controller.state.cleanSessionsToday)
    }

    @Test
    fun setFontScalePersistsAndCoerces() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 10_000L)

        controller.setFontScale(1.3f)
        assertEquals(1.3f, controller.state.fontScale)
        assertEquals(1.3f, preferences.current.fontScale)

        // Out-of-range input is clamped.
        controller.setFontScale(5.0f)
        assertEquals(1.5f, controller.state.fontScale)
        assertEquals(1.5f, preferences.current.fontScale)
    }

    @Test
    fun addDetourKeepsFullSpanWhenStartPredatesWindow() {
        val zone = TimeZone.currentSystemDefault()
        // 08:30 local, just after the 08:00 window opens.
        val now = LocalDateTime(2026, 7, 12, 8, 30).toInstant(zone).toEpochMilliseconds()
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, now)

        controller.addDetour("longue lecture", 60) // would start 07:30, before the window

        val episode = controller.state.detoursToday.single()
        assertEquals(60, episode.duration.inWholeMinutes) // no longer clamped to 30
        assertEquals(t(now), episode.end) // still ends now; the full 60 min is preserved before it
    }

    @Test
    fun addDetourFloorsPathologicalStartAtLocalMidnight() {
        val zone = TimeZone.currentSystemDefault()
        val now = LocalDateTime(2026, 7, 12, 8, 30).toInstant(zone).toEpochMilliseconds()
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, now)

        controller.addDetour("marathon", 12 * 60) // 12 h would cross into the previous day

        val episode = controller.state.detoursToday.single()
        assertEquals(startOfLocalDay(t(now)), episode.start) // floored to today's 00:00
        assertEquals(t(now), episode.end)
    }

    @Test
    fun localMutationFiresTheLocalWriteHook() {
        val preferences = InMemoryDayPreferences()
        var localWrites = 0
        val controller = testController(preferences, 10_000L, onLocalWrite = { localWrites++ })

        controller.setStartMinutes(7 * 60 + 30)

        assertEquals(1, localWrites)
    }

    @Test
    fun externalPreferencesChangeDoesNotFireTheLocalWriteHook() {
        val preferences = InMemoryDayPreferences()
        var localWrites = 0
        val controller = testController(preferences, 10_000L, onLocalWrite = { localWrites++ })

        controller.onPreferencesChanged(
            DayPreferencesSnapshot(startMinutes = 9 * 60, endMinutes = 17 * 60),
        )

        assertEquals(0, localWrites)
    }

    @Test
    fun addDetourStoresSanitizedDescription() {
        val controller = testController(InMemoryDayPreferences(), 50_000_000L)
        controller.addDetour("Slack", 15, "reading, threads\nmore")
        val episode = controller.state.detoursToday.single()
        assertEquals("Slack", episode.category)
        assertEquals("reading, threads more", episode.description) // newline→space, comma kept
    }

    @Test
    fun updateDetourKeepsDescription() {
        val controller = testController(InMemoryDayPreferences(), 50_000_000L)
        controller.addDetour("Slack", 15, "note")
        val original = controller.state.detoursToday.single()
        controller.updateDetour(0, original.copy(description = "edited\nnote"))
        assertEquals("edited note", controller.state.detoursToday.single().description)
    }

    @Test
    fun offWindowTotalStateCountsDroppedEpisodes() {
        val zone = TimeZone.currentSystemDefault()
        val now = LocalDateTime(2026, 7, 12, 21, 0).toInstant(zone).toEpochMilliseconds() // evening, past 18:00
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, now)

        controller.addDetour("série", 45) // 20:15–21:00, entirely after the window

        assertEquals(45, controller.state.detoursOffWindowTotalToday.inWholeMinutes)
        assertEquals(controller.state.detoursTotalToday, controller.state.detoursOffWindowTotalToday)
    }

    @Test
    fun netTimeExcludesFocusBlocksButRingKeepsThem() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone)
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(
                startMinutes = 8 * 60,
                endMinutes = 18 * 60,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
        )
        val controller = testController(preferences, noon.toEpochMilliseconds())

        // A real meeting 14:00-15:00 and a focus block 15:30-16:30, both this afternoon.
        controller.updateNetTimeData(
            hasPermission = true,
            busyIntervals = listOf(
                BusyInterval(noon + 2.hours, noon + 3.hours, listOf("Atelier"), "work"),
                BusyInterval(noon + 3.hours + 30.minutes, noon + 4.hours + 30.minutes, listOf("Deep Focus"), "work"),
            ),
            availableCalendars = listOf(CalendarInfo("work", "Work")),
        )

        val net = controller.state.netTime!!
        // Only the meeting counts: focus block is excluded from both the day total and remaining.
        assertEquals(1.hours, net.busyRemaining)
        assertEquals((10.hours) - 1.hours, net.netDay)
        // Both intervals still draw on the ring.
        assertEquals(2, controller.state.busyBlockArcsState.size)
    }

    @Test
    fun setGoalDeadlineInstantBackfillsStartLikeCommit() {
        val controller = testController(InMemoryDayPreferences(), 10_000L)
        val deadline = t(10_000L) + 5.minutes

        controller.setGoalDeadlineInstant(deadline)

        assertEquals(deadline, controller.state.goalDeadline)
        // No prior start -> backfilled to now (initialNow = 10_000L).
        assertEquals(t(10_000L), controller.state.goalStart)
        assertEquals(formatGoalDeadline(deadline), controller.state.goalDeadlineText)
    }

    @Test
    fun setGoalDeadlineInstantNullClearsDeadline() {
        val controller = testController(InMemoryDayPreferences(), 10_000L)
        controller.setGoalDeadlineInstant(t(10_000L) + 5.minutes)

        controller.setGoalDeadlineInstant(null)

        assertEquals(null, controller.state.goalDeadline)
        assertEquals(null, controller.state.goalStart)
        assertEquals("", controller.state.goalDeadlineText)
    }

    @Test
    fun sessionFocusedTodayDerivesFromSessionIntervals() {
        val controller = testController(InMemoryDayPreferences(), 10_000L)
        val windowStart = controller.state.dayWindow.first
        controller.setFocusSessionIntervals(
            listOf(FocusPresenceInterval(windowStart, windowStart + 30.minutes)),
        )
        assertEquals(30.minutes, controller.state.sessionFocusedToday)
    }
}
