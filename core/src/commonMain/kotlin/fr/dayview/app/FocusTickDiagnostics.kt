package fr.dayview.app

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Per-session summary of the focus sampling loop: how the frontmost app was classified
 * and how regularly the loop actually ticked. A mean/max tick gap well above the nominal
 * 1s cadence is the fingerprint of macOS App Nap throttling the background sampler; a
 * dominant [neutral]/[offGoal] share instead points at classification (on-goal list or
 * frontmost detection) rather than sampling.
 */
data class FocusTickSummary(
    val ticks: Int,
    val onGoal: Int,
    val neutral: Int,
    val offGoal: Int,
    val meanGap: Duration,
    val maxGap: Duration,
)

/**
 * Accumulates classification counts and inter-tick gaps for one focus session. Fed once
 * per tick while focus is active; [summarize] closes the session and yields its summary
 * (null when no tick was recorded), then [reset]s for the next session.
 */
class FocusTickDiagnostics {
    private var ticks = 0
    private var onGoal = 0
    private var neutral = 0
    private var offGoal = 0
    private var lastTick: Instant? = null
    private var gapSamples = 0
    private var totalGap: Duration = Duration.ZERO
    private var maxGap: Duration = Duration.ZERO

    fun record(now: Instant, state: OnGoalState) {
        ticks++
        when (state) {
            OnGoalState.ON_GOAL -> onGoal++
            OnGoalState.NEUTRAL -> neutral++
            OnGoalState.OFF_GOAL -> offGoal++
        }
        val previous = lastTick
        if (previous != null && now > previous) {
            val gap = now - previous
            totalGap += gap
            gapSamples++
            if (gap > maxGap) maxGap = gap
        }
        lastTick = now
    }

    /** Summarize the session and clear state; returns null when nothing was recorded. */
    fun summarize(): FocusTickSummary? {
        if (ticks == 0) {
            reset()
            return null
        }
        val meanGap = if (gapSamples > 0) totalGap / gapSamples else Duration.ZERO
        val summary = FocusTickSummary(ticks, onGoal, neutral, offGoal, meanGap, maxGap)
        reset()
        return summary
    }

    private fun reset() {
        ticks = 0
        onGoal = 0
        neutral = 0
        offGoal = 0
        lastTick = null
        gapSamples = 0
        totalGap = Duration.ZERO
        maxGap = Duration.ZERO
    }
}
