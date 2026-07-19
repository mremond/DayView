package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PomodoroTest {
    private fun t(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

    @Test
    fun idleSlotUsesTwentyFiveMinutesByDefault() {
        val progress = calculatePomodoroProgress(t(1_000L), 25, null)

        assertEquals(PomodoroStatus.IDLE, progress.status)
        assertEquals(25, progress.durationMinutes)
        assertEquals(1f, progress.remainingRatio)
        assertEquals("25:00", formatPomodoroClock(progress))
    }

    @Test
    fun activeSlotCountsDownFromPersistedDeadline() {
        val progress = calculatePomodoroProgress(
            now = t(10 * 60_000L),
            durationMinutes = 25,
            end = t(25 * 60_000L),
        )

        assertEquals(PomodoroStatus.ACTIVE, progress.status)
        assertEquals(15.minutes, progress.remaining)
        assertTrue(progress.remainingRatio in .59f..61f)
        assertEquals("15:00", formatPomodoroClock(progress))
    }

    @Test
    fun exactDeadlineAndPastDeadlineAreOvertime() {
        assertEquals(PomodoroStatus.OVERTIME, calculatePomodoroProgress(t(1_000L), 25, t(1_000L)).status)
        assertEquals(PomodoroStatus.OVERTIME, calculatePomodoroProgress(t(2_000L), 25, t(1_000L)).status)
    }

    @Test
    fun durationIsClampedToSupportedBounds() {
        assertEquals(5, calculatePomodoroProgress(t(0), -10, null).durationMinutes)
        assertEquals(180, calculatePomodoroProgress(t(0), 999, null).durationMinutes)
    }

    @Test
    fun futureDeadlineCannotExposeMoreThanTheCommittedDuration() {
        val progress = calculatePomodoroProgress(t(0), 25, t(10_000_000L))

        assertEquals(25.minutes, progress.remaining)
        assertEquals(1f, progress.remainingRatio)
    }

    @Test
    fun compactMenuBarValueRoundsPartialMinuteUp() {
        val progress = calculatePomodoroProgress(
            now = t(0),
            durationMinutes = 25,
            end = t(22 * 60_000L + 1),
        )

        assertEquals("23m", formatPomodoroCompactMinutes(progress))
        assertEquals("22m", formatPomodoroCompactMinutes(progress.copy(remaining = 22.minutes)))
    }

    @Test
    fun compactMinutesCeilsPartialMinutesButNotWholeOnes() {
        val base = calculatePomodoroProgress(t(0), 25, t(25 * 60_000L))
        assertEquals("25m", formatPomodoroCompactMinutes(base.copy(remaining = 25.minutes))) // exact minute
        assertEquals("25m", formatPomodoroCompactMinutes(base.copy(remaining = 25.minutes - 1.milliseconds))) // just under
        assertEquals("25m", formatPomodoroCompactMinutes(base.copy(remaining = 24.minutes + 1.milliseconds))) // just over 24
        assertEquals("24m", formatPomodoroCompactMinutes(base.copy(remaining = 24.minutes))) // exact minute
        assertEquals("0m", formatPomodoroCompactMinutes(base.copy(remaining = Duration.ZERO)))
    }

    @Test
    fun onlyToResumeClosureKeepsTheFocusIntention() {
        assertEquals(false, FocusClosureOutcome.COMPLETED.keepsIntention)
        assertEquals(false, FocusClosureOutcome.PROGRESSED.keepsIntention)
        assertEquals(true, FocusClosureOutcome.TO_RESUME.keepsIntention)
    }

    @Test
    fun closureClearsOrKeepsTheFocusIntentionAccordingToTheOutcome() {
        val intention = "Finaliser la présentation"

        assertEquals("", focusIntentionAfterClosure(intention, FocusClosureOutcome.COMPLETED))
        assertEquals("", focusIntentionAfterClosure(intention, FocusClosureOutcome.PROGRESSED))
        assertEquals(intention, focusIntentionAfterClosure(intention, FocusClosureOutcome.TO_RESUME))
    }

    @Test
    fun overtimeCountsUpFromTheFocusDeadline() {
        val progress = calculatePomodoroProgress(
            now = t(31 * 60_000L + 12_000L),
            durationMinutes = 25,
            end = t(25 * 60_000L),
        )

        assertEquals(PomodoroStatus.OVERTIME, progress.status)
        assertEquals(6.minutes + 12.seconds, progress.overtimeElapsed)
        assertEquals("06:12", formatElapsedClock(progress.overtimeElapsed))
    }

    @Test
    fun breakReminderRingsEveryTenMinutesUntilOneHour() {
        val scheduler = BreakReminderScheduler()
        val breakStart = 1_000L
        scheduler.observe(t(breakStart), t(breakStart))

        for (minutes in 10..60 step 10) {
            assertEquals(false, scheduler.observe(t(breakStart + minutes * 60_000L - 1), t(breakStart)))
            assertEquals(true, scheduler.observe(t(breakStart + minutes * 60_000L), t(breakStart)))
        }
        assertEquals(false, scheduler.observe(t(breakStart + 70 * 60_000L), t(breakStart)))
    }

    @Test
    fun breakReminderDoesNotReplayOldBoundariesAndResetsForANewSession() {
        val scheduler = BreakReminderScheduler()
        scheduler.observe(t(0L), t(0L))
        assertEquals(false, scheduler.observe(t(25 * 60_000L), t(0L)))
        assertEquals(false, scheduler.observe(t(25 * 60_000L + 1), t(20 * 60_000L)))
        assertEquals(true, scheduler.observe(t(30 * 60_000L), t(20 * 60_000L)))
    }

    @Test
    fun breakReminderIgnoresBackwardClockChanges() {
        val scheduler = BreakReminderScheduler()
        val breakStart = 1_000L
        scheduler.observe(t(breakStart), t(breakStart))
        scheduler.observe(t(breakStart + 9 * 60_000L), t(breakStart))

        assertEquals(false, scheduler.observe(t(breakStart + 8 * 60_000L), t(breakStart)))
        assertEquals(true, scheduler.observe(t(breakStart + 10 * 60_000L), t(breakStart)))
    }

    @Test
    fun breakReminderIgnoresBoundariesObservedTooLate() {
        val scheduler = BreakReminderScheduler()
        val breakStart = 1_000L
        scheduler.observe(t(breakStart), t(breakStart))

        assertEquals(false, scheduler.observe(t(breakStart + 12 * 60_000L), t(breakStart)))
    }

    @Test
    fun clearingBreakStatePreventsReminderFromThePreviousSession() {
        val scheduler = BreakReminderScheduler()
        val breakStart = 1_000L
        scheduler.observe(t(breakStart), t(breakStart))
        scheduler.observe(t(breakStart + 9 * 60_000L), null)

        assertEquals(false, scheduler.observe(t(breakStart + 10 * 60_000L), t(breakStart)))
    }

    @Test
    fun formatElapsedClockPadsMinutesAndSeconds() {
        assertEquals("01:05", formatElapsedClock(65.seconds))
        assertEquals("00:00", formatElapsedClock(kotlin.time.Duration.ZERO))
    }

    @Test
    fun pastTermIsOvertimeNotBreak() {
        val end = Instant.fromEpochMilliseconds(1_000_000L)
        val progress = calculatePomodoroProgress(end + 3.minutes, 25, end)
        assertEquals(PomodoroStatus.OVERTIME, progress.status)
        assertEquals(3.minutes, progress.overtimeElapsed)
        assertEquals(Duration.ZERO, progress.remaining)
    }

    @Test
    fun breakComesFromBreakStartAnchor() {
        val breakStart = Instant.fromEpochMilliseconds(1_000_000L)
        val progress = calculatePomodoroProgress(breakStart + 7.minutes, 25, end = null, breakStart = breakStart)
        assertEquals(PomodoroStatus.BREAK, progress.status)
        assertEquals(7.minutes, progress.breakElapsed)
    }

    @Test
    fun breakStaysVisibleAtExactlySixtyMinutes() {
        val breakStart = Instant.fromEpochMilliseconds(1_000_000L)
        val progress = calculatePomodoroProgress(breakStart + 60.minutes, 25, end = null, breakStart = breakStart)
        assertEquals(PomodoroStatus.BREAK, progress.status)
        assertEquals(60.minutes, progress.breakElapsed)
    }

    @Test
    fun breakDecaysToIdleAfterSixtyMinutes() {
        val breakStart = Instant.fromEpochMilliseconds(1_000_000L)
        val progress = calculatePomodoroProgress(breakStart + 61.minutes, 25, end = null, breakStart = breakStart)
        assertEquals(PomodoroStatus.IDLE, progress.status)
    }

    @Test
    fun activeSessionIgnoresStaleBreakStart() {
        val end = Instant.fromEpochMilliseconds(2_000_000L)
        val progress = calculatePomodoroProgress(end - 10.minutes, 25, end, breakStart = Instant.fromEpochMilliseconds(0L))
        assertEquals(PomodoroStatus.ACTIVE, progress.status)
    }

    @Test
    fun overtimeLabelCeilsToWholeMinutesAndNeverShowsZero() {
        val end = Instant.fromEpochMilliseconds(1_000_000L)
        assertEquals("+1 min", formatOvertimeLabel(calculatePomodoroProgress(end + 10.seconds, 25, end)))
        assertEquals("+3 min", formatOvertimeLabel(calculatePomodoroProgress(end + 2.minutes + 10.seconds, 25, end)))
    }

    @Test
    fun earlyExitGateOnlyBeforeTermAndOnlyWhenNotCompleted() {
        val end = Instant.fromEpochMilliseconds(1_000_000L)
        assertTrue(earlyExitRequiresDetour(end - 1.minutes, end, FocusClosureOutcome.PROGRESSED))
        assertTrue(earlyExitRequiresDetour(end - 1.minutes, end, FocusClosureOutcome.TO_RESUME))
        assertFalse(earlyExitRequiresDetour(end - 1.minutes, end, FocusClosureOutcome.COMPLETED))
        assertFalse(earlyExitRequiresDetour(end, end, FocusClosureOutcome.PROGRESSED))
        assertFalse(earlyExitRequiresDetour(end + 1.minutes, end, FocusClosureOutcome.TO_RESUME))
        assertFalse(earlyExitRequiresDetour(end - 1.minutes, null, FocusClosureOutcome.PROGRESSED))
    }

    @Test
    fun earlyExitGateIsFreeWhenADetourAlreadyRuns() {
        // A running detour already is the named exit; its motif is collected when it stops
        // (stopOpenDetour), not duplicated at focus-closure. Otherwise this is exactly the
        // case pinned above as requiring a name.
        val end = Instant.fromEpochMilliseconds(1_000_000L)
        assertTrue(earlyExitRequiresDetour(end - 1.minutes, end, FocusClosureOutcome.PROGRESSED))
        assertFalse(
            earlyExitRequiresDetour(end - 1.minutes, end, FocusClosureOutcome.PROGRESSED, detourAlreadyRunning = true),
        )
    }

    @Test
    fun overtimeReminderFiresOnceAtDoubleDuration() {
        val end = Instant.fromEpochMilliseconds(1_000_000L)
        val scheduler = OvertimeReminderScheduler()
        scheduler.observe(end + 24.minutes, end, 25)
        assertTrue(scheduler.observe(end + 25.minutes + 5.seconds, end, 25))
        assertFalse(scheduler.observe(end + 26.minutes, end, 25))
    }

    @Test
    fun overtimeReminderResetsWhenSessionChanges() {
        val end = Instant.fromEpochMilliseconds(1_000_000L)
        val scheduler = OvertimeReminderScheduler()
        scheduler.observe(end + 24.minutes, end, 25)
        assertTrue(scheduler.observe(end + 26.minutes, end, 25))
        val newEnd = end + 90.minutes
        scheduler.observe(newEnd + 24.minutes, newEnd, 25)
        assertTrue(scheduler.observe(newEnd + 26.minutes, newEnd, 25))
    }

    @Test
    fun overtimeReminderSkipsWhenWakingLongPastThreshold() {
        val end = Instant.fromEpochMilliseconds(1_000_000L)
        val scheduler = OvertimeReminderScheduler()
        scheduler.observe(end + 1.minutes, end, 25)
        // Laptop asleep across the threshold: too stale to alert.
        assertFalse(scheduler.observe(end + 50.minutes, end, 25))
    }
}
