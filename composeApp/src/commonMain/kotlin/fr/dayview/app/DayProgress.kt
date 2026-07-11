package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

data class DayProgress(
    val remainingMillis: Long,
    val remainingRatio: Float,
    val elapsedRatio: Float,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val hasStarted: Boolean,
    val isFinished: Boolean,
) {
    val remainingHours: Long get() = remainingMillis / 3_600_000
    val remainingMinutes: Long get() = (remainingMillis / 60_000) % 60
    val remainingSeconds: Long get() = (remainingMillis / 1_000) % 60
    val percentageRemaining: Int get() = (remainingRatio * 100).roundToInt()
}

fun currentMomentAngleDegrees(remainingRatio: Float): Float = -90f + (1f - remainingRatio.coerceIn(0f, 1f)) * 360f

fun calculateDayProgress(
    nowMillis: Long,
    startMinutesOfDay: Int,
    endMinutesOfDay: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): DayProgress {
    val safeStartMinutes = startMinutesOfDay.coerceIn(0, 23 * 60 + 29)
    val safeEndMinutes = endMinutesOfDay.coerceIn(safeStartMinutes + 30, 23 * 60 + 59)
    val now = Instant.fromEpochMilliseconds(nowMillis)
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

    val startMillis = start.toEpochMilliseconds()
    val endMillis = end.toEpochMilliseconds()
    val duration = (endMillis - startMillis).coerceAtLeast(1)
    val remaining = (endMillis - nowMillis).coerceIn(0, duration)
    val remainingRatio = remaining.toFloat() / duration.toFloat()

    return DayProgress(
        remainingMillis = remaining,
        remainingRatio = remainingRatio,
        elapsedRatio = 1f - remainingRatio,
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute,
        hasStarted = nowMillis >= startMillis,
        isFinished = remaining == 0L,
    )
}
