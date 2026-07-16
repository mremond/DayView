package fr.dayview.app

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

/** How many upcoming days the day-over screen summarises. */
const val UPCOMING_DAY_COUNT = 3

/**
 * Net availability of a single upcoming day. Carries the gross [window] and merged [busy]
 * (not only [net]) so a future per-day ring can render the busy share without a data change.
 */
data class UpcomingDayAvailability(
    val date: LocalDate,
    val window: Duration,
    val busy: Duration,
    val net: Duration,
)

/**
 * Net-available time for [dayCount] consecutive days starting at [fromDate]. Each day reuses the
 * global [startMinutes]/[endMinutes] window; [busy] is clipped to each day's window and merged so
 * overlapping events are not double-counted. net = (window - busy), floored at zero.
 */
fun calculateUpcomingAvailability(
    fromDate: LocalDate,
    dayCount: Int,
    startMinutes: Int,
    endMinutes: Int,
    busy: List<BusyInterval>,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): List<UpcomingDayAvailability> {
    val fromEpoch = fromDate.toEpochDays()
    return (0 until dayCount).map { offset ->
        val date = LocalDate.fromEpochDays((fromEpoch + offset).toInt())
        val (start, end) = dayWindowFor(date, startMinutes, endMinutes, timeZone)
        val window = (end - start).coerceAtLeast(Duration.ZERO)
        val busyTotal = busyWithinWindow(busy, start, end)
        UpcomingDayAvailability(
            date = date,
            window = window,
            busy = busyTotal,
            net = (window - busyTotal).coerceAtLeast(Duration.ZERO),
        )
    }
}

/** The single instant span covering all [dayCount] upcoming day windows, for one calendar read. */
fun upcomingUnionWindow(
    fromDate: LocalDate,
    dayCount: Int,
    startMinutes: Int,
    endMinutes: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Pair<Instant, Instant> {
    val fromEpoch = fromDate.toEpochDays()
    val lastDate = LocalDate.fromEpochDays((fromEpoch + (dayCount - 1)).toInt())
    val start = dayWindowFor(fromDate, startMinutes, endMinutes, timeZone).first
    val end = dayWindowFor(lastDate, startMinutes, endMinutes, timeZone).second
    return start to end
}
