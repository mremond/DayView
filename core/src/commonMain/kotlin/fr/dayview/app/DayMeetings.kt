package fr.dayview.app

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Local midnight of [now]'s day and the next local midnight (23 or 25 hours apart on DST days). */
fun localDayBounds(now: Instant, timeZone: TimeZone): Pair<Instant, Instant> {
    val today = now.toLocalDateTime(timeZone).date
    return today.atStartOfDayIn(timeZone) to today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone)
}

/**
 * Meetings over the whole local day: focus-titled events excluded, overlaps merged. Independent
 * of the net-time setting; empty without permission or when the calendar cannot be read.
 */
fun probeDayMeetings(
    source: CalendarSource,
    includedCalendarIds: Set<String>,
    now: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): List<BusyInterval> {
    if (!runCatching { source.hasPermission() }.getOrDefault(false)) return emptyList()
    val (dayStart, dayEnd) = localDayBounds(now, timeZone)
    return runCatching { source.busyIntervals(dayStart, dayEnd, includedCalendarIds) }
        .map { intervals -> mergeBusyIntervals(intervals.filterNot { it.isFocusBlock() }) }
        .getOrDefault(emptyList())
}
