package fr.dayview.app

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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

    @Test
    fun androidStyleCloseRecordsEngagedTimeMinusDetours() = runTest {
        // Full-day window so the session cannot be clipped by dayWindow.
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 60),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            derivesEngagedFromSessions = true,
        )
        controller.setFocusIntention("Ship it")
        controller.startPomodoro() // start at T0, end = T0 + 60m
        controller.tick(Instant.fromEpochMilliseconds(1_700_000_000_000L + 30 * 60_000L))
        controller.closePomodoro(FocusClosureOutcome.PROGRESSED) // early close at +30m

        // Engaged = 30 minutes (no detours), independent of strict focus.
        assertEquals(30.minutes, controller.stateFlow.value.sessionFocusedToday)
    }

    @Test
    fun flagOffClosePomodoroDoesNotRecordEngagedTime() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 60),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            derivesEngagedFromSessions = false,
        )
        controller.setFocusIntention("Ship it")
        controller.startPomodoro()
        controller.tick(Instant.fromEpochMilliseconds(1_700_000_000_000L + 30 * 60_000L))
        controller.closePomodoro(FocusClosureOutcome.PROGRESSED)

        assertEquals(true, controller.stateFlow.value.focusSessionIntervals.isEmpty())
        assertEquals(Duration.ZERO, controller.stateFlow.value.sessionFocusedToday)
    }

    @Test
    fun flagOffStopPomodoroDoesNotRecordEngagedTime() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 60),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            derivesEngagedFromSessions = false,
        )
        controller.setFocusIntention("Ship it")
        controller.startPomodoro()
        controller.tick(Instant.fromEpochMilliseconds(1_700_000_000_000L + 30 * 60_000L))
        controller.stopPomodoro()

        assertEquals(true, controller.stateFlow.value.focusSessionIntervals.isEmpty())
        assertEquals(Duration.ZERO, controller.stateFlow.value.sessionFocusedToday)
    }

    @Test
    fun overtimeIsCappedAtPomodoroEnd() = runTest {
        // Midday UTC start (unlike the other fixtures' late-evening instant) so a
        // 90-minute tick cannot cross local midnight and get clipped by dayWindow
        // regardless of the host machine's time zone.
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 60),
            initialNow = start,
            derivesEngagedFromSessions = true,
        )
        controller.setFocusIntention("Ship it")
        controller.startPomodoro() // start at T0, end = T0 + 60m
        controller.tick(start + 90.minutes)
        controller.closePomodoro(FocusClosureOutcome.COMPLETED) // closed well past the 60m end

        // Overtime past pomodoroEnd is not counted as engaged time.
        assertEquals(60.minutes, controller.stateFlow.value.sessionFocusedToday)
    }

    @Test
    fun stopPomodoroRecordsEngagedTime() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 60),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            derivesEngagedFromSessions = true,
        )
        controller.setFocusIntention("Ship it")
        controller.startPomodoro() // start at T0, end = T0 + 60m
        controller.tick(Instant.fromEpochMilliseconds(1_700_000_000_000L + 20 * 60_000L))
        controller.stopPomodoro()

        assertEquals(20.minutes, controller.stateFlow.value.sessionFocusedToday)
    }

    @Test
    fun closeFocusCompletedEndsSessionAndClearsIntention() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 25),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.startFocus("Ship it")
        runCurrent()
        assertEquals("ACTIVE", seen.last().pomodoroStatus)

        session.closeFocus("COMPLETED")
        runCurrent()
        assertEquals("IDLE", seen.last().pomodoroStatus)
        assertEquals("", seen.last().focusIntention)

        sub.cancel()
    }

    @Test
    fun closeFocusToResumeKeepsIntention() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 25),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.startFocus("Ship it")
        runCurrent()
        session.closeFocus("TO_RESUME")
        runCurrent()
        assertEquals("IDLE", seen.last().pomodoroStatus)
        assertEquals("Ship it", seen.last().focusIntention)

        sub.cancel()
    }

    @Test
    fun closeFocusUnknownOutcomeDefaultsToCompleted() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 25),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.startFocus("Ship it")
        runCurrent()
        session.closeFocus("garbage")
        runCurrent()
        // COMPLETED semantics: session ends, intention cleared.
        assertEquals("IDLE", seen.last().pomodoroStatus)
        assertEquals("", seen.last().focusIntention)

        sub.cancel()
    }

    @Test
    fun closeFocusProgressedRecordsProgressedOutcome() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 25),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.startFocus("Ship it")
        runCurrent()
        session.closeFocus("PROGRESSED")
        runCurrent()
        assertEquals("IDLE", seen.last().pomodoroStatus)
        assertEquals("", seen.last().focusIntention)
        // PROGRESSED and COMPLETED are indistinguishable at snapshot level (both clear
        // the intention), so pin the mapping via the recorded closure outcome.
        assertEquals(FocusClosureOutcome.PROGRESSED, controller.stateFlow.value.lastFocusClosure)

        sub.cancel()
    }
}
