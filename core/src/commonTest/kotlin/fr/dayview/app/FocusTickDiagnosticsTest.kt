package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class FocusTickDiagnosticsTest {
    private fun at(seconds: Long) = Instant.fromEpochSeconds(seconds)

    @Test
    fun summarizeIsNullWhenNothingRecorded() {
        assertNull(FocusTickDiagnostics().summarize())
    }

    @Test
    fun countsClassificationsAndTickGaps() {
        val diagnostics = FocusTickDiagnostics()
        diagnostics.record(at(0), OnGoalState.ON_GOAL)
        diagnostics.record(at(1), OnGoalState.ON_GOAL)
        diagnostics.record(at(41), OnGoalState.NEUTRAL) // a 40s gap: App Nap fingerprint
        diagnostics.record(at(42), OnGoalState.OFF_GOAL)

        val summary = diagnostics.summarize()!!
        assertEquals(4, summary.ticks)
        assertEquals(2, summary.onGoal)
        assertEquals(1, summary.neutral)
        assertEquals(1, summary.offGoal)
        // Gaps: 1s, 40s, 1s -> mean 14s, max 40s.
        assertEquals(14.seconds, summary.meanGap)
        assertEquals(40.seconds, summary.maxGap)
    }

    @Test
    fun summarizeResetsForTheNextSession() {
        val diagnostics = FocusTickDiagnostics()
        diagnostics.record(at(0), OnGoalState.ON_GOAL)
        diagnostics.summarize()
        assertNull(diagnostics.summarize())
    }

    @Test
    fun ignoresNonAdvancingTicks() {
        val diagnostics = FocusTickDiagnostics()
        diagnostics.record(at(10), OnGoalState.ON_GOAL)
        diagnostics.record(at(10), OnGoalState.ON_GOAL) // same instant contributes no gap
        val summary = diagnostics.summarize()!!
        assertEquals(2, summary.ticks)
        assertEquals(0.seconds, summary.meanGap)
        assertEquals(0.seconds, summary.maxGap)
    }
}
