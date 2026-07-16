package fr.dayview.app

import kotlin.time.Instant

/** Result of one calendar probe; mirrors what DayViewController.updateNetTimeData ingests. */
data class NetTimeProbeResult(
    val permission: Boolean,
    val busy: List<BusyInterval> = emptyList(),
    val calendars: List<CalendarInfo> = emptyList(),
    val readError: Boolean = false,
)

/**
 * Reads the busy layer for one day window. Same semantics as the desktop App's inline
 * probe: disabled means the source is never touched; a thrown interval read surfaces as
 * [NetTimeProbeResult.readError] (distinct from "no events") with the calendar list still
 * read; never throws.
 */
fun probeNetTime(
    source: CalendarSource,
    enabled: Boolean,
    includedCalendarIds: Set<String>,
    windowStart: Instant,
    windowEnd: Instant,
): NetTimeProbeResult {
    if (!enabled) return NetTimeProbeResult(permission = false)
    val granted = runCatching { source.hasPermission() }.getOrDefault(false)
    if (!granted) return NetTimeProbeResult(permission = false)
    val intervals = runCatching { source.busyIntervals(windowStart, windowEnd, includedCalendarIds) }
    val calendars = runCatching { source.availableCalendars() }.getOrDefault(emptyList())
    return NetTimeProbeResult(
        permission = true,
        busy = intervals.getOrDefault(emptyList()),
        calendars = calendars,
        readError = intervals.isFailure,
    )
}
