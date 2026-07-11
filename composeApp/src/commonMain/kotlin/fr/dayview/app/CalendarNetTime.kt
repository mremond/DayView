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

data class BusyArc(
    val startAngleDegrees: Float,
    val sweepDegrees: Float,
    val titles: List<String>,
)

/** Durée « H h MM » (ou « MM min » sous une heure) pour l'affichage du temps net. */
fun formatDurationHm(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h ${minutes.toString().padStart(2, '0')}" else "$minutes min"
}

/** Heure locale « HH:mm » d'un instant, pour l'overlay de survol. */
fun formatClockHm(epochMillis: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val value = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone)
    return "${value.hour.toString().padStart(2, '0')}:${value.minute.toString().padStart(2, '0')}"
}

/** Instant (millis) correspondant à un angle d'arc dans la fenêtre [start, end]. */
fun angleToMillis(angleDegrees: Float, windowStartMillis: Long, windowEndMillis: Long): Long {
    val fraction = ((angleDegrees + 90f) / 360f).coerceIn(0f, 1f)
    return windowStartMillis + (fraction * (windowEndMillis - windowStartMillis)).toLong()
}

/** Vrai si l'angle (degrés, convention drawArc) tombe dans le balayage de l'arc, wraparound compris. */
fun arcContainsAngle(arc: BusyArc, angleDegrees: Float): Boolean {
    val delta = (((angleDegrees - arc.startAngleDegrees) % 360f) + 360f) % 360f
    return delta <= arc.sweepDegrees
}

fun busyArcs(
    windowStartMillis: Long,
    windowEndMillis: Long,
    busy: List<BusyInterval>,
): List<BusyArc> {
    val duration = (windowEndMillis - windowStartMillis).toFloat()
    if (duration <= 0f) return emptyList()
    val clipped = mergeBusyIntervals(
        busy.map {
            it.copy(
                startMillis = it.startMillis.coerceIn(windowStartMillis, windowEndMillis),
                endMillis = it.endMillis.coerceIn(windowStartMillis, windowEndMillis),
            )
        },
    )
    return clipped.map {
        val fStart = (it.startMillis - windowStartMillis) / duration
        val fEnd = (it.endMillis - windowStartMillis) / duration
        BusyArc(
            startAngleDegrees = -90f + fStart * 360f,
            sweepDegrees = (fEnd - fStart) * 360f,
            titles = it.titles,
        )
    }
}
