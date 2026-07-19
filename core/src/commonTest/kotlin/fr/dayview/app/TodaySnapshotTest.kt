package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TodaySnapshotTest {
    private fun controllerWith(snapshot: DayPreferencesSnapshot, nowMillis: Long) = DayViewController(
        DefaultDayPreferences,
        CoroutineScope(Dispatchers.Unconfined),
        initialSnapshot = snapshot,
        initialNow = Instant.fromEpochMilliseconds(nowMillis),
    )

    @Test
    fun mapsIdleDayProgress() {
        val now = 1_700_000_000_000L
        val state = controllerWith(
            DayPreferencesSnapshot(startMinutes = 540, endMinutes = 1080),
            now,
        ).stateFlow.value

        val snap = state.toTodaySnapshot()

        assertEquals(state.dayProgress.remaining.inWholeSeconds, snap.remainingSeconds)
        assertEquals(state.dayProgress.isFinished, snap.isFinished)
        assertEquals(
            currentMomentAngleDegrees(state.dayProgress.remainingRatio).toDouble(),
            snap.momentAngleDegrees,
        )
        assertEquals("IDLE", snap.pomodoroStatus)
        assertEquals("", snap.pomodoroClock)
    }

    @Test
    fun mapsActiveFocus() {
        val now = 1_700_000_000_000L
        val state = controllerWith(
            DayPreferencesSnapshot(
                startMinutes = 540,
                endMinutes = 1080,
                pomodoroMinutes = 25,
                pomodoroEnd = Instant.fromEpochMilliseconds(now) + 25.minutes,
                focusIntention = "Ship it",
            ),
            now,
        ).stateFlow.value

        val snap = state.toTodaySnapshot()

        assertEquals("ACTIVE", snap.pomodoroStatus)
        assertEquals("Ship it", snap.focusIntention)
        assertEquals(formatPomodoroClock(state.pomodoroProgress), snap.pomodoroClock)
    }

    @Test
    fun mapsGoalAndPomodoroMinutes() {
        val now = 1_700_000_000_000L
        val deadline = Instant.fromEpochMilliseconds(now) + 5.minutes
        val state = controllerWith(
            DayPreferencesSnapshot(
                startMinutes = 540,
                endMinutes = 1080,
                pomodoroMinutes = 30,
                goalTitle = "Ship it",
                goalDeadline = deadline,
            ),
            now,
        ).stateFlow.value

        val snap = state.toTodaySnapshot()

        assertEquals("Ship it", snap.goalTitle)
        assertEquals(true, snap.goalHasDeadline)
        assertEquals(deadline.toEpochMilliseconds(), snap.goalDeadlineEpochMillis)
        assertEquals(30L, snap.pomodoroMinutes)
    }

    // Regression: OVERTIME's clock must tick from overtimeElapsed, not read the
    // always-zero breakElapsed (which would freeze it at "00:00" — see Pomodoro.kt).
    @Test
    fun overtimeClockTicksInsteadOfFreezingAtZero() {
        val now = 1_700_000_000_000L
        val state = controllerWith(
            DayPreferencesSnapshot(
                startMinutes = 540,
                endMinutes = 1080,
                pomodoroMinutes = 25,
                pomodoroEnd = Instant.fromEpochMilliseconds(now) - 3.minutes,
                focusIntention = "Ship it",
            ),
            now,
        ).stateFlow.value

        val snap = state.toTodaySnapshot()

        assertEquals("OVERTIME", snap.pomodoroStatus)
        assertNotEquals("00:00", snap.pomodoroClock)
        assertEquals(formatOvertimeLabel(state.pomodoroProgress), snap.pomodoroClock)
    }

    private fun stateAt(now: Instant, end: Instant?, breakStart: Instant? = null, intention: String = "") = DayViewUiState(
        now = now, startMinutes = 8 * 60, endMinutes = 18 * 60, showSeconds = false,
        soundSettings = SoundSettings(), goalTitle = "", goalDeadlineText = "", goalDeadline = null,
        goalStartText = "", goalStart = null, pomodoroMinutes = 25, pomodoroEnd = end,
        focusIntention = intention, pomodoroSessionMinutes = if (end != null) 25 else null,
        breakStart = breakStart,
    )

    @Test
    fun overtimeStatusAndClock() {
        val end = Instant.parse("2026-07-18T10:00:00Z")
        val snap = stateAt(end + 3.minutes, end, intention = "write").toTodaySnapshot()
        assertEquals("OVERTIME", snap.pomodoroStatus)
        assertEquals("+3 min", snap.pomodoroClock)
        assertEquals("Focus · write · +3 min", snap.focusLine)
        assertEquals("+3 min", snap.menuBarTitle)
    }

    @Test
    fun blankIntentionDropsTheMiddleSegment() {
        val end = Instant.parse("2026-07-18T10:00:00Z")
        val active = stateAt(end - 10.minutes, end).toTodaySnapshot()
        assertEquals("Focus · 10:00", active.focusLine)
    }

    @Test
    fun breakLineAnchorsOnBreakStart() {
        val breakStart = Instant.parse("2026-07-18T10:00:00Z")
        val snap = stateAt(breakStart + 5.minutes, end = null, breakStart = breakStart).toTodaySnapshot()
        assertEquals("BREAK", snap.pomodoroStatus)
        assertEquals("Break · 05:00", snap.focusLine)
    }
}
