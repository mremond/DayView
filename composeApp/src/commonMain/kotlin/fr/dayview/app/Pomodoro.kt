package fr.dayview.app

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
    val remainingMillis: Long,
    val remainingRatio: Float,
    val status: PomodoroStatus,
    val breakElapsedMillis: Long = 0L,
) {
    val remainingMinutes: Long get() = remainingMillis / 60_000
    val remainingSeconds: Long get() = (remainingMillis / 1_000) % 60
}

fun calculatePomodoroProgress(
    nowMillis: Long,
    durationMinutes: Int,
    endMillis: Long?,
): PomodoroProgress {
    val safeDuration = durationMinutes.coerceIn(5, 180)
    val durationMillis = safeDuration * 60_000L
    if (endMillis == null) {
        return PomodoroProgress(safeDuration, durationMillis, 1f, PomodoroStatus.IDLE)
    }

    val remaining = (endMillis - nowMillis).coerceIn(0, durationMillis)
    return PomodoroProgress(
        durationMinutes = safeDuration,
        remainingMillis = remaining,
        remainingRatio = remaining.toFloat() / durationMillis,
        status = if (remaining == 0L) PomodoroStatus.BREAK else PomodoroStatus.ACTIVE,
        breakElapsedMillis = (nowMillis - endMillis).coerceAtLeast(0L),
    )
}

fun formatPomodoroClock(progress: PomodoroProgress): String =
    "${progress.remainingMinutes.toString().padStart(2, '0')}:" +
        progress.remainingSeconds.toString().padStart(2, '0')

fun formatPomodoroCompactMinutes(progress: PomodoroProgress): String {
    val roundedMinutes = (progress.remainingMillis + 59_999L) / 60_000L
    return "${roundedMinutes}m"
}

fun formatBreakClock(progress: PomodoroProgress): String {
    val elapsedMinutes = progress.breakElapsedMillis / 60_000L
    val elapsedSeconds = (progress.breakElapsedMillis / 1_000L) % 60L
    return "${elapsedMinutes.toString().padStart(2, '0')}:" +
        elapsedSeconds.toString().padStart(2, '0')
}

class BreakReminderScheduler {
    private var previousMillis: Long? = null
    private var previousBreakStartMillis: Long? = null

    fun observe(nowMillis: Long, breakStartMillis: Long?): Boolean {
        val previous = previousMillis
        val previousBreakStart = previousBreakStartMillis
        previousMillis = nowMillis
        previousBreakStartMillis = breakStartMillis
        if (breakStartMillis == null || previous == null || nowMillis <= previous) return false
        if (previousBreakStart != breakStartMillis) return false

        for (minutes in REMINDER_INTERVAL_MINUTES..MAX_BREAK_REMINDER_MINUTES step REMINDER_INTERVAL_MINUTES) {
            val threshold = breakStartMillis + minutes * 60_000L
            if (previous < threshold && nowMillis >= threshold && nowMillis - threshold <= MAX_ALERT_LATENESS_MILLIS) {
                return true
            }
        }
        return false
    }

    private companion object {
        const val REMINDER_INTERVAL_MINUTES = 10
        const val MAX_BREAK_REMINDER_MINUTES = 60
        const val MAX_ALERT_LATENESS_MILLIS = 90_000L
    }
}
