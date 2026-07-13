package fr.dayview.app

/** Primitives-only view of the today screen for native (Swift) callers. */
data class TodaySnapshot(
    val remainingSeconds: Long,
    val remainingRatio: Double,
    val momentAngleDegrees: Double,
    val isFinished: Boolean,
    val remainingHours: Long,
    val remainingMinutes: Long,
    // "IDLE" | "ACTIVE" | "BREAK" — the Swift RingView.focusText switches on these exact
    // strings, so renaming PomodoroStatus enum constants would silently degrade the Swift UI
    // to "Idle" instead of failing to compile.
    val pomodoroStatus: String,
    val pomodoroClock: String, // formatted clock, "" when idle
    val focusIntention: String,
    val dayStatus: String, // remaining-time headline or "Day over"
    val goalTitle: String,
    val goalHasDeadline: Boolean,
    val goalDeadlineEpochMillis: Long,
    val goalHoursRemaining: Long,
    val pomodoroMinutes: Long,
)

internal fun DayViewUiState.toTodaySnapshot(): TodaySnapshot {
    val progress = dayProgress
    val pomodoro = pomodoroProgress
    return TodaySnapshot(
        remainingSeconds = progress.remaining.inWholeSeconds,
        remainingRatio = progress.remainingRatio.toDouble(),
        momentAngleDegrees = currentMomentAngleDegrees(progress.remainingRatio).toDouble(),
        isFinished = progress.isFinished,
        remainingHours = progress.remainingHours,
        remainingMinutes = progress.remainingMinutes,
        pomodoroStatus = pomodoro.status.name,
        pomodoroClock = when (pomodoro.status) {
            PomodoroStatus.ACTIVE -> formatPomodoroClock(pomodoro)
            PomodoroStatus.BREAK -> formatBreakClock(pomodoro)
            PomodoroStatus.IDLE -> ""
        },
        focusIntention = focusIntention,
        // TODO: localize — hardcoded English until the native macOS UI gains i18n.
        dayStatus = if (progress.isFinished) {
            "Day over"
        } else {
            "${progress.remainingHours}h ${progress.remainingMinutes.toString().padStart(2, '0')}m"
        },
        goalTitle = goalTitle,
        goalHasDeadline = goalDeadline != null,
        goalDeadlineEpochMillis = goalDeadline?.toEpochMilliseconds() ?: 0L,
        goalHoursRemaining = goalDeadline?.let { deadline ->
            val working = calculateGoalWorkingTime(now, deadline, startMinutes, endMinutes)
            kotlin.math.ceil(working.toDouble(kotlin.time.DurationUnit.HOURS)).toLong()
        } ?: 0L,
        pomodoroMinutes = pomodoroMinutes.toLong(),
    )
}
