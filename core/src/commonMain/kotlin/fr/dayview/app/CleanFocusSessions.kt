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
 * TO_RESUME — *and* closed at or after [window]'s end; an early COMPLETED closure is free
 * but earns no credit, since reaching the term is the serious criterion), off-goal
 * foreground time within the window stayed at or below [tolerance], and no declared detour
 * overlaps the window. Detours that merely touch a window edge do not overlap.
 */
fun evaluateSessionClean(
    window: FocusSessionWindow,
    now: Instant,
    offGoalDuring: Duration,
    detours: List<DetourEpisode>,
    outcome: FocusClosureOutcome,
    tolerance: Duration = DEFAULT_OFF_GOAL_TOLERANCE,
): Boolean {
    if (outcome != FocusClosureOutcome.COMPLETED) return false
    if (now < window.end) return false
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
            val cappedNow = minOf(now, sessionEnd)
            if (cappedNow > previous) accumulated += cappedNow - previous
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
 * just rolls the day over. No-op when no session was running. [now] is the moment of
 * closure, checked against the session's term to decide whether it counts as serious.
 */
fun closedFocusLedger(
    cleanSessions: CleanSessionLedger,
    dayKey: Long,
    pomodoroEnd: Instant?,
    sessionMinutes: Int,
    sessionOffGoal: Duration,
    detoursToday: List<DetourEpisode>,
    outcome: FocusClosureOutcome,
    now: Instant,
): CleanSessionLedger = pomodoroEnd?.let { end ->
    val window = FocusSessionWindow(end - sessionMinutes.minutes, end)
    val clean = evaluateSessionClean(window, now, sessionOffGoal, detoursToday, outcome)
    if (clean) registerCleanSession(cleanSessions, dayKey) else rollOver(cleanSessions, dayKey)
} ?: cleanSessions

/**
 * Same shape as `DayViewController.detoursForCarving`: today's committed episodes plus, when
 * the snapshot still has a detour open, a provisional episode covering it up to [now]. Without
 * this, closing a focus session from a window that bypasses the controller (the desktop mini
 * window) while a detour opened during the session is still running would miss the overlap and
 * wrongly register the session as clean.
 *
 * A detour may be open across the whole session, including its overtime: `startOpenDetour` is
 * allowed while a session runs, and a session now stays open past its term, so [now] here is
 * the uncapped closure moment and the provisional episode covers the overtime too.
 */
private fun detoursForCarving(
    snapshot: DayPreferencesSnapshot,
    now: Instant,
    committedToday: List<DetourEpisode>,
): List<DetourEpisode> {
    val openStart = snapshot.openDetourStart ?: return committedToday
    if (now <= openStart) return committedToday
    return committedToday + DetourEpisode(openStart, now, PROVISIONAL_DETOUR_CATEGORY)
}

/**
 * Applies a closure outcome directly to the persisted snapshot, with the same semantics as
 * DayViewController.closePomodoro: leaving before the term with anything other than
 * COMPLETED costs a name, so without a usable detour category the call is a no-op (the
 * snapshot is returned unchanged) — unless a detour is already running, in which case it
 * already is the named exit and no category is owed; the recorded window and ledger use the
 * uncapped closure moment [now] so overtime counts; a still-open detour is carved into the
 * ledger's view of the day so an overlapped session cannot register as clean; and a closure
 * that hands off to a detour — one newly named here, or one already running — starts no
 * break, otherwise the break opens at [now]. A narrower cousin of the controller for windows
 * that bypass it (the desktop mini window) — the two paths agree.
 */
fun closeFocusSnapshot(
    snapshot: DayPreferencesSnapshot,
    now: Instant,
    sessionOffGoal: Duration,
    outcome: FocusClosureOutcome,
    intention: String = snapshot.focusIntention,
    detourCategory: String = "",
    detourDescription: String = "",
): DayPreferencesSnapshot {
    val end = snapshot.pomodoroEnd ?: return snapshot
    val cleanCategory = sanitizeDetourCategory(detourCategory)
    val namedDetour = cleanCategory.isNotEmpty()
    val detourAlreadyRunning = snapshot.openDetourStart != null
    if (earlyExitRequiresDetour(now, end, outcome, detourAlreadyRunning) && !namedDetour) return snapshot
    // A detour may already be running: `startOpenDetour` is allowed during a focus session and
    // refuses to replace a running one. Mirror that refusal here so the mini window cannot
    // silently reset an in-flight detour's start and swallow the time already elapsed on it.
    // (`earlyExitRequiresDetour` above already stopped charging this toll when a detour is
    // running, so `namedDetour` is normally false here too; this guard is what protects a
    // category supplied anyway from resetting the running detour's start.)
    val opensDetour = namedDetour && snapshot.openDetourStart == null
    val trimmedIntention = intention.take(100)
    val dayKey = dayKeyOf(now)
    val committedDetours = if (snapshot.detoursDayKey == dayKey) snapshot.detours else emptyList()
    val detoursToday = detoursForCarving(snapshot, now, committedDetours)
    val sessionMinutes = snapshot.sessionMinutesEffective
    val start = end - sessionMinutes.minutes
    val existingRecords = if (snapshot.focusSessionRecordsDayKey == dayKey) snapshot.focusSessionRecords else emptyList()
    val newRecord = if (now > start) FocusSessionRecord(start, now, trimmedIntention, outcome) else null
    val ledger = closedFocusLedger(
        cleanSessions = snapshot.cleanSessions,
        dayKey = dayKey,
        pomodoroEnd = snapshot.pomodoroEnd,
        sessionMinutes = sessionMinutes,
        sessionOffGoal = sessionOffGoal,
        detoursToday = detoursToday,
        outcome = outcome,
        now = now,
    )
    return snapshot.copy(
        pomodoroEnd = null,
        pomodoroSessionMinutes = null,
        breakStart = if (namedDetour || detourAlreadyRunning) null else now,
        focusIntention = focusIntentionAfterClosure(trimmedIntention, outcome),
        focusSessionRecords = if (newRecord != null) existingRecords + newRecord else existingRecords,
        focusSessionRecordsDayKey = dayKey,
        cleanSessions = ledger,
        openDetourStart = if (opensDetour) now else snapshot.openDetourStart,
        openDetourCategory = if (opensDetour) cleanCategory else snapshot.openDetourCategory,
        openDetourDescription = if (opensDetour) sanitizeDetourDescription(detourDescription) else snapshot.openDetourDescription,
    )
}
