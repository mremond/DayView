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
        // Pinned to a fixed mid-week day so "yesterday" always lands inside the current
        // Monday→Sunday history week — otherwise this fails whenever CI runs on a Monday.
        val now = midWeekNow()
        val todayKey = dayKeyOf(now)
        val history = InMemoryDayHistoryStore()
        val yesterdayKey = todayKey - 1
        val yesterday = DayHistoryRecord(
            dayKey = yesterdayKey, startMinutes = 480, endMinutes = 1080, focusIntention = "",
            busyIntervals = emptyList(), calendarNames = emptyMap(), netTimeSettings = NetTimeSettings(),
            focusPresenceIntervals = emptyList(), focusSessionIntervals = emptyList(),
            detours = emptyList(), cleanSessions = CleanSessionLedger(),
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
    fun openHistoryBuildsTodaysCellFromLiveState() {
        val now = midWindowNow()
        val todayKey = dayKeyOf(now)
        // Live state carries today's calendar-busy layer; today is never archived, so the cell
        // must be built from the live state rather than read (as null) from the store.
        val snapshot = DayPreferencesSnapshot(
            netTimeSettings = NetTimeSettings(enabled = true),
            busyDayKey = todayKey,
            busyIntervals = listOf(
                BusyInterval(now, now + kotlin.time.Duration.parse("30m"), listOf("Standup"), "cal-a"),
            ),
            availableCalendars = listOf(CalendarInfo("cal-a", "Work")),
        )
        val controller = controllerWith(snapshot, now, InMemoryDayHistoryStore())

        controller.openHistory()

        val todayRecord = controller.state.historyWeek.firstOrNull { it.dayKey == todayKey }?.record
        assertTrue(todayRecord != null)
        assertEquals(1, todayRecord.busyIntervals.size)
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
