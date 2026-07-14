package fr.dayview.app

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.Instant

/**
 * Long-term goal calculations ("Cap" in French). The shorter `goal` identifier is retained in
 * code and persisted preference keys for compatibility; it never means a goal limited to today.
 */
fun parseGoalDeadline(
    value: String,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Instant? {
    val match = Regex("""^(\d{2})/(\d{2})/(\d{4})\s+(\d{2}):(\d{2})$""").matchEntire(value.trim())
        ?: return null
    val (day, month, year, hour, minute) = match.destructured

    return runCatching {
        val local = LocalDateTime.parse("$year-$month-${day}T$hour:$minute:00")
        val instant = local.toInstant(timeZone)
        // Some time zones skip local times during the spring clock change.
        // Reject those values instead of silently moving the user's deadline.
        require(instant.toLocalDateTime(timeZone) == local)
        instant
    }.getOrNull()
}

fun formatGoalDeadline(
    instant: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val value = instant.toLocalDateTime(timeZone)
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
    instant: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val value = instant.toLocalDateTime(timeZone)
    return "${value.day} ${FRENCH_SHORT_MONTHS[value.month.ordinal]}"
}

/**
 * UTC-midnight millis of the local calendar day of [instant] — the value a
 * Material3 DatePicker (rememberDatePickerState) expects.
 */
fun goalPickerDateMillis(
    instant: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Long {
    val date = instant.toLocalDateTime(timeZone).date
    return LocalDateTime(date.year, date.month, date.day, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
}

fun goalPickerHour(
    instant: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Int = instant.toLocalDateTime(timeZone).hour

fun goalPickerMinute(
    instant: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Int = instant.toLocalDateTime(timeZone).minute

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

fun calculateGoalWorkingTime(
    now: Instant,
    deadline: Instant,
    startMinutesOfDay: Int,
    endMinutesOfDay: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Duration {
    if (deadline <= now) return Duration.ZERO

    val safeStart = startMinutesOfDay.coerceIn(0, 23 * 60 + 29)
    val safeEnd = endMinutesOfDay.coerceIn(safeStart + 30, 23 * 60 + 59)
    val startHour = safeStart / 60
    val startMinute = safeStart % 60
    val endHour = safeEnd / 60
    val endMinute = safeEnd % 60
    var date = now.toLocalDateTime(timeZone).date
    val deadlineDate = deadline.toLocalDateTime(timeZone).date
    var total = Duration.ZERO

    while (date <= deadlineDate) {
        val workStart = LocalDateTime(
            year = date.year,
            month = date.month,
            day = date.day,
            hour = startHour,
            minute = startMinute,
        ).toInstant(timeZone)
        val workEnd = LocalDateTime(
            year = date.year,
            month = date.month,
            day = date.day,
            hour = endHour,
            minute = endMinute,
        ).toInstant(timeZone)
        val overlapStart = maxOf(now, workStart)
        val overlapEnd = minOf(deadline, workEnd)
        if (overlapEnd > overlapStart) total += overlapEnd - overlapStart
        date = date.plus(1, DateTimeUnit.DAY)
    }

    return total
}

sealed interface GoalWorkingTime {
    /** The deadline has already passed. */
    data object DeadlineReached : GoalWorkingTime

    /** [hours] whole working hours remain before the deadline (always >= 1). */
    data class HoursRemaining(val hours: Long) : GoalWorkingTime

    /** Less than a full working hour remains before the deadline. */
    data object LessThanAnHour : GoalWorkingTime
}

/**
 * Classifies how much configured working time is left before the deadline. The wording
 * lives in string resources; [goalWorkingTimeLabel] renders this to a localized label.
 */
fun goalWorkingTime(working: Duration, deadlineReached: Boolean): GoalWorkingTime {
    if (deadlineReached) return GoalWorkingTime.DeadlineReached
    val hours = ceil(working.toDouble(DurationUnit.HOURS)).toLong()
    return if (hours > 0) GoalWorkingTime.HoursRemaining(hours) else GoalWorkingTime.LessThanAnHour
}

fun calculateGoalProgress(
    now: Instant,
    start: Instant,
    deadline: Instant,
    startMinutesOfDay: Int,
    endMinutesOfDay: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Float {
    if (now >= deadline) return 1f
    val total = calculateGoalWorkingTime(start, deadline, startMinutesOfDay, endMinutesOfDay, timeZone)
    if (total <= Duration.ZERO) return 0f
    val effectiveNow = maxOf(now, start)
    val remaining = calculateGoalWorkingTime(effectiveNow, deadline, startMinutesOfDay, endMinutesOfDay, timeZone)
    val elapsed = total - remaining
    return (elapsed / total).toFloat().coerceIn(0f, 1f)
}

fun formatGoalSummaryLine(
    title: String,
    workingHoursLabel: String?,
): String = listOfNotNull(
    title.ifBlank { null },
    workingHoursLabel,
).joinToString(" · ")
