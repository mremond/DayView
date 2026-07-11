package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

data class BusyInterval(
    val startMillis: Long,
    val endMillis: Long,
    val titles: List<String> = emptyList(),
)

fun mergeBusyIntervals(intervals: List<BusyInterval>): List<BusyInterval> {
    val sorted = intervals.filter { it.endMillis > it.startMillis }.sortedBy { it.startMillis }
    val merged = mutableListOf<BusyInterval>()
    for (interval in sorted) {
        val last = merged.lastOrNull()
        if (last != null && interval.startMillis <= last.endMillis) {
            merged[merged.lastIndex] = last.copy(
                endMillis = maxOf(last.endMillis, interval.endMillis),
                titles = last.titles + interval.titles,
            )
        } else {
            merged.add(interval)
        }
    }
    return merged
}

data class NetTime(
    val netDayMillis: Long,
    val netRemainingMillis: Long,
    val busyRemainingMillis: Long,
)

fun dayWindowMillis(
    nowMillis: Long,
    startMinutesOfDay: Int,
    endMinutesOfDay: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Pair<Long, Long> {
    val safeStart = startMinutesOfDay.coerceIn(0, 23 * 60 + 29)
    val safeEnd = endMinutesOfDay.coerceIn(safeStart + 30, 23 * 60 + 59)
    val localNow = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone)
    fun at(minutes: Int) = LocalDateTime(
        year = localNow.year,
        month = localNow.month,
        day = localNow.day,
        hour = minutes / 60,
        minute = minutes % 60,
    ).toInstant(timeZone).toEpochMilliseconds()
    return at(safeStart) to at(safeEnd)
}

private fun overlapMillis(start: Long, end: Long, from: Long, to: Long): Long =
    (minOf(end, to) - maxOf(start, from)).coerceAtLeast(0)

fun calculateNetTime(
    progress: DayProgress,
    nowMillis: Long,
    windowStartMillis: Long,
    windowEndMillis: Long,
    busy: List<BusyInterval>,
): NetTime {
    val clipped = mergeBusyIntervals(
        busy.map {
            it.copy(
                startMillis = it.startMillis.coerceIn(windowStartMillis, windowEndMillis),
                endMillis = it.endMillis.coerceIn(windowStartMillis, windowEndMillis),
            )
        },
    )
    val totalBusy = clipped.sumOf { it.endMillis - it.startMillis }
    val aheadFrom = nowMillis.coerceIn(windowStartMillis, windowEndMillis)
    val busyRemaining = clipped.sumOf { overlapMillis(it.startMillis, it.endMillis, aheadFrom, windowEndMillis) }
    val windowDuration = (windowEndMillis - windowStartMillis).coerceAtLeast(0)
    return NetTime(
        netDayMillis = (windowDuration - totalBusy).coerceAtLeast(0),
        netRemainingMillis = (progress.remainingMillis - busyRemaining).coerceAtLeast(0),
        busyRemainingMillis = busyRemaining,
    )
}
