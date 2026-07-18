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
