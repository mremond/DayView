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
            evaluateSessionClean(window, offGoalDuring = 0.seconds, detours = emptyList(), outcome = FocusClosureOutcome.COMPLETED),
        )
    }

    @Test
    fun progressedNeverCounts() {
        assertFalse(
            evaluateSessionClean(window, offGoalDuring = 0.seconds, detours = emptyList(), outcome = FocusClosureOutcome.PROGRESSED),
        )
    }

    @Test
    fun toResumeNeverCounts() {
        assertFalse(
            evaluateSessionClean(window, offGoalDuring = 0.seconds, detours = emptyList(), outcome = FocusClosureOutcome.TO_RESUME),
        )
    }

    @Test
    fun offGoalAtToleranceIsCleanButOverIsNot() {
        assertTrue(
            evaluateSessionClean(window, offGoalDuring = 30.seconds, detours = emptyList(), outcome = FocusClosureOutcome.COMPLETED),
        )
        assertFalse(
            evaluateSessionClean(window, offGoalDuring = 31.seconds, detours = emptyList(), outcome = FocusClosureOutcome.COMPLETED),
        )
    }

    @Test
    fun overlappingDetourBlocksButAdjacentAndOutsideDoNot() {
        val overlapping = DetourEpisode(window.start + 5.minutes, window.start + 10.minutes, "call")
        val touchingEnd = DetourEpisode(window.end, window.end + 5.minutes, "call")
        val outside = DetourEpisode(window.end + 5.minutes, window.end + 10.minutes, "call")
        assertFalse(
            evaluateSessionClean(window, 0.seconds, listOf(overlapping), FocusClosureOutcome.COMPLETED),
        )
        assertTrue(
            evaluateSessionClean(window, 0.seconds, listOf(touchingEnd, outside), FocusClosureOutcome.COMPLETED),
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
        tracker.observe(at(0L), at(1L), OnGoalState.ON_GOAL)
        tracker.observe(at(10_000L), at(1L), OnGoalState.OFF_GOAL)
        assertEquals(10.seconds, tracker.offGoalDuration)
        // New session end -> reset.
        tracker.observe(at(20_000L), at(999L), OnGoalState.OFF_GOAL)
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
}
