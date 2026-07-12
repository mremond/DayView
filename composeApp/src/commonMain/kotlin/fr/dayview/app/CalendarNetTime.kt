package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

data class BusyInterval(
    val start: Instant,
    val end: Instant,
    val titles: List<String> = emptyList(),
    val calendarId: String = "",
)

fun mergeBusyIntervals(intervals: List<BusyInterval>): List<BusyInterval> {
    val sorted = intervals.filter { it.end > it.start }.sortedBy { it.start }
    val merged = mutableListOf<BusyInterval>()
    for (interval in sorted) {
        val last = merged.lastOrNull()
        if (last != null && interval.start <= last.end) {
            merged[merged.lastIndex] = last.copy(
                end = maxOf(last.end, interval.end),
                titles = last.titles + interval.titles,
            )
        } else {
            merged.add(interval)
        }
    }
    return merged
}

/**
 * Fusionne les créneaux occupés au sein d'un même calendrier uniquement : deux calendriers
 * qui se chevauchent dans le temps restent distincts (pour le rendu par calendrier), alors
 * que [mergeBusyIntervals] fusionne tout (union, pour le calcul du temps net).
 */
fun mergeBusyIntervalsByCalendar(intervals: List<BusyInterval>): List<BusyInterval> = intervals.groupBy { it.calendarId }.values.flatMap { mergeBusyIntervals(it) }

/** Un calendrier occupé : couleur stable (premier vu) et durée cumulée sur la journée. */
data class BusyCalendar(val calendarId: String, val colorIndex: Int, val total: Duration)

/**
 * Index de couleur par calendrier, dans l'ordre de première apparition (créneaux triés par
 * début), pour rester stables sur la journée — même convention que [detourSources].
 */
fun busyCalendars(intervals: List<BusyInterval>): List<BusyCalendar> {
    val colorByCal = LinkedHashMap<String, Int>()
    val totalByCal = LinkedHashMap<String, Duration>()
    for (interval in intervals.filter { it.end > it.start }.sortedBy { it.start }) {
        val key = interval.calendarId
        colorByCal.getOrPut(key) { colorByCal.size }
        totalByCal[key] = (totalByCal[key] ?: Duration.ZERO) + (interval.end - interval.start)
    }
    return colorByCal.keys.map { BusyCalendar(it, colorByCal.getValue(it), totalByCal.getValue(it)) }
}

/** Un créneau agenda projeté en arc coloré fin sur l'anneau. */
data class BusyBlockArc(
    val startAngleDegrees: Float,
    val sweepDegrees: Float,
    val colorIndex: Int,
    val titles: List<String>,
    val calendarName: String,
)

/**
 * Arcs colorés par calendrier : fusion intra-calendrier, clip à la fenêtre, couleur stable
 * de [busyCalendars], nom depuis [calendarNames] (vide si inconnu). Même convention d'angle
 * que [busyBlockArcs].
 */
fun busyBlockArcs(
    windowStart: Instant,
    windowEnd: Instant,
    intervals: List<BusyInterval>,
    calendarNames: Map<String, String>,
): List<BusyBlockArc> {
    val total = windowEnd - windowStart
    if (total <= Duration.ZERO) return emptyList()
    val colorByCal = busyCalendars(intervals).associate { it.calendarId to it.colorIndex }
    val clipped = mergeBusyIntervalsByCalendar(
        intervals.map {
            it.copy(
                start = it.start.coerceIn(windowStart, windowEnd),
                end = it.end.coerceIn(windowStart, windowEnd),
            )
        },
    )
    return clipped.mapNotNull {
        if (it.end <= it.start) return@mapNotNull null
        val fStart = ((it.start - windowStart) / total).toFloat()
        val fEnd = ((it.end - windowStart) / total).toFloat()
        BusyBlockArc(
            startAngleDegrees = -90f + fStart * 360f,
            sweepDegrees = (fEnd - fStart) * 360f,
            colorIndex = colorByCal[it.calendarId] ?: 0,
            titles = it.titles,
            calendarName = calendarNames[it.calendarId] ?: "",
        )
    }
}

data class NetTime(
    val netDay: Duration,
    val netRemaining: Duration,
    val busyRemaining: Duration,
)

fun dayWindow(
    now: Instant,
    startMinutesOfDay: Int,
    endMinutesOfDay: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Pair<Instant, Instant> {
    val safeStart = startMinutesOfDay.coerceIn(0, 23 * 60 + 29)
    val safeEnd = endMinutesOfDay.coerceIn(safeStart + 30, 23 * 60 + 59)
    val localNow = now.toLocalDateTime(timeZone)
    fun at(minutes: Int) = LocalDateTime(
        year = localNow.year,
        month = localNow.month,
        day = localNow.day,
        hour = minutes / 60,
        minute = minutes % 60,
    ).toInstant(timeZone)
    return at(safeStart) to at(safeEnd)
}

private fun overlap(start: Instant, end: Instant, from: Instant, to: Instant): Duration = (minOf(end, to) - maxOf(start, from)).coerceAtLeast(Duration.ZERO)

fun calculateNetTime(
    progress: DayProgress,
    now: Instant,
    windowStart: Instant,
    windowEnd: Instant,
    busy: List<BusyInterval>,
): NetTime {
    val clipped = mergeBusyIntervals(
        busy.map {
            it.copy(
                start = it.start.coerceIn(windowStart, windowEnd),
                end = it.end.coerceIn(windowStart, windowEnd),
            )
        },
    )
    val totalBusy = clipped.fold(Duration.ZERO) { acc, it -> acc + (it.end - it.start) }
    val aheadFrom = now.coerceIn(windowStart, windowEnd)
    val busyRemaining = clipped.fold(Duration.ZERO) { acc, it -> acc + overlap(it.start, it.end, aheadFrom, windowEnd) }
    val windowDuration = (windowEnd - windowStart).coerceAtLeast(Duration.ZERO)
    return NetTime(
        netDay = (windowDuration - totalBusy).coerceAtLeast(Duration.ZERO),
        netRemaining = (progress.remaining - busyRemaining).coerceAtLeast(Duration.ZERO),
        busyRemaining = busyRemaining,
    )
}

/** Durée « H h MM » (ou « MM min » sous une heure) pour l'affichage du temps net. */
fun formatDurationHm(duration: Duration): String {
    val totalMinutes = duration.inWholeMinutes.coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h ${minutes.toString().padStart(2, '0')}" else "$minutes min"
}

/** Heure locale « HH:mm » d'un instant, pour l'overlay de survol. */
fun formatClockHm(instant: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val value = instant.toLocalDateTime(timeZone)
    return "${value.hour.toString().padStart(2, '0')}:${value.minute.toString().padStart(2, '0')}"
}

/** Instant correspondant à un angle d'arc dans la fenêtre [start, end]. */
fun angleToInstant(angleDegrees: Float, windowStart: Instant, windowEnd: Instant): Instant {
    val fraction = ((angleDegrees + 90f) / 360f).coerceIn(0f, 1f)
    return windowStart + (windowEnd - windowStart) * fraction.toDouble()
}

/** Vrai si l'angle (degrés, convention drawArc) tombe dans le balayage de l'arc, wraparound compris. */
fun arcContainsAngle(startAngleDegrees: Float, sweepDegrees: Float, angleDegrees: Float): Boolean {
    val delta = (((angleDegrees - startAngleDegrees) % 360f) + 360f) % 360f
    return delta <= sweepDegrees
}

/**
 * Écart angulaire (degrés) entre [angleDegrees] et le balayage de l'arc : 0 à l'intérieur,
 * sinon la distance à l'extrémité la plus proche (wraparound compris). Sert à donner une marge
 * de survol aux créneaux très courts, dont l'arc est trop mince pour être visé directement.
 */
fun angularDistanceToArc(startAngleDegrees: Float, sweepDegrees: Float, angleDegrees: Float): Float {
    if (arcContainsAngle(startAngleDegrees, sweepDegrees, angleDegrees)) return 0f
    fun gap(a: Float, b: Float): Float {
        val d = (((a - b) % 360f) + 360f) % 360f
        return minOf(d, 360f - d)
    }
    return minOf(gap(angleDegrees, startAngleDegrees), gap(angleDegrees, startAngleDegrees + sweepDegrees))
}

data class FocusArc(
    val startAngleDegrees: Float,
    val sweepDegrees: Float,
)

private fun clipToWindow(
    intervals: List<FocusPresenceInterval>,
    windowStart: Instant,
    windowEnd: Instant,
): List<FocusPresenceInterval> = intervals.map {
    FocusPresenceInterval(
        start = it.start.coerceIn(windowStart, windowEnd),
        end = it.end.coerceIn(windowStart, windowEnd),
    )
}.filter { it.end > it.start }

/** Project intense-focus intervals to ring arcs (same convention as [busyBlockArcs]). */
fun focusArcs(
    windowStart: Instant,
    windowEnd: Instant,
    intervals: List<FocusPresenceInterval>,
): List<FocusArc> {
    val total = windowEnd - windowStart
    if (total <= Duration.ZERO) return emptyList()
    return clipToWindow(intervals, windowStart, windowEnd).map {
        val fStart = ((it.start - windowStart) / total).toFloat()
        val fEnd = ((it.end - windowStart) / total).toFloat()
        FocusArc(
            startAngleDegrees = -90f + fStart * 360f,
            sweepDegrees = (fEnd - fStart) * 360f,
        )
    }
}

/** Total intense-focus time within the day window. */
fun focusedTime(
    windowStart: Instant,
    windowEnd: Instant,
    intervals: List<FocusPresenceInterval>,
): Duration = clipToWindow(intervals, windowStart, windowEnd)
    .fold(Duration.ZERO) { acc, it -> acc + (it.end - it.start) }
