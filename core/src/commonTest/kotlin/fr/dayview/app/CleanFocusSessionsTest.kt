package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun at(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

class CleanFocusSessionsTest {
    private val window = FocusSessionWindow(at(1_000_000L), at(1_000_000L + 25 * 60_000L))

    @Test
    fun completedWithNoDriftAndNoDetourIsClean() {
        assertTrue(
            evaluateSessionClean(window, now = window.end, offGoalDuring = 0.seconds, detours = emptyList(), outcome = FocusClosureOutcome.COMPLETED),
        )
    }

    @Test
    fun progressedNeverCounts() {
        assertFalse(
            evaluateSessionClean(window, now = window.end, offGoalDuring = 0.seconds, detours = emptyList(), outcome = FocusClosureOutcome.PROGRESSED),
        )
    }

    @Test
    fun toResumeNeverCounts() {
        assertFalse(
            evaluateSessionClean(window, now = window.end, offGoalDuring = 0.seconds, detours = emptyList(), outcome = FocusClosureOutcome.TO_RESUME),
        )
    }

    @Test
    fun offGoalAtToleranceIsCleanButOverIsNot() {
        assertTrue(
            evaluateSessionClean(window, now = window.end, offGoalDuring = 30.seconds, detours = emptyList(), outcome = FocusClosureOutcome.COMPLETED),
        )
        assertFalse(
            evaluateSessionClean(window, now = window.end, offGoalDuring = 31.seconds, detours = emptyList(), outcome = FocusClosureOutcome.COMPLETED),
        )
    }

    @Test
    fun overlappingDetourBlocksButAdjacentAndOutsideDoNot() {
        val overlapping = DetourEpisode(window.start + 5.minutes, window.start + 10.minutes, "call")
        val touchingEnd = DetourEpisode(window.end, window.end + 5.minutes, "call")
        val outside = DetourEpisode(window.end + 5.minutes, window.end + 10.minutes, "call")
        assertFalse(
            evaluateSessionClean(window, window.end, 0.seconds, listOf(overlapping), FocusClosureOutcome.COMPLETED),
        )
        assertTrue(
            evaluateSessionClean(window, window.end, 0.seconds, listOf(touchingEnd, outside), FocusClosureOutcome.COMPLETED),
        )
    }

    @Test
    fun completedBeforeTheTermEarnsNoCredit() {
        // Reaching the term is the serious criterion (see spec): an early COMPLETED
        // closure is free (no detour demanded) but must not earn streak credit.
        assertFalse(
            evaluateSessionClean(
                window,
                now = window.end - 1.minutes,
                offGoalDuring = 0.seconds,
                detours = emptyList(),
                outcome = FocusClosureOutcome.COMPLETED,
            ),
        )
    }

    @Test
    fun completedAtOrAfterTheTermStillEarnsCredit() {
        assertTrue(
            evaluateSessionClean(
                window,
                now = window.end,
                offGoalDuring = 0.seconds,
                detours = emptyList(),
                outcome = FocusClosureOutcome.COMPLETED,
            ),
        )
        assertTrue(
            evaluateSessionClean(
                window,
                now = window.end + 5.minutes,
                offGoalDuring = 0.seconds,
                detours = emptyList(),
                outcome = FocusClosureOutcome.COMPLETED,
            ),
        )
    }

    /**
     * The two rules that arrived from different branches have to hold together at the one
     * place both of them route through. Reaching the term is the serious criterion, and a
     * detour overlapping the session disqualifies it — including one still open at closure,
     * carved in as a provisional episode by the caller.
     */
    @Test
    fun closedFocusLedgerHonoursBothTheTermAndTheOpenDetour() {
        val dayKey = dayKeyOf(window.end)
        fun cleanCountAt(
            now: Instant,
            detours: List<DetourEpisode>,
        ): Int = closedFocusLedger(
            cleanSessions = CleanSessionLedger(),
            dayKey = dayKey,
            pomodoroEnd = window.end,
            sessionMinutes = 25,
            sessionOffGoal = Duration.ZERO,
            detoursToday = detours,
            outcome = FocusClosureOutcome.COMPLETED,
            now = now,
        ).cleanToday

        // Baseline: ran to term, nothing in the way.
        assertEquals(1, cleanCountAt(window.end, emptyList()))
        // Term rule: an early COMPLETED is free but earns no credit, detour or not.
        assertEquals(0, cleanCountAt(window.end - 1.minutes, emptyList()))
        // Detour rule: opened mid-session and still open at a closure in overtime. The
        // provisional episode runs from the pull-away to the closure and overlaps the window.
        assertEquals(
            0,
            cleanCountAt(
                window.end + 10.minutes,
                listOf(DetourEpisode(window.start + 5.minutes, window.end + 10.minutes, PROVISIONAL_DETOUR_CATEGORY)),
            ),
        )
        // ...but a detour opened *after* the term does not reach into [start, term], so a
        // session that ran clean to its term keeps its credit. Overtime never spoils it.
        assertEquals(
            1,
            cleanCountAt(
                window.end + 10.minutes,
                listOf(DetourEpisode(window.end + 2.minutes, window.end + 10.minutes, PROVISIONAL_DETOUR_CATEGORY)),
            ),
        )
    }

    @Test
    fun defaultToleranceIsThirtySeconds() {
        assertEquals(30.seconds, DEFAULT_OFF_GOAL_TOLERANCE)
    }

    @Test
    fun trackerAccumulatesOnlyOffGoalTime() {
        val tracker = SessionCleanlinessTracker()
        val end = at(2_000_000L)
        // First observation seeds lastObserved without adding time.
        tracker.observe(at(0L), end, OnGoalState.ON_GOAL)
        // 10s off-goal counts.
        tracker.observe(at(10_000L), end, OnGoalState.OFF_GOAL)
        // 5s on-goal does not.
        tracker.observe(at(15_000L), end, OnGoalState.ON_GOAL)
        // 5s neutral does not.
        tracker.observe(at(20_000L), end, OnGoalState.NEUTRAL)
        // 3s off-goal counts.
        val total = tracker.observe(at(23_000L), end, OnGoalState.OFF_GOAL)
        assertEquals(13.seconds, total)
        assertEquals(13.seconds, tracker.offGoalDuration)
    }

    @Test
    fun trackerResetsWhenSessionChanges() {
        val tracker = SessionCleanlinessTracker()
        val sessionEnd1 = at(100_000L)
        val sessionEnd2 = at(200_000L)
        tracker.observe(at(0L), sessionEnd1, OnGoalState.ON_GOAL)
        tracker.observe(at(10_000L), sessionEnd1, OnGoalState.OFF_GOAL)
        assertEquals(10.seconds, tracker.offGoalDuration)
        // New session end -> reset.
        tracker.observe(at(20_000L), sessionEnd2, OnGoalState.OFF_GOAL)
        assertEquals(0.seconds, tracker.offGoalDuration)
    }

    @Test
    fun trackerIgnoresOffGoalWhenNoSession() {
        val tracker = SessionCleanlinessTracker()
        tracker.observe(at(0L), null, OnGoalState.OFF_GOAL)
        tracker.observe(at(10_000L), null, OnGoalState.OFF_GOAL)
        assertEquals(0.seconds, tracker.offGoalDuration)
    }

    @Test
    fun firstCleanSessionStartsCountAndStreak() {
        val after = registerCleanSession(CleanSessionLedger(), dayKey = 100L)
        assertEquals(100L, after.dayKey)
        assertEquals(1, after.cleanToday)
        assertEquals(1, after.streakDays)
        assertEquals(100L, after.streakLastDayKey)
    }

    @Test
    fun secondCleanSessionSameDayIncrementsCountNotStreak() {
        val first = registerCleanSession(CleanSessionLedger(), dayKey = 100L)
        val second = registerCleanSession(first, dayKey = 100L)
        assertEquals(2, second.cleanToday)
        assertEquals(1, second.streakDays)
    }

    @Test
    fun consecutiveDayExtendsStreakAndResetsCount() {
        val day100 = registerCleanSession(CleanSessionLedger(), dayKey = 100L)
        val day101 = registerCleanSession(day100, dayKey = 101L)
        assertEquals(1, day101.cleanToday)
        assertEquals(2, day101.streakDays)
        assertEquals(101L, day101.streakLastDayKey)
    }

    @Test
    fun gapRestartsStreakAtOne() {
        val day100 = registerCleanSession(CleanSessionLedger(), dayKey = 100L)
        val day103 = registerCleanSession(day100, dayKey = 103L)
        assertEquals(1, day103.streakDays)
    }

    @Test
    fun rollOverResetsCountButKeepsStreakState() {
        val day100 = registerCleanSession(CleanSessionLedger(), dayKey = 100L)
        val rolled = rollOver(day100, dayKey = 101L)
        assertEquals(101L, rolled.dayKey)
        assertEquals(0, rolled.cleanToday)
        assertEquals(1, rolled.streakDays)
        assertEquals(100L, rolled.streakLastDayKey)
    }

    @Test
    fun displayedStreakHidesADeadStreak() {
        val ledger = CleanSessionLedger(dayKey = 100L, cleanToday = 0, streakDays = 3, streakLastDayKey = 100L)
        // Same day and next day: still alive.
        assertEquals(3, displayedStreak(ledger, dayKey = 100L))
        assertEquals(3, displayedStreak(ledger, dayKey = 101L))
        // A day was missed with nothing yet today: shown as 0.
        assertEquals(0, displayedStreak(ledger, dayKey = 102L))
    }

    @Test
    fun closeFocusSnapshotRecordsTheSession() {
        val now = Instant.fromEpochMilliseconds(100_000_000)
        val snapshot = DayPreferencesSnapshot(
            pomodoroMinutes = 25,
            pomodoroEnd = now, // ends at now → window [now-25m, now]
            focusIntention = "mini window work",
        )
        val closed = closeFocusSnapshot(snapshot, now, Duration.ZERO, FocusClosureOutcome.COMPLETED)
        assertEquals(1, closed.focusSessionRecords.size)
        assertEquals("mini window work", closed.focusSessionRecords[0].intention)
        assertEquals(dayKeyOf(now), closed.focusSessionRecordsDayKey)
    }

    @Test
    fun offGoalPastTermDoesNotAccumulate() {
        val end = Instant.fromEpochMilliseconds(1_000_000L)
        val tracker = SessionCleanlinessTracker()
        tracker.observe(end - 2.minutes, end, OnGoalState.OFF_GOAL)
        tracker.observe(end - 1.minutes, end, OnGoalState.OFF_GOAL) // 1 min inside the window
        tracker.observe(end + 5.minutes, end, OnGoalState.OFF_GOAL) // straddles the term: only up to `end` counts
        tracker.observe(end + 9.minutes, end, OnGoalState.OFF_GOAL) // fully past the term: nothing
        assertEquals(2.minutes, tracker.offGoalDuration)
    }
}
