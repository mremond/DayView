package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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

    private class FakeCalendarSource(
        var permission: Boolean = true,
        var calendars: List<CalendarInfo> = emptyList(),
        var busy: List<BusyInterval> = emptyList(),
        var throwOnBusy: Boolean = false,
    ) : CalendarSource {
        var busyReads = 0

        override fun isSupported() = true

        override fun hasPermission() = permission

        override fun requestPermission() = Unit

        override fun availableCalendars(): List<CalendarInfo> = calendars

        override fun busyIntervals(
            windowStart: Instant,
            windowEnd: Instant,
            includedCalendarIds: Set<String>,
        ): List<BusyInterval> {
            busyReads++
            if (throwOnBusy) error("boom")
            return busy
        }
    }

    @Test
    fun creationProbesTheCalendarAndFillsTheSnapshot() = runTest {
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L) // midday UTC fixture
        val source = FakeCalendarSource(
            calendars = listOf(CalendarInfo("c1", "Work")),
            busy = listOf(BusyInterval(now, now + 1.hours, titles = listOf("Standup"), calendarId = "c1")),
        )
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
            initialNow = now,
        )
        val session = DayViewSession(controller, backgroundScope, source)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertEquals(true, seen.last().netTimeEnabled)
        assertEquals(true, seen.last().calendarPermission)
        assertEquals(false, seen.last().calendarReadError)
        assertTrue(Regex("^Net (\\d+ h \\d{2}|\\d+ min)$").matches(seen.last().netTimeLabel))
        assertEquals(listOf(CalendarChoice("c1", "Work", included = true)), seen.last().calendars)

        sub.cancel()
    }

    @Test
    fun tickRefreshesTheCalendarEverySixtiethTick() = runTest {
        val source = FakeCalendarSource()
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope, source)
        runCurrent()
        assertEquals(1, source.busyReads) // the creation probe

        repeat(59) { session.tick() }
        assertEquals(1, source.busyReads)

        session.tick() // 60th
        assertEquals(2, source.busyReads)
    }

    @Test
    fun setNetTimeEnabledRefreshesImmediately() = runTest {
        val source = FakeCalendarSource(calendars = listOf(CalendarInfo("c1", "Work")))
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope, source)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()
        assertEquals(false, seen.last().netTimeEnabled)
        assertEquals(false, seen.last().calendarPermission) // disabled probe never asks

        session.setNetTimeEnabled(true)
        runCurrent()
        assertEquals(true, seen.last().netTimeEnabled)
        assertEquals(true, seen.last().calendarPermission)

        sub.cancel()
    }

    @Test
    fun calendarInclusionRoundTripsTheEmptySetMeansAllRule() = runTest {
        val source = FakeCalendarSource(
            calendars = listOf(CalendarInfo("c1", "Work"), CalendarInfo("c2", "Home")),
        )
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope, source)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()
        // Empty set = all included.
        assertEquals(listOf(true, true), seen.last().calendars.map { it.included })

        session.setCalendarIncluded("c2", included = false)
        runCurrent()
        assertEquals(
            listOf(CalendarChoice("c1", "Work", true), CalendarChoice("c2", "Home", false)),
            seen.last().calendars,
        )

        // Re-including everything renormalizes back to the empty set (= all).
        session.setCalendarIncluded("c2", included = true)
        runCurrent()
        assertEquals(listOf(true, true), seen.last().calendars.map { it.included })

        sub.cancel()
    }

    @Test
    fun tickCadenceRepeatsAcrossCycles() = runTest {
        val source = FakeCalendarSource()
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope, source)
        runCurrent()
        assertEquals(1, source.busyReads)

        repeat(60) { session.tick() }
        assertEquals(2, source.busyReads)

        // The counter resets: a second full cycle probes again on the 120th tick.
        repeat(60) { session.tick() }
        assertEquals(3, source.busyReads)
    }

    @Test
    fun throwingCalendarReadSurfacesAsReadErrorInTheSnapshot() = runTest {
        val source = FakeCalendarSource(
            calendars = listOf(CalendarInfo("c1", "Work")),
        ).apply { throwOnBusy = true }
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope, source)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertEquals(true, seen.last().calendarPermission)
        assertEquals(true, seen.last().calendarReadError)
        // The calendar list still arrives even when the busy read fails.
        assertEquals(listOf(CalendarChoice("c1", "Work", included = true)), seen.last().calendars)

        sub.cancel()
    }

    @Test
    fun busyArcsCarryAnglesColorAndHoverLabel() = runTest {
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L) // midday UTC fixture
        val source = FakeCalendarSource(
            calendars = listOf(CalendarInfo("c1", "Work")),
            busy = listOf(BusyInterval(now, now + 1.hours, titles = listOf("Standup"), calendarId = "c1")),
        )
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
            initialNow = now,
        )
        val session = DayViewSession(controller, backgroundScope, source)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        val arc = seen.last().busyArcs.single()
        assertEquals(0L, arc.colorIndex)
        assertTrue(arc.sweepDegrees > 0.0)
        assertTrue(arc.startAngleDegrees >= -90.0 && arc.startAngleDegrees < 270.0)
        // Expected label built from the fixture's own local conversion — host-TZ-portable,
        // pins the joining and format wiring (formatWallClock has its own tests).
        val zone = TimeZone.currentSystemDefault()
        val s = now.toLocalDateTime(zone)
        val e = (now + 1.hours).toLocalDateTime(zone)
        assertEquals(
            "Standup · ${formatWallClock(s.hour, s.minute, true)}–${formatWallClock(e.hour, e.minute, true)}",
            arc.hoverLabel,
        )

        sub.cancel()
    }

    @Test
    fun untitledBusyArcFallsBackToTheCalendarName() = runTest {
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val source = FakeCalendarSource(
            calendars = listOf(CalendarInfo("c1", "Work")),
            busy = listOf(BusyInterval(now, now + 1.hours, titles = emptyList(), calendarId = "c1")),
        )
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
            initialNow = now,
        )
        val session = DayViewSession(controller, backgroundScope, source)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertTrue(seen.last().busyArcs.single().hoverLabel.startsWith("Work · "))

        sub.cancel()
    }

    @Test
    fun twelveHourSessionFormatsHoverTimesInTwelveHourClock() = runTest {
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val source = FakeCalendarSource(
            calendars = listOf(CalendarInfo("c1", "Work")),
            busy = listOf(BusyInterval(now, now + 1.hours, titles = listOf("Standup"), calendarId = "c1")),
        )
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
            initialNow = now,
        )
        val session = DayViewSession(controller, backgroundScope, source, use24Hour = false)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        val zone = TimeZone.currentSystemDefault()
        val s = now.toLocalDateTime(zone)
        val e = (now + 1.hours).toLocalDateTime(zone)
        assertEquals(
            "Standup · ${formatWallClock(s.hour, s.minute, false)}–${formatWallClock(e.hour, e.minute, false)}",
            seen.last().busyArcs.single().hoverLabel,
        )

        sub.cancel()
    }

    @Test
    fun busyArcsEmptyWhenNetTimeDisabled() = runTest {
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val source = FakeCalendarSource(
            calendars = listOf(CalendarInfo("c1", "Work")),
            busy = listOf(BusyInterval(now, now + 1.hours, titles = listOf("Standup"), calendarId = "c1")),
        )
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = now,
        )
        val session = DayViewSession(controller, backgroundScope, source)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertTrue(seen.last().busyArcs.isEmpty())

        sub.cancel()
    }

    @Test
    fun hasStartedReflectsTheDayWindow() = runTest {
        // Window 00:00-23:59: the fixture instant is mid-day, so the day has started.
        val started = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val startedSession = DayViewSession(started, backgroundScope)
        val startedSeen = mutableListOf<TodaySnapshot>()
        val sub1 = startedSession.subscribe { startedSeen.add(it) }
        runCurrent()
        assertEquals(true, startedSeen.last().hasStarted)
        sub1.cancel()

        // Window 23:00-23:59: at the same mid-day instant the day has NOT started yet.
        val notYet = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 23 * 60, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val notYetSession = DayViewSession(notYet, backgroundScope)
        val notYetSeen = mutableListOf<TodaySnapshot>()
        val sub2 = notYetSession.subscribe { notYetSeen.add(it) }
        runCurrent()
        assertEquals(false, notYetSeen.last().hasStarted)
        sub2.cancel()
    }

    @Test
    fun detourCaptureFillsSourcesTotalAndList() = runTest {
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L) // midday UTC fixture
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = now,
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.addDetour("Call", 15, "")
        runCurrent()
        val s = seen.last()
        assertEquals(1, s.detourSources.size)
        assertEquals("Call", s.detourSources.first().label)
        assertTrue(Regex("^(\\d+ h \\d{2}|\\d+ min)$").matches(s.detourSources.first().totalLabel))
        assertTrue(s.detourTotalLabel.startsWith("Detours "))
        assertEquals(1, s.detours.size)
        assertTrue(s.recentDetourCategories.contains("Call"))
        // The list entry's time range is built from the episode's own instants.
        val entry = s.detours.first()
        val zone = TimeZone.currentSystemDefault()
        val start = Instant.fromEpochMilliseconds(entry.startEpochMillis).toLocalDateTime(zone)
        val end = Instant.fromEpochMilliseconds(entry.endEpochMillis).toLocalDateTime(zone)
        assertEquals(
            "${formatWallClock(start.hour, start.minute, true)} – ${formatWallClock(end.hour, end.minute, true)}",
            entry.timeRangeLabel,
        )

        sub.cancel()
    }

    @Test
    fun detourRemoveThenRestoreRoundTrips() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.addDetour("Call", 15, "")
        session.addDetour("Chat", 30, "")
        runCurrent()
        assertEquals(2, seen.last().detours.size)

        session.removeDetour(0)
        runCurrent()
        assertEquals(1, seen.last().detours.size)

        session.restoreLastRemovedDetour()
        runCurrent()
        assertEquals(2, seen.last().detours.size)

        sub.cancel()
    }

    @Test
    fun detourUpdateAndForgetCategory() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.addDetour("Call", 15, "")
        runCurrent()
        val e = seen.last().detours.first()
        session.updateDetour(0, e.startEpochMillis, e.endEpochMillis, "Meeting", "")
        runCurrent()
        assertEquals("Meeting", seen.last().detours.first().category)

        session.forgetRecentDetourCategory("Meeting")
        runCurrent()
        assertTrue(!seen.last().recentDetourCategories.contains("Meeting"))

        sub.cancel()
    }

    @Test
    fun sameCategoryDetoursAccumulateInOneSource() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.addDetour("Call", 15, "")
        session.addDetour("Call", 30, "")
        runCurrent()
        // One source, both episodes; the list keeps both entries.
        assertEquals(1, seen.last().detourSources.size)
        assertEquals(2, seen.last().detours.size)

        sub.cancel()
    }

    @Test
    fun updateDetourChangesTheTimeSpan() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.addDetour("Call", 15, "")
        runCurrent()
        val e = seen.last().detours.first()
        // Widen the span: keep the end, move the start 60 minutes earlier.
        val newStart = e.startEpochMillis - 45 * 60_000L
        session.updateDetour(0, newStart, e.endEpochMillis, e.category, e.description)
        runCurrent()
        assertEquals(newStart, seen.last().detours.first().startEpochMillis)

        sub.cancel()
    }

    @Test
    fun detourBodiesCarryAnglesColorAndHoverLabel() = runTest {
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L) // midday UTC fixture
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = now,
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.addDetour("Call", 15, "")
        runCurrent()
        val body = seen.last().detourBodies.single()
        assertEquals(0L, body.colorIndex)
        assertTrue(body.sweepDegrees > 0.0)
        // Label from the episode's own instants (the entry carries the same span).
        val entry = seen.last().detours.single()
        val zone = TimeZone.currentSystemDefault()
        val start = Instant.fromEpochMilliseconds(entry.startEpochMillis).toLocalDateTime(zone)
        val end = Instant.fromEpochMilliseconds(entry.endEpochMillis).toLocalDateTime(zone)
        assertEquals(
            "Call · ${formatWallClock(start.hour, start.minute, true)} – ${formatWallClock(end.hour, end.minute, true)}",
            body.hoverLabel,
        )

        sub.cancel()
    }

    @Test
    fun detourBodiesEmptyWithNoDetours() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()
        assertTrue(seen.last().detourBodies.isEmpty())
        sub.cancel()
    }

    private class FakeFrontmostProvider(
        var bundleId: String? = null,
        var apps: List<AppRef> = emptyList(),
    ) : FrontmostAppProvider {
        var frontmostQueries = 0
        override fun frontmostBundleId(): String? {
            frontmostQueries++
            return bundleId
        }
        override fun runningApps(): List<AppRef> = apps
    }

    @Test
    fun onGoalAppsRoundTripThroughTheSnapshot() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        session.addOnGoalApp("com.on.goal", "On Goal")
        runCurrent()
        assertEquals(setOf(AppRef("com.on.goal", "On Goal")), controller.stateFlow.value.onGoalApps)
    }

    @Test
    fun engagedTimeAccruesThroughTheSessionTickChain() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        var clock = start
        val provider = FakeFrontmostProvider(bundleId = "com.on.goal")
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                pomodoroMinutes = 25,
                onGoalApps = setOf(AppRef("com.on.goal", "On Goal")),
            ),
            initialNow = start,
        )
        val session = DayViewSession(
            controller,
            backgroundScope,
            frontmostAppProvider = provider,
            now = { clock },
        )
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        session.startFocus("Ship it")
        runCurrent()

        // Three simulated minutes of on-goal presence, one tick per second.
        repeat(180) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()

        assertTrue(seen.last().focusArcs.isNotEmpty(), "engaged arcs should have accrued")
        assertTrue(seen.last().focusTotalLabel.startsWith("Focus "), "focus total should render")

        sub.cancel()
    }

    @Test
    fun frontmostIsNotSampledWhenNoFocusIsActive() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        var clock = start
        val provider = FakeFrontmostProvider(bundleId = "com.on.goal")
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = start,
        )
        val session = DayViewSession(controller, backgroundScope, frontmostAppProvider = provider, now = { clock })
        repeat(5) {
            clock += 1.seconds
            session.tick()
        }
        assertEquals(0, provider.frontmostQueries)
    }

    @Test
    fun focusTotalLabelEmptyWithNoEngagedTime() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()
        assertEquals("", seen.last().focusTotalLabel)
        assertTrue(seen.last().focusArcs.isEmpty())
        sub.cancel()
    }

    private class FakeDock : DockAttentionProvider {
        var badge: Boolean? = null
        var bounces = 0
        override fun setBadge(visible: Boolean) {
            badge = visible
        }
        override fun bounceOnce() {
            bounces++
        }
    }

    private class FakePresencePersistence(
        private val stored: StoredPresence = StoredPresence(),
    ) : PresencePersistence {
        data class Save(
            val dayKey: Long,
            val presence: List<FocusPresenceInterval>,
            val session: List<FocusPresenceInterval>,
        )

        val saves = mutableListOf<Save>()

        override suspend fun load(): StoredPresence = stored

        override suspend fun save(
            dayKey: Long,
            presence: List<FocusPresenceInterval>,
            session: List<FocusPresenceInterval>,
        ) {
            saves.add(Save(dayKey, presence, session))
        }
    }

    @Test
    fun aStillActiveSessionAtLaunchRaisesTheResumeRitual() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        var clock = start
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                pomodoroMinutes = 25,
                pomodoroEnd = start + 20.minutes,
                focusIntention = "Ship it",
            ),
            initialNow = start,
        )
        val session = DayViewSession(
            controller,
            backgroundScope,
            frontmostAppProvider = FakeFrontmostProvider(bundleId = "com.on.goal"),
            now = { clock },
        )
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        clock += 1.seconds
        session.tick()
        runCurrent()

        assertTrue(seen.last().showResumeRitual, "a session already running at launch should raise the ritual")

        session.dismissResumeRitual()
        runCurrent()
        assertTrue(!seen.last().showResumeRitual)

        sub.cancel()
    }

    @Test
    fun driftReminderLatchesBadgesAndBouncesOnce() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        var clock = start
        val dock = FakeDock()
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                pomodoroMinutes = 25,
                onGoalApps = setOf(AppRef("com.on.goal", "On Goal")),
            ),
            initialNow = start,
        )
        val provider = FakeFrontmostProvider(bundleId = "com.other")
        val session = DayViewSession(
            controller,
            backgroundScope,
            frontmostAppProvider = provider,
            now = { clock },
            dockAttention = dock,
        )
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        session.startFocus("Ship it")
        runCurrent()

        // Sit off-goal well past the 2-minute sustained rule.
        repeat(200) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()

        assertTrue(seen.last().showDriftReminder, "sustained off-goal should latch a drift reminder")
        assertEquals(true, dock.badge)
        assertEquals(1, dock.bounces, "exactly one bounce per reminder")

        // More off-goal ticks must not re-bounce for the same latched reminder.
        repeat(30) {
            clock += 1.seconds
            session.tick()
        }
        assertEquals(1, dock.bounces)

        session.dismissDriftReminder()
        runCurrent()
        assertTrue(!seen.last().showDriftReminder)
        assertEquals(false, dock.badge)

        sub.cancel()
    }

    @Test
    fun aManuallyStartedFocusDoesNotRaiseTheResumeRitual() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        var clock = start
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 25),
            initialNow = start,
        )
        val session = DayViewSession(
            controller,
            backgroundScope,
            frontmostAppProvider = FakeFrontmostProvider(bundleId = "com.on.goal"),
            now = { clock },
        )
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        // Idle ticks first: the app is running with no focus.
        repeat(5) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()
        assertTrue(!seen.last().showResumeRitual)

        // The user starts a focus by hand — this must NOT look like a recovered session.
        session.startFocus("Ship it")
        repeat(3) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()
        assertTrue(!seen.last().showResumeRitual, "a manually started focus must not raise the resume ritual")

        sub.cancel()
    }

    @Test
    fun endingTheFocusClearsBothLatchesAndTheBadge() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        var clock = start
        val dock = FakeDock()
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                pomodoroMinutes = 25,
                onGoalApps = setOf(AppRef("com.on.goal", "On Goal")),
            ),
            initialNow = start,
        )
        val session = DayViewSession(
            controller,
            backgroundScope,
            frontmostAppProvider = FakeFrontmostProvider(bundleId = "com.other"),
            now = { clock },
            dockAttention = dock,
        )
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        session.startFocus("Ship it")
        repeat(200) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()
        assertTrue(seen.last().showDriftReminder)
        assertEquals(true, dock.badge)

        session.stopFocus()
        clock += 1.seconds
        session.tick()
        runCurrent()
        assertTrue(!seen.last().showDriftReminder, "ending the focus clears the drift latch")
        assertEquals(false, dock.badge, "ending the focus clears the badge")

        sub.cancel()
    }

    @Test
    fun theCooldownSuppressesASecondReminder() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        var clock = start
        val dock = FakeDock()
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                pomodoroMinutes = 60,
                onGoalApps = setOf(AppRef("com.on.goal", "On Goal")),
            ),
            initialNow = start,
        )
        val session = DayViewSession(
            controller,
            backgroundScope,
            frontmostAppProvider = FakeFrontmostProvider(bundleId = "com.other"),
            now = { clock },
            dockAttention = dock,
        )
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        session.startFocus("Ship it")
        repeat(200) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()
        assertEquals(1, dock.bounces)

        // Dismiss, then keep drifting for another two minutes: the detector's 5-minute
        // cooldown must suppress a second reminder.
        session.dismissDriftReminder()
        repeat(120) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()
        assertTrue(!seen.last().showDriftReminder, "the 5-minute cooldown suppresses a second reminder")
        assertEquals(1, dock.bounces, "no second bounce inside the cooldown")

        sub.cancel()
    }

    @Test
    fun presenceSavesOnStructuralChangeThenThrottlesToThirtySeconds() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        var clock = start
        val persistence = FakePresencePersistence()
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                pomodoroMinutes = 25,
                onGoalApps = setOf(AppRef("com.on.goal", "On Goal")),
            ),
            initialNow = start,
        )
        val session = DayViewSession(
            controller,
            backgroundScope,
            frontmostAppProvider = FakeFrontmostProvider(bundleId = "com.on.goal"),
            now = { clock },
            presencePersistence = persistence,
        )
        session.startFocus("Ship it")
        runCurrent()
        assertEquals(0, persistence.saves.size, "nothing accrued yet, nothing to write")

        // The lenient session accumulator commits its first run once it reaches its
        // 60s minimum: the list goes from 0 to 1 entry — a structural change.
        repeat(61) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()
        assertEquals(1, persistence.saves.size, "a run opening writes immediately")
        assertEquals(dayKeyOf(start), persistence.saves.last().dayKey)
        assertEquals(1, persistence.saves.last().session.size)

        // Extending the same run only moves its end: throttled for 30s.
        repeat(29) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()
        assertEquals(1, persistence.saves.size, "extensions inside the 30s window are not written")

        clock += 1.seconds
        session.tick()
        runCurrent()
        assertEquals(2, persistence.saves.size, "30s after the last write, the extension is flushed")
    }

    @Test
    fun presenceIsNotSavedWhileIdle() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        var clock = start
        val persistence = FakePresencePersistence()
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = start,
        )
        val session = DayViewSession(
            controller,
            backgroundScope,
            frontmostAppProvider = FakeFrontmostProvider(bundleId = "com.on.goal"),
            now = { clock },
            presencePersistence = persistence,
        )
        repeat(120) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()
        assertEquals(0, persistence.saves.size, "no focus, no presence, no writes")
    }

    @Test
    fun seededIntervalsRenderFromTheFirstSnapshot() = runTest {
        // Anchor to the local day so the seeded interval is inside the day window in
        // every timezone.
        val dayStart = dayWindow(Instant.fromEpochMilliseconds(1_699_956_000_000L), 0, 1439).first
        val now = dayStart + 3.hours
        val stored = listOf(FocusPresenceInterval(dayStart + 1.hours, dayStart + 90.minutes))
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = now,
            initialFocusPresenceIntervals = stored,
            initialFocusSessionIntervals = stored,
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertTrue(seen.last().focusArcs.isNotEmpty(), "restored engaged arcs should render")
        assertTrue(
            seen.last().focusTotalLabel.startsWith("Focus "),
            "restored engaged total should render, was '${seen.last().focusTotalLabel}'",
        )

        sub.cancel()
    }

    @Test
    fun seededIntervalsSurviveNinetySecondsOfTicking() = runTest {
        // Anchor to the local day so the seeded interval is inside the day window in
        // every timezone.
        val dayStart = dayWindow(Instant.fromEpochMilliseconds(1_699_956_000_000L), 0, 1439).first
        val start = dayStart + 3.hours
        var clock = start
        val seeded = listOf(FocusPresenceInterval(dayStart + 1.hours, dayStart + 90.minutes))
        val persistence = FakePresencePersistence()
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                pomodoroMinutes = 25,
                onGoalApps = setOf(AppRef("com.on.goal", "On Goal")),
            ),
            initialNow = start,
            initialFocusPresenceIntervals = seeded,
            initialFocusSessionIntervals = seeded,
        )
        val session = DayViewSession(
            controller,
            backgroundScope,
            frontmostAppProvider = FakeFrontmostProvider(bundleId = "com.on.goal"),
            now = { clock },
            presencePersistence = persistence,
        )
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        session.startFocus("Ship it")
        runCurrent()

        // Roughly 90 simulated seconds of on-goal presence: enough for the session
        // accumulator to open and commit its own run alongside the seeded one.
        repeat(90) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()

        assertTrue(
            seen.last().focusArcs.isNotEmpty(),
            "engaged arcs should still render after ticking",
        )
        assertTrue(
            persistence.saves.isNotEmpty(),
            "the accruing run should have triggered at least one persisted save",
        )
        assertTrue(
            persistence.saves.last().session.contains(seeded.single()),
            "the seeded session interval must still be present after ticking, not dropped by the first save",
        )

        sub.cancel()
    }

    @Test
    fun stoppingFocusCarvesAStillOpenDetour() {
        val start = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val c = DayViewController(
            DefaultDayPreferences,
            CoroutineScope(Dispatchers.Unconfined),
            initialSnapshot = DayPreferencesSnapshot(
                pomodoroEnd = start + 30.minutes,
                pomodoroMinutes = 30,
                focusIntention = "écrire",
                // Pulled away 10 minutes in, still off-path when the session is stopped.
                openDetourStart = start + 10.minutes,
            ),
            initialNow = start + 20.minutes,
            derivesEngagedFromSessions = true,
        )
        c.stopPomodoro()
        // Engaged = [start, start+10]; the open detour carves [start+10, start+20].
        assertEquals(
            listOf(FocusPresenceInterval(start, start + 10.minutes)),
            c.state.focusSessionIntervals,
        )
    }
}
