package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class DayHistoryRolloverTest {
    // Deterministic controller construction mirrors seededController but injects a store.
    private fun controllerWith(
        snapshot: DayPreferencesSnapshot,
        now: Instant,
        history: DayHistoryStore,
        focusContributions: FocusContributionStore? = null,
        deviceId: String? = null,
        initialFocusSessionIntervals: List<FocusPresenceInterval> = emptyList(),
    ): DayViewController = DayViewController(
        preferences = InMemoryDayPreferences(snapshot),
        scope = CoroutineScope(Dispatchers.Unconfined),
        initialSnapshot = snapshot,
        initialNow = now,
        history = history,
        focusContributions = focusContributions,
        deviceId = deviceId,
        initialFocusSessionIntervals = initialFocusSessionIntervals,
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

    @Test
    fun archiveWritesOwnFocusContributionAndReadUnionsForeign() = runTest {
        val contributions = InMemoryFocusContributionStore()
        val history = InMemoryDayHistoryStore()
        // "today" is Tuesday 2026-05-05 and the archived day is Monday 2026-05-04, so both
        // fall in the same Monday-Sunday week that openHistory's historyWeek covers (unlike
        // the 2026-05-04/2026-05-03 pair used elsewhere in this file, where 05-03 is a Sunday
        // belonging to the *previous* week and would never show up in historyWeek).
        val today = Instant.parse("2026-05-05T09:00:00Z")
        val yesterdayKey = dayKeyOf(Instant.parse("2026-05-04T12:00:00Z"))
        val ownSession = FocusPresenceInterval(
            Instant.parse("2026-05-04T10:00:00Z"),
            Instant.parse("2026-05-04T10:30:00Z"),
        )
        val snapshot = DayPreferencesSnapshot(focusSessionDayKey = yesterdayKey)
        val controller = controllerWith(
            snapshot,
            today,
            history,
            focusContributions = contributions,
            deviceId = "self",
            initialFocusSessionIntervals = listOf(ownSession),
        )

        val archived = history.read(yesterdayKey)
        val ownContribution = contributions.listForDay(yesterdayKey).single()
        assertEquals("self", ownContribution.deviceId)
        assertEquals(listOf(ownSession), archived?.focusSessionIntervals)
        assertEquals(listOf(ownSession), ownContribution.session)

        val foreignSession = FocusPresenceInterval(
            Instant.parse("2026-05-04T14:00:00Z"),
            Instant.parse("2026-05-04T14:20:00Z"),
        )
        contributions.write(FocusContribution(yesterdayKey, "other", emptyList(), listOf(foreignSession)))
        controller.openHistory()

        val cell = controller.stateFlow.value.historyWeek.first { it.dayKey == yesterdayKey }
        val mergedSessions = cell.record!!.focusSessionIntervals
        assertEquals(2, mergedSessions.size)
        assertTrue(mergedSessions.contains(ownSession))
        assertTrue(mergedSessions.contains(foreignSession))
    }
}
