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
