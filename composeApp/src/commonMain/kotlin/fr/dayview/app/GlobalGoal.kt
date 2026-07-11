package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlin.math.ceil

const val GOAL_DATE_PLACEHOLDER = "JJ/MM/AAAA HH:MM"

fun parseGoalDeadline(
    value: String,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Long? {
    val match = Regex("""^(\d{2})/(\d{2})/(\d{4})\s+(\d{2}):(\d{2})$""").matchEntire(value.trim())
        ?: return null
    val (day, month, year, hour, minute) = match.destructured

    return runCatching {
        val local = LocalDateTime.parse("$year-$month-${day}T$hour:$minute:00")
        val instant = local.toInstant(timeZone)
        // Some time zones skip local times during the spring clock change.
        // Reject those values instead of silently moving the user's deadline.
        require(instant.toLocalDateTime(timeZone) == local)
        instant.toEpochMilliseconds()
    }.getOrNull()
}

fun formatGoalDeadline(
    epochMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val value = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone)
    return "${value.day.toString().padStart(2, '0')}/" +
        "${(value.month.ordinal + 1).toString().padStart(2, '0')}/${value.year} " +
        "${value.hour.toString().padStart(2, '0')}:${value.minute.toString().padStart(2, '0')}"
}

fun calculateGoalWorkingMillis(
    nowMillis: Long,
    deadlineMillis: Long,
    startMinutesOfDay: Int,
    endMinutesOfDay: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Long {
    if (deadlineMillis <= nowMillis) return 0

    val safeStart = startMinutesOfDay.coerceIn(0, 23 * 60 + 29)
    val safeEnd = endMinutesOfDay.coerceIn(safeStart + 30, 23 * 60 + 59)
    val startHour = safeStart / 60
    val startMinute = safeStart % 60
    val endHour = safeEnd / 60
    val endMinute = safeEnd % 60
    val deadline = Instant.fromEpochMilliseconds(deadlineMillis)
    var date = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date
    val deadlineDate = deadline.toLocalDateTime(timeZone).date
    var totalMillis = 0L

    while (date <= deadlineDate) {
        val workStart = LocalDateTime(
            year = date.year,
            month = date.month,
            day = date.day,
            hour = startHour,
            minute = startMinute,
        ).toInstant(timeZone).toEpochMilliseconds()
        val workEnd = LocalDateTime(
            year = date.year,
            month = date.month,
            day = date.day,
            hour = endHour,
            minute = endMinute,
        ).toInstant(timeZone).toEpochMilliseconds()
        val overlapStart = maxOf(nowMillis, workStart)
        val overlapEnd = minOf(deadlineMillis, workEnd)
        if (overlapEnd > overlapStart) totalMillis += overlapEnd - overlapStart
        date = date.plus(1, DateTimeUnit.DAY)
    }

    return totalMillis
}

fun formatGoalWorkingHours(workingMillis: Long, deadlineReached: Boolean): String {
    if (deadlineReached) return "Échéance atteinte"
    val hours = ceil(workingMillis / 3_600_000.0).toLong()
    return if (hours > 0) "Encore $hours h" else "Moins d’une heure de travail"
}
