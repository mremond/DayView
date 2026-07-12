package fr.dayview.app

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Off-goal foreground time tolerated inside a focus session before it stops counting as serious. */
val DEFAULT_OFF_GOAL_TOLERANCE: Duration = 30.seconds

/** The active span of a focus session: `[start, end]` where `end = start + duration`. */
data class FocusSessionWindow(val start: Instant, val end: Instant)

/**
 * A focus session is "serious" iff it ran to term (COMPLETED — not PROGRESSED or
 * TO_RESUME), off-goal foreground time within the window stayed at or below [tolerance],
 * and no declared detour overlaps the window. Detours that merely touch a window edge do
 * not overlap.
 */
fun evaluateSessionClean(
    window: FocusSessionWindow,
    offGoalDuring: Duration,
    detours: List<DetourEpisode>,
    outcome: FocusClosureOutcome,
    tolerance: Duration = DEFAULT_OFF_GOAL_TOLERANCE,
): Boolean {
    if (outcome != FocusClosureOutcome.COMPLETED) return false
    if (offGoalDuring > tolerance) return false
    return detours.none { it.start < window.end && it.end > window.start }
}

/**
 * Accumulates OFF_GOAL foreground time for the current focus session. The session is
 * identified by its [sessionEnd]; when that changes (a new session starts, or the session
 * is cleared to null), the accumulator resets. NEUTRAL and ON_GOAL ticks add nothing, and
 * nothing accrues while there is no session. Fed once per tick from the desktop loop.
 */
class SessionCleanlinessTracker {
    private var sessionEnd: Instant? = null
    private var lastObserved: Instant? = null
    private var accumulated: Duration = Duration.ZERO

    val offGoalDuration: Duration get() = accumulated

    fun observe(
        now: Instant,
        sessionEnd: Instant?,
        state: OnGoalState,
    ): Duration {
        if (sessionEnd != this.sessionEnd) {
            this.sessionEnd = sessionEnd
            lastObserved = null
            accumulated = Duration.ZERO
        }
        val previous = lastObserved
        lastObserved = now
        if (sessionEnd != null && state == OnGoalState.OFF_GOAL && previous != null && now > previous) {
            accumulated += now - previous
        }
        return accumulated
    }
}
