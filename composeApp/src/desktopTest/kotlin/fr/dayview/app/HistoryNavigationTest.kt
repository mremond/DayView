package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure controller-transition tests for the history navigation wiring. `DayViewApp` itself is
 * not hostable in `runComposeUiTest` (see the UI-test gotchas), so this exercises the
 * controller directly, mirroring [DayHistoryRolloverTest]'s deterministic construction.
 */
class HistoryNavigationTest {
    private fun controllerWith(
        snapshot: DayPreferencesSnapshot,
        now: kotlin.time.Instant,
        history: DayHistoryStore,
    ): DayViewController = DayViewController(
        preferences = InMemoryDayPreferences(snapshot),
        scope = CoroutineScope(Dispatchers.Unconfined),
        initialSnapshot = snapshot,
        initialNow = now,
        history = history,
    )

    @Test
    fun openHistorySwitchesDestinationAndClearsSelection() {
        val controller = controllerWith(DayPreferencesSnapshot(), midWindowNow(), InMemoryDayHistoryStore())

        controller.openHistory()

        assertEquals(DayViewDestination.HISTORY, controller.state.destination)
        assertNull(controller.state.selectedHistoryDay)
    }

    @Test
    fun openHistoryLoadsTheCurrentWeekRecords() {
        val now = midWindowNow()
        val todayKey = dayKeyOf(now)
        val history = InMemoryDayHistoryStore()
        val yesterdayKey = todayKey - 1
        val yesterday = DayHistoryRecord(
            dayKey = yesterdayKey, startMinutes = 480, endMinutes = 1080, focusIntention = "",
            busyIntervals = emptyList(), calendarNames = emptyMap(), netTimeSettings = NetTimeSettings(),
            focusPresenceIntervals = emptyList(), detours = emptyList(), cleanSessions = CleanSessionLedger(),
            pomodoroMinutes = 25, pomodoroEnd = null, goalTitle = "", goalDeadline = null, goalStart = null,
        )
        kotlinx.coroutines.runBlocking { history.write(yesterday) }
        val controller = controllerWith(DayPreferencesSnapshot(), now, history)

        controller.openHistory()

        assertEquals(7, controller.state.historyWeek.size)
        val loaded = controller.state.historyWeek.firstOrNull { it.dayKey == yesterdayKey }
        assertTrue(loaded != null && loaded.record != null)
    }

    @Test
    fun openHistoryDaySelectsADay() {
        val controller = controllerWith(DayPreferencesSnapshot(), midWindowNow(), InMemoryDayHistoryStore())
        controller.openHistory()

        controller.openHistoryDay(42L)

        assertEquals(42L, controller.state.selectedHistoryDay)
    }

    @Test
    fun closeHistoryGoesFromDayToWeekToToday() {
        val controller = controllerWith(DayPreferencesSnapshot(), midWindowNow(), InMemoryDayHistoryStore())
        controller.openHistory()
        controller.openHistoryDay(42L)

        controller.closeHistory()
        assertEquals(DayViewDestination.HISTORY, controller.state.destination)
        assertNull(controller.state.selectedHistoryDay)

        controller.closeHistory()
        assertEquals(DayViewDestination.TODAY, controller.state.destination)
    }
}
