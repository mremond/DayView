package fr.dayview.app

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    @Test
    fun dayWindowSettersRoundTripAndClamp() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 540, endMinutes = 1080),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.setDayStart(600)
        runCurrent()
        assertEquals(600L, seen.last().startMinutes)

        session.setDayEnd(1200)
        runCurrent()
        assertEquals(1200L, seen.last().endMinutes)

        // Controller clamping is the validation story: start is coerced to end - 30.
        session.setDayStart(1439)
        runCurrent()
        assertEquals(1170L, seen.last().startMinutes)

        sub.cancel()
    }

    @Test
    fun showSecondsDrivesSecondsLabel() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            // Full-day window so the day is running at any host time zone.
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertEquals(true, seen.last().showSeconds)
        assertTrue(Regex("^\\d{2}s$").matches(seen.last().secondsLabel))

        session.setShowSeconds(false)
        runCurrent()
        assertEquals(false, seen.last().showSeconds)
        assertEquals("", seen.last().secondsLabel)

        sub.cancel()
    }

    @Test
    fun themeModeSetterMapsAndDefaults() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertEquals("SYSTEM", seen.last().themeMode)

        session.setThemeMode("DARK")
        runCurrent()
        assertEquals("DARK", seen.last().themeMode)

        session.setThemeMode("garbage")
        runCurrent()
        assertEquals("SYSTEM", seen.last().themeMode)

        sub.cancel()
    }

    @Test
    fun presentationLabelsFollowFocusState() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertEquals("", seen.last().focusLine)
        assertEquals(seen.last().dayStatus, seen.last().menuBarTitle)

        session.startFocus("Ship it")
        runCurrent()
        assertTrue(seen.last().focusLine.startsWith("Focus · Ship it · "))
        assertEquals(seen.last().pomodoroClock, seen.last().menuBarTitle)

        session.stopFocus()
        runCurrent()
        assertEquals("", seen.last().focusLine)
        assertEquals(seen.last().dayStatus, seen.last().menuBarTitle)

        sub.cancel()
    }

    @Test
    fun presentationLabelsDuringBreak() = runTest {
        // Midday UTC start so a 26-minute tick cannot cross local midnight in any zone.
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = start,
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.startFocus("Ship it")
        controller.tick(start + 26.minutes) // past the default 25-minute session: BREAK
        runCurrent()
        assertEquals("BREAK", seen.last().pomodoroStatus)
        assertTrue(seen.last().focusLine.startsWith("Break · "))
        // During the break the menu bar shows the break clock, not the day headline.
        assertEquals(seen.last().pomodoroClock, seen.last().menuBarTitle)

        sub.cancel()
    }

    @Test
    fun secondsLabelEmptyWhenDayIsOver() = runTest {
        // Window 00:00-00:30: the fixture instant falls after 00:30 local in any zone,
        // so the day is finished and the isFinished gate must blank the label even
        // though showSeconds is on.
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 30),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertEquals(true, seen.last().isFinished)
        assertEquals(true, seen.last().showSeconds)
        assertEquals("", seen.last().secondsLabel)

        sub.cancel()
    }
}
