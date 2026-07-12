package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
}
