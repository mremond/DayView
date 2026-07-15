package fr.dayview.app

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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

/**
 * Day-scoped tally of serious sessions plus streak state. Persisted; the empty default
 * (all sentinels) is what a fresh install reads back.
 */
data class CleanSessionLedger(
    val dayKey: Long = -1L,
    val cleanToday: Int = 0,
    val streakDays: Int = 0,
    val streakLastDayKey: Long = -1L,
)

/** Reset today's count when the day changed; leave streak state untouched (broken lazily). */
fun rollOver(ledger: CleanSessionLedger, dayKey: Long): CleanSessionLedger = if (ledger.dayKey == dayKey) ledger else ledger.copy(dayKey = dayKey, cleanToday = 0)

/**
 * Record one serious session on [dayKey]: increment today's count and, if it is the day's
 * first, extend the streak (consecutive day) or restart it at 1 (after a gap).
 */
fun registerCleanSession(ledger: CleanSessionLedger, dayKey: Long): CleanSessionLedger {
    val rolled = rollOver(ledger, dayKey)
    val firstToday = rolled.cleanToday == 0
    val streakDays = when {
        !firstToday -> rolled.streakDays
        rolled.streakLastDayKey == dayKey - 1 -> rolled.streakDays + 1
        else -> 1
    }
    return rolled.copy(
        cleanToday = rolled.cleanToday + 1,
        streakDays = streakDays,
        streakLastDayKey = dayKey,
    )
}

/** Streak to show today: the stored value only while still alive, else 0 (never stale). */
fun displayedStreak(ledger: CleanSessionLedger, dayKey: Long): Int = if (ledger.streakLastDayKey >= dayKey - 1) ledger.streakDays else 0

/**
 * Ledger after closing the running session: registers it when it was serious, otherwise
 * just rolls the day over. No-op when no session was running.
 */
fun closedFocusLedger(
    cleanSessions: CleanSessionLedger,
    dayKey: Long,
    pomodoroEnd: Instant?,
    pomodoroMinutes: Int,
    sessionOffGoal: Duration,
    detoursToday: List<DetourEpisode>,
    outcome: FocusClosureOutcome,
): CleanSessionLedger = pomodoroEnd?.let { end ->
    val window = FocusSessionWindow(end - pomodoroMinutes.minutes, end)
    val clean = evaluateSessionClean(window, sessionOffGoal, detoursToday, outcome)
    if (clean) registerCleanSession(cleanSessions, dayKey) else rollOver(cleanSessions, dayKey)
} ?: cleanSessions

/**
 * Applies a closure outcome directly to the persisted snapshot: ends the session, keeps
 * or clears the intention per outcome, and updates the clean-session ledger. Same
 * semantics as DayViewController.closePomodoro, for windows that bypass the controller
 * (the desktop mini window).
 */
fun closeFocusSnapshot(
    snapshot: DayPreferencesSnapshot,
    now: Instant,
    sessionOffGoal: Duration,
    outcome: FocusClosureOutcome,
): DayPreferencesSnapshot {
    val dayKey = dayKeyOf(now)
    val detoursToday = if (snapshot.detoursDayKey == dayKey) snapshot.detours else emptyList()
    val newRecord = snapshot.pomodoroEnd?.let { end ->
        val start = end - snapshot.pomodoroMinutes.minutes
        val effectiveEnd = minOf(now, end)
        if (effectiveEnd <= start) {
            null
        } else {
            FocusSessionRecord(start, effectiveEnd, snapshot.focusIntention, outcome)
        }
    }
    val existingRecords = if (snapshot.focusSessionRecordsDayKey == dayKey) snapshot.focusSessionRecords else emptyList()
    return snapshot.copy(
        pomodoroEnd = null,
        focusIntention = focusIntentionAfterClosure(snapshot.focusIntention, outcome),
        focusSessionRecords = if (newRecord != null) existingRecords + newRecord else existingRecords,
        focusSessionRecordsDayKey = dayKey,
        cleanSessions = closedFocusLedger(
            cleanSessions = snapshot.cleanSessions,
            dayKey = dayKey,
            pomodoroEnd = snapshot.pomodoroEnd,
            pomodoroMinutes = snapshot.pomodoroMinutes,
            sessionOffGoal = sessionOffGoal,
            detoursToday = detoursToday,
            outcome = outcome,
        ),
    )
}
