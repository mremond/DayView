package fr.dayview.app

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.ceil
import kotlin.time.Instant

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

private val FRENCH_SHORT_MONTHS = listOf(
    "janv.", "févr.", "mars", "avr.", "mai", "juin",
    "juil.", "août", "sept.", "oct.", "nov.", "déc.",
)

/** Glanceable date-only label, e.g. "11 juil.". No year, no time. */
fun formatGoalDateShort(
    epochMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val value = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone)
    return "${value.day} ${FRENCH_SHORT_MONTHS[value.month.ordinal]}"
}

/**
 * UTC-midnight millis of the local calendar day of [epochMillis] — the value a
 * Material3 DatePicker expects for [androidx.compose.material3.rememberDatePickerState].
 */
fun goalPickerDateMillis(
    epochMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Long {
    val date = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone).date
    return LocalDateTime(date.year, date.month, date.day, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
}

fun goalPickerHour(
    epochMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Int = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone).hour

fun goalPickerMinute(
    epochMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Int = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone).minute

/**
 * Canonical `dd/MM/yyyy HH:mm` string from a DatePicker's selected day (UTC-midnight
 * millis) plus a TimeInput's hour/minute, ready to feed [parseGoalDeadline].
 */
fun formatGoalPickerInput(
    selectedUtcDateMillis: Long,
    hour: Int,
    minute: Int,
): String {
    val date = Instant.fromEpochMilliseconds(selectedUtcDateMillis).toLocalDateTime(TimeZone.UTC).date
    return "${date.day.toString().padStart(2, '0')}/" +
        "${(date.month.ordinal + 1).toString().padStart(2, '0')}/${date.year} " +
        "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
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

fun calculateGoalProgress(
    nowMillis: Long,
    startMillis: Long,
    deadlineMillis: Long,
    startMinutesOfDay: Int,
    endMinutesOfDay: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Float {
    if (nowMillis >= deadlineMillis) return 1f
    val total = calculateGoalWorkingMillis(startMillis, deadlineMillis, startMinutesOfDay, endMinutesOfDay, timeZone)
    if (total <= 0L) return 0f
    val effectiveNow = maxOf(nowMillis, startMillis)
    val remaining = calculateGoalWorkingMillis(effectiveNow, deadlineMillis, startMinutesOfDay, endMinutesOfDay, timeZone)
    val elapsed = total - remaining
    return (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

fun formatGoalSummaryLine(
    title: String,
    deadlineMillis: Long?,
    workingMillis: Long,
    deadlineReached: Boolean,
): String = listOfNotNull(
    title.ifBlank { null },
    deadlineMillis?.let { formatGoalWorkingHours(workingMillis, deadlineReached) },
).joinToString(" · ")
