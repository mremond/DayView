package fr.dayview.app

/** Primitives-only view of the today screen for native (Swift) callers. */
data class TodaySnapshot(
    val remainingSeconds: Long,
    val remainingRatio: Double,
    val momentAngleDegrees: Double,
    val isFinished: Boolean,
    val remainingHours: Long,
    val remainingMinutes: Long,
    // "IDLE" | "ACTIVE" | "BREAK" — the Swift UI switches on these exact strings, so
    // renaming PomodoroStatus enum constants would silently degrade the Swift UI
    // instead of failing to compile.
    val pomodoroStatus: String,
    val pomodoroClock: String, // formatted clock, "" when idle
    val focusIntention: String,
    val dayStatus: String, // remaining-time headline or "Day over"
    val goalTitle: String,
    val goalHasDeadline: Boolean,
    val goalDeadlineEpochMillis: Long,
    val goalHoursRemaining: Long,
    val pomodoroMinutes: Long,
    val startMinutes: Long, // day window start, minutes from midnight
    val endMinutes: Long, // day window end, minutes from midnight
    val showSeconds: Boolean,
    val themeMode: String, // "SYSTEM" | "LIGHT" | "DARK" (ThemeMode.name)
    // Presentation labels, computed once here instead of per-Swift-view (the
    // dayStatus/pomodoroClock convention):
    val secondsLabel: String, // e.g. "07s" when showSeconds && !isFinished, else ""
    val focusLine: String, // "Focus · <intention> · <clock>" / "Break · <clock>" / ""
    val menuBarTitle: String, // the clock during ACTIVE/BREAK, else dayStatus
)

internal fun DayViewUiState.toTodaySnapshot(): TodaySnapshot {
    val progress = dayProgress
    val pomodoro = pomodoroProgress
    val clock = when (pomodoro.status) {
        PomodoroStatus.ACTIVE -> formatPomodoroClock(pomodoro)
        PomodoroStatus.BREAK -> formatBreakClock(pomodoro)
        PomodoroStatus.IDLE -> ""
    }
    // TODO: localize — hardcoded English until the native macOS UI gains i18n.
    val status = if (progress.isFinished) {
        "Day over"
    } else {
        "${progress.remainingHours}h ${progress.remainingMinutes.toString().padStart(2, '0')}m"
    }
    return TodaySnapshot(
        remainingSeconds = progress.remaining.inWholeSeconds,
        remainingRatio = progress.remainingRatio.toDouble(),
        momentAngleDegrees = currentMomentAngleDegrees(progress.remainingRatio).toDouble(),
        isFinished = progress.isFinished,
        remainingHours = progress.remainingHours,
        remainingMinutes = progress.remainingMinutes,
        pomodoroStatus = pomodoro.status.name,
        pomodoroClock = clock,
        focusIntention = focusIntention,
        dayStatus = status,
        goalTitle = goalTitle,
        goalHasDeadline = goalDeadline != null,
        goalDeadlineEpochMillis = goalDeadline?.toEpochMilliseconds() ?: 0L,
        goalHoursRemaining = goalDeadline?.let { deadline ->
            val working = calculateGoalWorkingTime(now, deadline, startMinutes, endMinutes)
            kotlin.math.ceil(working.toDouble(kotlin.time.DurationUnit.HOURS)).toLong()
        } ?: 0L,
        pomodoroMinutes = pomodoroMinutes.toLong(),
        startMinutes = startMinutes.toLong(),
        endMinutes = endMinutes.toLong(),
        showSeconds = showSeconds,
        themeMode = themeMode.name,
        secondsLabel = if (showSeconds && !progress.isFinished) {
            "${progress.remainingSeconds.toString().padStart(2, '0')}s"
        } else {
            ""
        },
        focusLine = when (pomodoro.status) {
            PomodoroStatus.ACTIVE -> "Focus · $focusIntention · $clock"
            PomodoroStatus.BREAK -> "Break · $clock"
            PomodoroStatus.IDLE -> ""
        },
        menuBarTitle = if (pomodoro.status == PomodoroStatus.IDLE) status else clock,
    )
}
