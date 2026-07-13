package fr.dayview.app

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DayViewSessionTest {
    @Test
    fun emitsInitialThenReactsToStartFocus() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 540, endMinutes = 1080),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()

        val subscription = session.subscribe { seen.add(it) }
        runCurrent()
        assertEquals("IDLE", seen.last().pomodoroStatus)

        session.startFocus("Ship it")
        runCurrent()
        assertEquals("ACTIVE", seen.last().pomodoroStatus)
        assertEquals("Ship it", seen.last().focusIntention)

        subscription.cancel()
    }

    @Test
    fun goalActionsUpdateSnapshot() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 540, endMinutes = 1080),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.setGoalTitle("Launch")
        runCurrent()
        assertEquals("Launch", seen.last().goalTitle)

        session.setGoalDeadline(1_700_000_300_000L)
        runCurrent()
        assertEquals(true, seen.last().goalHasDeadline)

        sub.cancel()
    }
}
