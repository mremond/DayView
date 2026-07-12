package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
