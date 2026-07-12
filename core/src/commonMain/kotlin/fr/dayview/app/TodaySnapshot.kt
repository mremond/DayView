package fr.dayview.app

/** Primitives-only view of the today screen for native (Swift) callers. */
data class TodaySnapshot(
    val remainingSeconds: Long,
    val remainingRatio: Double,
    val momentAngleDegrees: Double,
    val isFinished: Boolean,
    val remainingHours: Long,
    val remainingMinutes: Long,
    val pomodoroStatus: String, // "IDLE" | "ACTIVE" | "BREAK"
    val pomodoroClock: String, // formatted clock, "" when idle
    val focusIntention: String,
    val dayStatus: String, // remaining-time headline or "Day over"
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
        dayStatus = if (progress.isFinished) {
            "Day over"
        } else {
            "${progress.remainingHours}h ${progress.remainingMinutes.toString().padStart(2, '0')}m"
        },
    )
}
