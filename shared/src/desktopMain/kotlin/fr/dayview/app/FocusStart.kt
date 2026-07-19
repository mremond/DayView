package fr.dayview.app

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Coerces [durationMinutes] into the valid 5..180 minute range — the same
 * clamp [DayViewController.startPomodoro] snapshots into `pomodoroSessionMinutes`.
 * The mini window's direct-persist start path uses this to stay coherent with
 * what the controller would have produced.
 */
fun focusStartSessionMinutes(durationMinutes: Int): Int = durationMinutes.coerceIn(5, 180)

/**
 * Computes the pomodoro end time for a focus session started at [now]
 * with the given [durationMinutes], coercing the duration into the valid
 * 5..180 minute range (matching calculatePomodoroProgress).
 */
fun focusStartEnd(now: Instant, durationMinutes: Int): Instant = now + focusStartSessionMinutes(durationMinutes).minutes

/**
 * Builds the snapshot the mini window's direct-persist start path should write, mirroring
 * [DayViewController.startPomodoro]'s two preconditions in the same order: entering focus is
 * free, but not while a session or an open detour is already running. Returns null when either
 * guard blocks the start, so the caller must leave the existing snapshot untouched rather than
 * write one where both an open detour and a session are active at once.
 */
fun focusStartSnapshot(
    snapshot: DayPreferencesSnapshot,
    now: Instant,
    intention: String,
    durationMinutes: Int,
): DayPreferencesSnapshot? {
    if (snapshot.openDetourStart != null) return null
    if (snapshot.pomodoroEnd != null) return null
    return snapshot.copy(
        focusIntention = intention.trim().take(100),
        pomodoroMinutes = durationMinutes,
        pomodoroEnd = focusStartEnd(now, durationMinutes),
        pomodoroSessionMinutes = focusStartSessionMinutes(durationMinutes),
        breakStart = null,
    )
}
