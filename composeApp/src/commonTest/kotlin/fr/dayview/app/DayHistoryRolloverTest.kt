package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DayHistoryRolloverTest {
    // Deterministic controller construction mirrors seededController but injects a store.
    private fun controllerWith(
        snapshot: DayPreferencesSnapshot,
        now: Instant,
        history: DayHistoryStore,
    ): DayViewController = DayViewController(
        preferences = InMemoryDayPreferences(snapshot),
        scope = CoroutineScope(Dispatchers.Unconfined),
        initialSnapshot = snapshot,
        initialNow = now,
        history = history,
    )

    @Test
    fun crossingMidnightArchivesThePreviousDay() = runTest {
        val history = InMemoryDayHistoryStore()
        val today = Instant.parse("2026-05-04T09:00:00Z")
        val yesterdayKey = dayKeyOf(Instant.parse("2026-05-03T12:00:00Z"))
        // Persisted state still carries yesterday's detours.
        val snapshot = DayPreferencesSnapshot(
            detoursDayKey = yesterdayKey,
            detours = listOf(DetourEpisode(Instant.parse("2026-05-03T11:00:00Z"), Instant.parse("2026-05-03T11:10:00Z"), "slack")),
        )
        controllerWith(snapshot, today, history)

        val archived = history.read(yesterdayKey)
        assertEquals(yesterdayKey, archived?.dayKey)
        assertEquals(1, archived?.detours?.size)
    }

    @Test
    fun coldLaunchArchivesTheCalendarBusyLayerOfThePreviousDay() = runTest {
        val history = InMemoryDayHistoryStore()
        val today = Instant.parse("2026-05-04T09:00:00Z")
        val yesterdayKey = dayKeyOf(Instant.parse("2026-05-03T12:00:00Z"))
        // A cold launch rebuilds the controller from the persisted snapshot, which now carries
        // yesterday's calendar-busy layer day-tagged to yesterday. Before the fix these fields
        // were transient (never persisted), so the archived record lost them.
        val snapshot = DayPreferencesSnapshot(
            busyDayKey = yesterdayKey,
            busyIntervals = listOf(
                BusyInterval(
                    Instant.parse("2026-05-03T10:00:00Z"),
                    Instant.parse("2026-05-03T11:00:00Z"),
                    listOf("Standup"),
                    "cal-a",
                ),
            ),
            availableCalendars = listOf(CalendarInfo("cal-a", "Work")),
            detoursDayKey = yesterdayKey,
        )
        controllerWith(snapshot, today, history)

        val archived = history.read(yesterdayKey)
        assertEquals(yesterdayKey, archived?.dayKey)
        assertEquals(1, archived?.busyIntervals?.size)
        assertEquals(mapOf("cal-a" to "Work"), archived?.calendarNames)
    }

    @Test
    fun noArchiveWhenPersistedDayIsToday() = runTest {
        val history = InMemoryDayHistoryStore()
        val today = Instant.parse("2026-05-04T09:00:00Z")
        val todayKey = dayKeyOf(today)
        val snapshot = DayPreferencesSnapshot(detoursDayKey = todayKey, detours = emptyList())
        controllerWith(snapshot, today, history)

        assertEquals(emptyList(), history.listDays(Long.MIN_VALUE..Long.MAX_VALUE))
    }
}
