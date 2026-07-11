package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PomodoroTest {
    @Test
    fun idleSlotUsesTwentyFiveMinutesByDefault() {
        val progress = calculatePomodoroProgress(1_000L, 25, null)

        assertEquals(PomodoroStatus.IDLE, progress.status)
        assertEquals(25, progress.durationMinutes)
        assertEquals(1f, progress.remainingRatio)
        assertEquals("25:00", formatPomodoroClock(progress))
    }

    @Test
    fun activeSlotCountsDownFromPersistedDeadline() {
        val progress = calculatePomodoroProgress(
            nowMillis = 10 * 60_000L,
            durationMinutes = 25,
            endMillis = 25 * 60_000L,
        )

        assertEquals(PomodoroStatus.ACTIVE, progress.status)
        assertEquals(15 * 60_000L, progress.remainingMillis)
        assertTrue(progress.remainingRatio in .59f..61f)
        assertEquals("15:00", formatPomodoroClock(progress))
    }

    @Test
    fun exactDeadlineAndPastDeadlineAreFinished() {
        assertEquals(PomodoroStatus.BREAK, calculatePomodoroProgress(1_000L, 25, 1_000L).status)
        assertEquals(PomodoroStatus.BREAK, calculatePomodoroProgress(2_000L, 25, 1_000L).status)
    }

    @Test
    fun durationIsClampedToSupportedBounds() {
        assertEquals(5, calculatePomodoroProgress(0, -10, null).durationMinutes)
        assertEquals(180, calculatePomodoroProgress(0, 999, null).durationMinutes)
    }

    @Test
    fun futureDeadlineCannotExposeMoreThanTheCommittedDuration() {
        val progress = calculatePomodoroProgress(0, 25, 10_000_000L)

        assertEquals(25 * 60_000L, progress.remainingMillis)
        assertEquals(1f, progress.remainingRatio)
    }

    @Test
    fun compactMenuBarValueRoundsPartialMinuteUp() {
        val progress = calculatePomodoroProgress(
            nowMillis = 0,
            durationMinutes = 25,
            endMillis = 22 * 60_000L + 1,
        )

        assertEquals("23m", formatPomodoroCompactMinutes(progress))
        assertEquals("22m", formatPomodoroCompactMinutes(progress.copy(remainingMillis = 22 * 60_000L)))
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
    fun breakCountsUpFromTheFocusDeadline() {
        val progress = calculatePomodoroProgress(
            nowMillis = 31 * 60_000L + 12_000L,
            durationMinutes = 25,
            endMillis = 25 * 60_000L,
        )

        assertEquals(PomodoroStatus.BREAK, progress.status)
        assertEquals(6 * 60_000L + 12_000L, progress.breakElapsedMillis)
        assertEquals("06:12", formatBreakClock(progress))
    }

    @Test
    fun breakReminderRingsEveryTenMinutesUntilOneHour() {
        val scheduler = BreakReminderScheduler()
        val breakStart = 1_000L
        scheduler.observe(breakStart, breakStart)

        for (minutes in 10..60 step 10) {
            assertEquals(false, scheduler.observe(breakStart + minutes * 60_000L - 1, breakStart))
            assertEquals(true, scheduler.observe(breakStart + minutes * 60_000L, breakStart))
        }
        assertEquals(false, scheduler.observe(breakStart + 70 * 60_000L, breakStart))
    }

    @Test
    fun breakReminderDoesNotReplayOldBoundariesAndResetsForANewSession() {
        val scheduler = BreakReminderScheduler()
        scheduler.observe(0L, 0L)
        assertEquals(false, scheduler.observe(25 * 60_000L, 0L))
        assertEquals(false, scheduler.observe(25 * 60_000L + 1, 20 * 60_000L))
        assertEquals(true, scheduler.observe(30 * 60_000L, 20 * 60_000L))
    }

    @Test
    fun breakReminderIgnoresBackwardClockChanges() {
        val scheduler = BreakReminderScheduler()
        val breakStart = 1_000L
        scheduler.observe(breakStart, breakStart)
        scheduler.observe(breakStart + 9 * 60_000L, breakStart)

        assertEquals(false, scheduler.observe(breakStart + 8 * 60_000L, breakStart))
        assertEquals(true, scheduler.observe(breakStart + 10 * 60_000L, breakStart))
    }

    @Test
    fun breakReminderIgnoresBoundariesObservedTooLate() {
        val scheduler = BreakReminderScheduler()
        val breakStart = 1_000L
        scheduler.observe(breakStart, breakStart)

        assertEquals(false, scheduler.observe(breakStart + 12 * 60_000L, breakStart))
    }

    @Test
    fun clearingBreakStatePreventsReminderFromThePreviousSession() {
        val scheduler = BreakReminderScheduler()
        val breakStart = 1_000L
        scheduler.observe(breakStart, breakStart)
        scheduler.observe(breakStart + 9 * 60_000L, null)

        assertEquals(false, scheduler.observe(breakStart + 10 * 60_000L, breakStart))
    }
}
