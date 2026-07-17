package fr.dayview.app

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** One row of the settings calendar checklist. [included] is the EFFECTIVE inclusion. */
data class CalendarChoice(
    val id: String,
    val displayName: String,
    val included: Boolean,
)

/** One calendar-busy block projected on the ring, ready for native drawing and hover. */
data class BusyArcSnapshot(
    val startAngleDegrees: Double, // -90° anchor (12 o'clock), clockwise
    val sweepDegrees: Double,
    val colorIndex: Long, // stable per calendar; the UI maps % palette size
    val hoverLabel: String, // "<name> · <start>–<end>", see busyArcHoverLabel
)

private const val BUSY_HOVER_TOLERANCE_DEGREES = 5.0

/**
 * Index of the arc containing [angleDegrees], or the nearest arc within 5° of one of
 * its edges — the JVM hover margin: a 15-minute event is a ~5° sliver, and pure
 * containment would make it a pixel-wide target. Ring convention: -90° anchor,
 * clockwise, wrap-aware. -1 when nothing is within tolerance.
 */
fun busyArcIndexAt(arcs: List<BusyArcSnapshot>, angleDegrees: Double): Int {
    val probe = normalizeRingDegrees(angleDegrees)
    var best = -1
    var bestDistance = Double.MAX_VALUE
    arcs.forEachIndexed { index, arc ->
        val offset = normalizeRingDegrees(probe - normalizeRingDegrees(arc.startAngleDegrees))
        val distance = if (offset <= arc.sweepDegrees) {
            0.0
        } else {
            // Angular distance to the nearer edge, wrap-aware: past the end going
            // clockwise, or back around to the start going counter-clockwise.
            minOf(offset - arc.sweepDegrees, 360.0 - offset)
        }
        if (distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return if (bestDistance <= BUSY_HOVER_TOLERANCE_DEGREES) best else -1
}

private fun normalizeRingDegrees(degrees: Double): Double {
    val mod = degrees % 360.0
    return if (mod < 0) mod + 360.0 else mod
}

/**
 * Hover text for a busy block: non-blank titles joined, falling back to the calendar
 * name, then "Busy"; times from the EXACT stored instants (an angle round-trip shaves
 * minutes — see BusyBlockArc).
 */
internal fun busyArcHoverLabel(arc: BusyBlockArc, use24Hour: Boolean): String {
    val name = arc.titles.filter { it.isNotBlank() }.joinToString(", ")
        .ifBlank { arc.calendarName }
        .ifBlank { "Busy" }
    val zone = TimeZone.currentSystemDefault()
    val start = arc.start.toLocalDateTime(zone)
    val end = arc.end.toLocalDateTime(zone)
    val startLabel = formatWallClock(start.hour, start.minute, use24Hour)
    val endLabel = formatWallClock(end.hour, end.minute, use24Hour)
    return "$name · $startLabel–$endLabel"
}

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
    val netTimeEnabled: Boolean,
    val calendarPermission: Boolean,
    val calendarReadError: Boolean,
    val netTimeLabel: String, // "Net " + formatDurationHm(netRemaining), "" when off/no data
    val calendars: List<CalendarChoice>, // raw available calendars (settings checklist)
    val busyArcs: List<BusyArcSnapshot>,
    val hasStarted: Boolean,
)

internal fun DayViewUiState.toTodaySnapshot(use24Hour: Boolean = true): TodaySnapshot {
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
        netTimeEnabled = netTimeSettings.enabled,
        calendarPermission = netCalendarPermission,
        calendarReadError = netCalendarError,
        netTimeLabel = netTime?.let { "Net ${formatDurationHm(it.netRemaining)}" } ?: "",
        calendars = availableCalendars.map { cal ->
            CalendarChoice(
                id = cal.id,
                displayName = cal.displayName,
                included = netTimeSettings.includedCalendarIds.isEmpty() ||
                    cal.id in netTimeSettings.includedCalendarIds,
            )
        },
        busyArcs = busyBlockArcsState.map { arc ->
            BusyArcSnapshot(
                startAngleDegrees = arc.startAngleDegrees.toDouble(),
                sweepDegrees = arc.sweepDegrees.toDouble(),
                colorIndex = arc.colorIndex.toLong(),
                hoverLabel = busyArcHoverLabel(arc, use24Hour),
            )
        },
        hasStarted = progress.hasStarted,
    )
}
