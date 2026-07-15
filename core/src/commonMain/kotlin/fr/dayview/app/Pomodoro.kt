package fr.dayview.app

import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.Instant

enum class PomodoroStatus { IDLE, ACTIVE, BREAK }

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
) {
    val remainingMinutes: Long get() = remaining.inWholeMinutes
    val remainingSeconds: Long get() = remaining.inWholeSeconds % 60
}

fun calculatePomodoroProgress(
    now: Instant,
    durationMinutes: Int,
    end: Instant?,
): PomodoroProgress {
    val safeDuration = durationMinutes.coerceIn(5, 180)
    val duration = safeDuration.minutes
    if (end == null) {
        return PomodoroProgress(safeDuration, duration, 1f, PomodoroStatus.IDLE)
    }

    val remaining = (end - now).coerceIn(Duration.ZERO, duration)
    return PomodoroProgress(
        durationMinutes = safeDuration,
        remaining = remaining,
        remainingRatio = (remaining / duration).toFloat(),
        status = if (remaining == Duration.ZERO) PomodoroStatus.BREAK else PomodoroStatus.ACTIVE,
        breakElapsed = (now - end).coerceAtLeast(Duration.ZERO),
    )
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
