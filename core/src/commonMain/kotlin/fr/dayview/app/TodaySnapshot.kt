package fr.dayview.app

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration

/** One row of the settings calendar checklist. [included] is the EFFECTIVE inclusion. */
data class CalendarChoice(
    val id: String,
    val displayName: String,
    val included: Boolean,
)

/** One distraction source for the tally row. */
data class DetourSourceSnapshot(
    val label: String,
    val colorIndex: Long, // stable per source; the UI maps % detours palette size
    val totalLabel: String, // formatDurationHm(total)
)

/** One declared detour episode, for the edit list (index matches detoursToday). */
data class DetourEntry(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val category: String,
    val description: String,
    val timeRangeLabel: String, // "09:00 – 09:15"
    val durationLabel: String, // formatDurationHm(duration)
)

/** One detour episode projected on the ring's outer lane, ready for drawing and hover. */
data class DetourBodySnapshot(
    val startAngleDegrees: Double, // -90° anchor, clockwise
    val sweepDegrees: Double, // already floored to a visible minimum by :core
    val colorIndex: Long, // matches the tally source color
    val hoverLabel: String, // "<motif> · <start> – <end>"
)

/** A focus/engaged arc on the ring's main lane (mint; no color index; no hover in 10a). */
data class FocusArcSnapshot(val startAngleDegrees: Double, val sweepDegrees: Double)

/** One calendar-busy block projected on the ring, ready for native drawing and hover. */
data class BusyArcSnapshot(
    val startAngleDegrees: Double, // -90° anchor (12 o'clock), clockwise
    val sweepDegrees: Double,
    val colorIndex: Long, // stable per calendar; the UI maps % palette size
    val hoverLabel: String, // "<name> · <start>–<end>", see busyArcHoverLabel
)

/**
 * Index of the arc (parallel [starts]/[sweeps], same length) containing [angleDegrees], or
 * the nearest whose edge is within [toleranceDegrees] — the JVM hover margin: a short arc is
 * a few-degree sliver, and pure containment would make it a pixel-wide target. Ring
 * convention: -90° anchor, clockwise, wrap-aware. -1 when nothing is within tolerance.
 */
fun angularArcIndexAt(
    starts: List<Double>,
    sweeps: List<Double>,
    angleDegrees: Double,
    toleranceDegrees: Double,
): Int {
    val probe = normalizeRingDegrees(angleDegrees)
    var best = -1
    var bestDistance = Double.MAX_VALUE
    for (index in starts.indices) {
        val offset = normalizeRingDegrees(probe - normalizeRingDegrees(starts[index]))
        val distance = if (offset <= sweeps[index]) {
            0.0
        } else {
            // Angular distance to the nearer edge, wrap-aware: past the end going
            // clockwise, or back around to the start going counter-clockwise.
            minOf(offset - sweeps[index], 360.0 - offset)
        }
        if (distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return if (bestDistance <= toleranceDegrees) best else -1
}

/** Busy-arc pick at the 5° hover margin. */
fun busyArcIndexAt(arcs: List<BusyArcSnapshot>, angleDegrees: Double): Int = angularArcIndexAt(
    arcs.map { it.startAngleDegrees },
    arcs.map { it.sweepDegrees },
    angleDegrees,
    5.0,
)

/** Detour-body pick at the 6° hover margin (the JVM detour tolerance). */
fun detourBodyIndexAt(bodies: List<DetourBodySnapshot>, angleDegrees: Double): Int = angularArcIndexAt(
    bodies.map { it.startAngleDegrees },
    bodies.map { it.sweepDegrees },
    angleDegrees,
    6.0,
)

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
    // "IDLE" | "ACTIVE" | "OVERTIME" | "BREAK" — the Swift UI switches on these exact
    // strings, so renaming PomodoroStatus enum constants would silently degrade the
    // Swift UI instead of failing to compile.
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
    val detourSources: List<DetourSourceSnapshot>,
    val detourTotalLabel: String,
    val recentDetourCategories: List<String>,
    val detours: List<DetourEntry>,
    val detourBodies: List<DetourBodySnapshot>,
    val focusArcs: List<FocusArcSnapshot>,
    val focusSessionBands: List<FocusArcSnapshot>,
    val focusTotalLabel: String,
    val showDriftReminder: Boolean,
    val showResumeRitual: Boolean,
    // Leaving now with PROGRESSED or TO_RESUME costs a named detour. One boolean covers
    // both tolled outcomes exactly: the predicate's only outcome-dependence is
    // `outcome != COMPLETED`, so COMPLETED is always free and the other two always share
    // a verdict. It falls to false on its own at the term and while a detour is already
    // running, which is what lets the Swift capture collapse without any timing logic.
    val earlyExitCostsName: Boolean,
    val detourOpenRunning: Boolean,
    val detourOpenCategory: String,
    val detourOpenDescription: String,
    val detourOpenClock: String, // "MM:SS" elapsed, "" when none is running
)

internal fun DayViewUiState.toTodaySnapshot(
    use24Hour: Boolean = true,
    showDriftReminder: Boolean = false,
    showResumeRitual: Boolean = false,
): TodaySnapshot {
    val progress = dayProgress
    val pomodoro = pomodoroProgress
    val clock = when (pomodoro.status) {
        PomodoroStatus.ACTIVE -> formatPomodoroClock(pomodoro)
        // "+N min" — distinct from BREAK's breakElapsed clock.
        PomodoroStatus.OVERTIME -> formatOvertimeLabel(pomodoro)
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
            PomodoroStatus.ACTIVE, PomodoroStatus.OVERTIME ->
                if (focusIntention.isBlank()) "Focus · $clock" else "Focus · $focusIntention · $clock"
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
        detourSources = detourSourcesState.map {
            DetourSourceSnapshot(it.label, it.colorIndex.toLong(), formatDurationHm(it.total))
        },
        detourTotalLabel = if (detoursToday.isEmpty()) "" else "Detours ${formatDurationHm(detoursTotalToday)}",
        recentDetourCategories = recentDetourCategories,
        detours = detoursToday.map { episode ->
            val zone = TimeZone.currentSystemDefault()
            val start = episode.start.toLocalDateTime(zone)
            val end = episode.end.toLocalDateTime(zone)
            DetourEntry(
                startEpochMillis = episode.start.toEpochMilliseconds(),
                endEpochMillis = episode.end.toEpochMilliseconds(),
                category = episode.category,
                description = episode.description,
                timeRangeLabel = "${formatWallClock(start.hour, start.minute, use24Hour)} – " +
                    formatWallClock(end.hour, end.minute, use24Hour),
                durationLabel = formatDurationHm(episode.duration),
            )
        },
        detourBodies = detourBodiesState.map { body ->
            val zone = TimeZone.currentSystemDefault()
            val start = body.start.toLocalDateTime(zone)
            val end = body.end.toLocalDateTime(zone)
            DetourBodySnapshot(
                startAngleDegrees = body.startAngleDegrees.toDouble(),
                sweepDegrees = body.sweepDegrees.toDouble(),
                colorIndex = body.colorIndex.toLong(),
                hoverLabel = "${body.category} · ${formatWallClock(start.hour, start.minute, use24Hour)} – " +
                    formatWallClock(end.hour, end.minute, use24Hour),
            )
        },
        focusArcs = focusArcsState.map { FocusArcSnapshot(it.startAngleDegrees.toDouble(), it.sweepDegrees.toDouble()) },
        focusSessionBands = focusSessionBandsState.map { FocusArcSnapshot(it.startAngleDegrees.toDouble(), it.sweepDegrees.toDouble()) },
        focusTotalLabel = if (focusedToday > Duration.ZERO) "Focus ${formatDurationHm(focusedToday)}" else "",
        showDriftReminder = showDriftReminder,
        showResumeRitual = showResumeRitual,
        earlyExitCostsName = earlyExitRequiresDetour(
            now = now,
            end = pomodoroEnd,
            outcome = FocusClosureOutcome.PROGRESSED,
            detourAlreadyRunning = openDetourRunning,
        ),
        detourOpenRunning = openDetourRunning,
        detourOpenCategory = openDetourCategory,
        detourOpenDescription = openDetourDescription,
        detourOpenClock = if (openDetourRunning) formatElapsedClock(openDetourElapsed) else "",
    )
}
