package fr.dayview.app

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class DayHistoryRecordTest {
    // Must match the zone the getters under test use: dayKeyOf(dayNow) (called with no
    // explicit timeZone by DayViewController) defaults to TimeZone.currentSystemDefault().
    // Using a fixed UTC zone here would desync the record's dayKey from that getter's
    // computed dayKey on any host west/east enough of UTC, making the test flaky.
    private val tz = TimeZone.currentSystemDefault()
    private val dayKey = LocalDate(2026, 5, 4).toEpochDays().toLong()

    private fun instantAt(hour: Int, minute: Int): Instant = LocalDate.fromEpochDays(dayKey.toInt()).atTime(LocalTime(hour, minute)).toInstant(tz)

    @Test
    fun frozenStateReprojectsBusyArcsOnTheRecordedDay() {
        val record = DayHistoryRecord(
            dayKey = dayKey,
            startMinutes = 8 * 60,
            endMinutes = 18 * 60,
            focusIntention = "Ship the plan",
            busyIntervals = listOf(
                BusyInterval(instantAt(9, 0), instantAt(10, 30), listOf("Standup"), "cal-a"),
            ),
            calendarNames = mapOf("cal-a" to "Work"),
            netTimeSettings = NetTimeSettings(enabled = true, includedCalendarIds = setOf("cal-a")),
            focusPresenceIntervals = emptyList(),
            focusSessionIntervals = emptyList(),
            detours = emptyList(),
            cleanSessions = CleanSessionLedger(dayKey = dayKey, cleanToday = 2),
            pomodoroMinutes = 25,
            pomodoroEnd = null,
            goalTitle = "",
            goalDeadline = null,
            goalStart = null,
        )

        val state = record.toFrozenUiState(tz)

        // The frozen "now" lands on the recorded day, not today.
        assertEquals(dayKey, dayKeyOf(state.now, tz))
        // Busy projection runs through the existing pipeline and yields one arc.
        assertEquals(1, state.busyBlockArcsState.size)
        // The day is fully elapsed in the replay (now == window end).
        assertTrue(state.dayProgress.isFinished)
        assertEquals(2, state.cleanSessionsToday)
    }

    @Test
    fun liveNowKeepsTodayInProgress() {
        val record = DayHistoryRecord(
            dayKey = dayKey,
            startMinutes = 8 * 60,
            endMinutes = 18 * 60,
            focusIntention = "",
            busyIntervals = emptyList(),
            calendarNames = emptyMap(),
            netTimeSettings = NetTimeSettings(),
            focusPresenceIntervals = emptyList(),
            detours = emptyList(),
            cleanSessions = CleanSessionLedger(),
            pomodoroMinutes = 25,
            pomodoroEnd = null,
            goalTitle = "",
            goalDeadline = null,
            goalStart = null,
        )

        // Today's cell is projected at the live instant, not frozen at the window end, so
        // the ring reads as in progress rather than finished.
        val nowMidday = instantAt(12, 0)
        val state = record.toFrozenUiState(tz, now = nowMidday)

        assertEquals(nowMidday, state.now)
        assertFalse(state.dayProgress.isFinished)
    }

    @Test
    fun recordRoundTripsThroughUiState() {
        val record = DayHistoryRecord(
            dayKey = dayKey,
            startMinutes = 9 * 60,
            endMinutes = 17 * 60,
            focusIntention = "Focus",
            busyIntervals = emptyList(),
            calendarNames = emptyMap(),
            netTimeSettings = NetTimeSettings(),
            focusPresenceIntervals = listOf(FocusPresenceInterval(instantAt(9, 0), instantAt(9, 30))),
            focusSessionIntervals = listOf(FocusPresenceInterval(instantAt(9, 0), instantAt(9, 45))),
            detours = listOf(DetourEpisode(instantAt(11, 0), instantAt(11, 15), "slack", "reading threads")),
            cleanSessions = CleanSessionLedger(dayKey = dayKey, cleanToday = 1),
            pomodoroMinutes = 30,
            pomodoroEnd = null,
            goalTitle = "Deliver",
            goalDeadline = instantAt(17, 0),
            goalStart = instantAt(9, 0),
        )

        val back = record.toFrozenUiState(tz).toHistoryRecord(dayKey, tz)

        assertEquals(record, back)
    }
}
