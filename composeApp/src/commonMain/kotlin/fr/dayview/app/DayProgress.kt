package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

data class DayProgress(
    val remaining: Duration,
    val remainingRatio: Float,
    val elapsedRatio: Float,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val hasStarted: Boolean,
    val isFinished: Boolean,
) {
    val remainingHours: Long get() = remaining.inWholeHours
    val remainingMinutes: Long get() = remaining.inWholeMinutes % 60
    val remainingSeconds: Long get() = remaining.inWholeSeconds % 60
    val percentageRemaining: Int get() = (remainingRatio * 100).roundToInt()
}

fun currentMomentAngleDegrees(remainingRatio: Float): Float = -90f + (1f - remainingRatio.coerceIn(0f, 1f)) * 360f

fun calculateDayProgress(
    now: Instant,
    startMinutesOfDay: Int,
    endMinutesOfDay: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): DayProgress {
    val safeStartMinutes = startMinutesOfDay.coerceIn(0, 23 * 60 + 29)
    val safeEndMinutes = endMinutesOfDay.coerceIn(safeStartMinutes + 30, 23 * 60 + 59)
    val localNow = now.toLocalDateTime(timeZone)
    val startHour = safeStartMinutes / 60
    val startMinute = safeStartMinutes % 60
    val endHour = safeEndMinutes / 60
    val endMinute = safeEndMinutes % 60
    val start = LocalDateTime(
        year = localNow.year,
        month = localNow.month,
        day = localNow.day,
        hour = startHour,
        minute = startMinute,
    ).toInstant(timeZone)
    val end = LocalDateTime(
        year = localNow.year,
        month = localNow.month,
        day = localNow.day,
        hour = endHour,
        minute = endMinute,
    ).toInstant(timeZone)

    val duration = (end - start).coerceAtLeast(1.milliseconds)
    val remaining = (end - now).coerceIn(Duration.ZERO, duration)
    val remainingRatio = (remaining / duration).toFloat()

    return DayProgress(
        remaining = remaining,
        remainingRatio = remainingRatio,
        elapsedRatio = 1f - remainingRatio,
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute,
        hasStarted = now >= start,
        isFinished = remaining == Duration.ZERO,
    )
}
