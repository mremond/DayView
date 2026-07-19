package fr.dayview.app

import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.Instant

enum class PomodoroStatus { IDLE, ACTIVE, OVERTIME, BREAK }

/**
 * A focus session is open from the moment it starts until a conscious closure. The term does
 * not end it — a session being overrun (OVERTIME) is still open, still counted, and still
 * protected from the day's cues. The single definition behind [DayViewUiState.focusIsActive]
 * and [DayPreferencesSnapshot.focusIsActive], so the controller's state and the snapshot-driven
 * desktop loop cannot drift apart.
 */
fun focusSessionIsOpen(pomodoroEnd: Instant?): Boolean = pomodoroEnd != null

/** How long a closed session's break stays visible before the panel returns to idle. */
val BREAK_VISIBLE_MAX: Duration = 60.minutes

enum class FocusClosureOutcome(val keepsIntention: Boolean) {
    COMPLETED(false),
    PROGRESSED(false),
    TO_RESUME(true),
}

fun focusIntentionAfterClosure(
    intention: String,
    outcome: FocusClosureOutcome,
): String = intention.takeIf { outcome.keepsIntention }.orEmpty()

data class PomodoroProgress(
    val durationMinutes: Int,
    val remaining: Duration,
    val remainingRatio: Float,
    val status: PomodoroStatus,
    val breakElapsed: Duration = Duration.ZERO,
    val overtimeElapsed: Duration = Duration.ZERO,
) {
    val remainingMinutes: Long get() = remaining.inWholeMinutes
    val remainingSeconds: Long get() = remaining.inWholeSeconds % 60
}

fun calculatePomodoroProgress(
    now: Instant,
    durationMinutes: Int,
    end: Instant?,
    breakStart: Instant? = null,
): PomodoroProgress {
    val safeDuration = durationMinutes.coerceIn(5, 180)
    val duration = safeDuration.minutes
    if (end != null) {
        val remaining = (end - now).coerceIn(Duration.ZERO, duration)
        return if (remaining > Duration.ZERO) {
            PomodoroProgress(safeDuration, remaining, (remaining / duration).toFloat(), PomodoroStatus.ACTIVE)
        } else {
            PomodoroProgress(
                durationMinutes = safeDuration,
                remaining = Duration.ZERO,
                remainingRatio = 0f,
                status = PomodoroStatus.OVERTIME,
                overtimeElapsed = (now - end).coerceAtLeast(Duration.ZERO),
            )
        }
    }
    if (breakStart != null) {
        val elapsed = (now - breakStart).coerceAtLeast(Duration.ZERO)
        if (elapsed <= BREAK_VISIBLE_MAX) {
            return PomodoroProgress(safeDuration, duration, 1f, PomodoroStatus.BREAK, breakElapsed = elapsed)
        }
    }
    return PomodoroProgress(safeDuration, duration, 1f, PomodoroStatus.IDLE)
}

/** Overtime headline: "+N min", ceiled so the first seconds already read "+1 min". */
fun formatOvertimeLabel(progress: PomodoroProgress): String {
    val minutes = ceil(progress.overtimeElapsed.toDouble(DurationUnit.MINUTES)).toLong().coerceAtLeast(1L)
    return "+$minutes min"
}

fun formatPomodoroClock(progress: PomodoroProgress): String = "${progress.remainingMinutes.toString().padStart(2, '0')}:" +
    progress.remainingSeconds.toString().padStart(2, '0')

fun formatPomodoroCompactMinutes(progress: PomodoroProgress): String {
    val roundedMinutes = ceil(progress.remaining.toDouble(DurationUnit.MINUTES)).toLong()
    return "${roundedMinutes}m"
}

fun formatElapsedClock(elapsed: Duration): String {
    val elapsedMinutes = elapsed.inWholeMinutes
    val elapsedSeconds = elapsed.inWholeSeconds % 60L
    return "${elapsedMinutes.toString().padStart(2, '0')}:" +
        elapsedSeconds.toString().padStart(2, '0')
}

fun formatBreakClock(progress: PomodoroProgress): String = formatElapsedClock(progress.breakElapsed)

class BreakReminderScheduler {
    private var previous: Instant? = null
    private var previousBreakStart: Instant? = null

    fun observe(now: Instant, breakStart: Instant?): Boolean {
        val previousObservation = previous
        val previousStart = previousBreakStart
        previous = now
        previousBreakStart = breakStart
        if (breakStart == null || previousObservation == null || now <= previousObservation) return false
        if (previousStart != breakStart) return false

        for (minutes in REMINDER_INTERVAL_MINUTES..MAX_BREAK_REMINDER_MINUTES step REMINDER_INTERVAL_MINUTES) {
            val threshold = breakStart + minutes.minutes
            if (previousObservation < threshold && now >= threshold && now - threshold <= MAX_ALERT_LATENESS) {
                return true
            }
        }
        return false
    }

    private companion object {
        const val REMINDER_INTERVAL_MINUTES = 10
        const val MAX_BREAK_REMINDER_MINUTES = 60
        val MAX_ALERT_LATENESS = 90.seconds
    }
}

/**
 * Leaving before the term with anything but COMPLETED must name the pull (a detour) —
 * unless [detourAlreadyRunning]: a detour already open is already the named exit (its
 * motif is collected when it stops, not duplicated here), so no further toll is owed.
 */
fun earlyExitRequiresDetour(
    now: Instant,
    end: Instant?,
    outcome: FocusClosureOutcome,
    detourAlreadyRunning: Boolean = false,
): Boolean = end != null && now < end && outcome != FocusClosureOutcome.COMPLETED && !detourAlreadyRunning

/**
 * One discreet closure suggestion when a session's overtime reaches its planned duration
 * (total = 2x planned). Fires at most once per session; a wake long past the threshold
 * stays silent (same lateness rule as break reminders).
 */
class OvertimeReminderScheduler {
    private var sessionEnd: Instant? = null
    private var previous: Instant? = null
    private var fired: Boolean = false

    fun observe(
        now: Instant,
        sessionEnd: Instant?,
        sessionMinutes: Int,
    ): Boolean {
        if (sessionEnd != this.sessionEnd) {
            this.sessionEnd = sessionEnd
            previous = null
            fired = false
        }
        val previousObservation = previous
        previous = now
        if (sessionEnd == null || fired || previousObservation == null || now <= previousObservation) return false
        val threshold = sessionEnd + sessionMinutes.coerceIn(5, 180).minutes
        if (previousObservation < threshold && now >= threshold && now - threshold <= MAX_ALERT_LATENESS) {
            fired = true
            return true
        }
        return false
    }

    private companion object {
        val MAX_ALERT_LATENESS = 90.seconds
    }
}
