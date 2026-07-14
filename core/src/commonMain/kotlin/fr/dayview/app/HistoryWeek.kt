package fr.dayview.app

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlin.time.Instant

/**
 * One cell in the week grid: a day key, its captured record (if any), and the live instant
 * to project the ring at. [now] is non-null only for today (still in progress); archived days
 * leave it null so the ring freezes at the day's end and reads as completed.
 */
data class HistoryWeekDay(val dayKey: Long, val record: DayHistoryRecord?, val now: Instant? = null)

/** The 7 Monday→Sunday day keys of the calendar week containing [todayKey]. */
fun weekDaysEndingAt(todayKey: Long): List<Long> {
    val today = LocalDate.fromEpochDays(todayKey.toInt())
    val monday = today.toEpochDays() - (today.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber)
    return (0..6).map { monday + it }
}
