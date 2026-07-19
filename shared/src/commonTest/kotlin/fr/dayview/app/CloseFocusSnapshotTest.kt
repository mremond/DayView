package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CloseFocusSnapshotTest {
    private val now = Instant.fromEpochMilliseconds(1_760_000_000_000L)

    private fun breakSnapshot(vararg detours: DetourEpisode) = DayPreferencesSnapshot(
        focusIntention = "Écrire le rapport",
        pomodoroMinutes = 25,
        pomodoroEnd = now - 1.minutes,
        detoursDayKey = dayKeyOf(now),
        detours = detours.toList(),
    )

    /** A session still running: pomodoroEnd is in the future relative to [now]. */
    private fun runningSnapshot(vararg detours: DetourEpisode) = DayPreferencesSnapshot(
        focusIntention = "Écrire le rapport",
        pomodoroMinutes = 25,
        pomodoroEnd = now + 25.minutes,
        detoursDayKey = dayKeyOf(now),
        detours = detours.toList(),
    )

    @Test
    fun snapshotEarlyExitWithoutDetourIsRefused() {
        val s = runningSnapshot()
        val result = closeFocusSnapshot(s, now = s.pomodoroEnd!! - 10.minutes, sessionOffGoal = Duration.ZERO, outcome = FocusClosureOutcome.PROGRESSED)
        assertEquals(s, result)
    }

    @Test
    fun snapshotEarlyExitWithDetourStartsOpenDetour() {
        val s = runningSnapshot()
        val closureNow = s.pomodoroEnd!! - 10.minutes
        val result = closeFocusSnapshot(s, closureNow, Duration.ZERO, FocusClosureOutcome.TO_RESUME, detourCategory = "Mail")
        assertNull(result.pomodoroEnd)
        assertEquals("Mail", result.openDetourCategory)
        assertEquals(closureNow, result.openDetourStart)
        assertNull(result.breakStart)
    }

    @Test
    fun snapshotOvertimeCloseRecordsUncappedAndAnchorsBreak() {
        val s = runningSnapshot()
        val closureNow = s.pomodoroEnd!! + 8.minutes
        val result = closeFocusSnapshot(s, closureNow, Duration.ZERO, FocusClosureOutcome.COMPLETED)
        assertEquals(closureNow, result.focusSessionRecords.last().end)
        assertEquals(closureNow, result.breakStart)
        assertNull(result.pomodoroSessionMinutes)
    }

    @Test
    fun snapshotEarlyCompletedClosesFreeButEarnsNoCredit() {
        // The spec: reaching the term is the serious criterion. An early COMPLETED
        // closure must be free (no detour demanded, unlike PROGRESSED/TO_RESUME above)
        // but must not register in the clean-session ledger.
        val s = runningSnapshot()
        val closureNow = s.pomodoroEnd!! - 10.minutes
        val result = closeFocusSnapshot(s, closureNow, Duration.ZERO, FocusClosureOutcome.COMPLETED)
        assertNull(result.pomodoroEnd)
        assertEquals(0, result.cleanSessions.cleanToday)
    }

    @Test
    fun snapshotCompletedAtOrAfterTheTermEarnsCredit() {
        val s = runningSnapshot()
        val result = closeFocusSnapshot(s, s.pomodoroEnd!!, Duration.ZERO, FocusClosureOutcome.COMPLETED)
        assertEquals(1, result.cleanSessions.cleanToday)
    }

    @Test
    fun completedCleanSessionClearsIntentionAndRegistersInLedger() {
        val closed = closeFocusSnapshot(breakSnapshot(), now, Duration.ZERO, FocusClosureOutcome.COMPLETED)
        assertNull(closed.pomodoroEnd)
        assertEquals("", closed.focusIntention)
        assertEquals(dayKeyOf(now), closed.cleanSessions.dayKey)
        assertEquals(1, closed.cleanSessions.cleanToday)
    }

    @Test
    fun toResumeKeepsIntentionAndDoesNotCountAsClean() {
        val closed = closeFocusSnapshot(breakSnapshot(), now, Duration.ZERO, FocusClosureOutcome.TO_RESUME)
        assertNull(closed.pomodoroEnd)
        assertEquals("Écrire le rapport", closed.focusIntention)
        assertEquals(0, closed.cleanSessions.cleanToday)
    }

    @Test
    fun detourOverlappingTheSessionPreventsACleanCount() {
        val overlapping = DetourEpisode(start = now - 10.minutes, end = now - 5.minutes, category = "Slack")
        val closed = closeFocusSnapshot(breakSnapshot(overlapping), now, Duration.ZERO, FocusClosureOutcome.COMPLETED)
        assertEquals(0, closed.cleanSessions.cleanToday)
    }

    @Test
    fun staleDetoursFromAPreviousDayAreIgnored() {
        val overlapping = DetourEpisode(start = now - 10.minutes, end = now - 5.minutes, category = "Slack")
        val snapshot = breakSnapshot(overlapping).copy(detoursDayKey = dayKeyOf(now) - 1)
        val closed = closeFocusSnapshot(snapshot, now, Duration.ZERO, FocusClosureOutcome.COMPLETED)
        assertEquals(1, closed.cleanSessions.cleanToday)
    }

    @Test
    fun closingClearsTheSnapshottedSessionDuration() {
        // pomodoroSessionMinutes is live once a session starts (Task 5); leaving it set past
        // closure would keep sessionMinutesEffective returning the closed session's duration
        // while changePomodoroDuration moves pomodoroMinutes invisibly underneath.
        val snapshot = breakSnapshot().copy(pomodoroSessionMinutes = 5)
        val closed = closeFocusSnapshot(snapshot, now, Duration.ZERO, FocusClosureOutcome.COMPLETED)
        assertNull(closed.pomodoroSessionMinutes)
    }

    @Test
    fun earlyExitWithARunningDetourSucceedsWithNoCategoryAndLeavesItUntouched() {
        // A detour already running is already the named exit: earlyExitRequiresDetour no
        // longer demands a category in this state, so the closure must succeed with none
        // supplied, and the running detour's own start/category must be left alone.
        val s = runningSnapshot()
        val closureNow = s.pomodoroEnd!! - 10.minutes
        val detourStart = closureNow - 10.minutes
        val seeded = s.copy(openDetourStart = detourStart, openDetourCategory = "Mail")
        val result = closeFocusSnapshot(seeded, closureNow, Duration.ZERO, FocusClosureOutcome.PROGRESSED)
        assertNull(result.pomodoroEnd)
        assertEquals(detourStart, result.openDetourStart)
        assertEquals("Mail", result.openDetourCategory)
        // This closure hands off to the already-running detour, so no break starts either —
        // otherwise a break and an open detour would be simultaneously live.
        assertNull(result.breakStart)
    }

    @Test
    fun earlyExitWithARunningDetourIgnoresASuppliedCategory() {
        // Pins the `opensDetour` guard: collapsing it back to `namedDetour` alone would let
        // a category supplied here (namedDetour = true, deliberately) reset the already-
        // running detour's start to closureNow, silently discarding the time already
        // elapsed on it. The fixed UI no longer prompts for one in this state (a running
        // detour makes requiresDetourFor false), but this function must not rely on that —
        // it must refuse to reset a running detour regardless of what it is handed.
        val s = runningSnapshot()
        val closureNow = s.pomodoroEnd!! - 10.minutes
        val detourStart = closureNow - 10.minutes
        val seeded = s.copy(openDetourStart = detourStart, openDetourCategory = "Mail")
        val result = closeFocusSnapshot(
            seeded,
            closureNow,
            Duration.ZERO,
            FocusClosureOutcome.PROGRESSED,
            detourCategory = "Coffee",
        )
        assertNull(result.pomodoroEnd)
        assertEquals(detourStart, result.openDetourStart)
        assertEquals("Mail", result.openDetourCategory)
    }

    @Test
    fun stillOpenDetourPreventsACleanCount() {
        // The mini window can close a focus session while a detour opened during it is still
        // running (startOpenDetour no longer refuses during an active focus session). The open
        // detour spans the whole session here, same as evaluateSessionClean would see via
        // DayViewController.closePomodoro.
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "mini window work",
            pomodoroMinutes = 25,
            pomodoroEnd = now,
            openDetourStart = now - 25.minutes,
        )
        val closed = closeFocusSnapshot(snapshot, now, Duration.ZERO, FocusClosureOutcome.COMPLETED)
        assertEquals(0, closed.cleanSessions.cleanToday)
    }
}
