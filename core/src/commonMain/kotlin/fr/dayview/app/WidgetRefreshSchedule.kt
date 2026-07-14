package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The next epoch-milliseconds instant at which a placed widget's reading changes and it
 * must be redrawn: the day's start, the top of each hour while the day runs, the day's
 * end, and — once the day is over — tomorrow's start. Callers arm an idle-safe alarm at
 * this instant and re-arm on each fire, so the widget stays current through Doze without
 * relying on the deferred [updatePeriodMillis] update. The result is always strictly in
 * the future relative to [nowMillis].
 */
fun nextWidgetRefreshMillis(
    nowMillis: Long,
    startMinutesOfDay: Int,
    endMinutesOfDay: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Long {
    val safeStartMinutes = startMinutesOfDay.coerceIn(0, 23 * 60 + 29)
    val safeEndMinutes = endMinutesOfDay.coerceIn(safeStartMinutes + 30, 23 * 60 + 59)
    val now = Instant.fromEpochMilliseconds(nowMillis)
    val localNow = now.toLocalDateTime(timeZone)

    fun dayBoundary(date: LocalDateTime, minutesOfDay: Int): Instant = LocalDateTime(date.year, date.month, date.day, minutesOfDay / 60, minutesOfDay % 60).toInstant(timeZone)

    val todayStart = dayBoundary(localNow, safeStartMinutes)
    val todayEnd = dayBoundary(localNow, safeEndMinutes)
    val tomorrowStart = dayBoundary((now + 1.days).toLocalDateTime(timeZone), safeStartMinutes)

    val candidates = mutableListOf(todayStart, todayEnd, tomorrowStart)
    if (now >= todayStart && now < todayEnd) {
        val topOfHour = LocalDateTime(localNow.year, localNow.month, localNow.day, localNow.hour, 0).toInstant(timeZone)
        candidates += topOfHour + 1.hours
    }

    return candidates.filter { it > now }.min().toEpochMilliseconds()
}
