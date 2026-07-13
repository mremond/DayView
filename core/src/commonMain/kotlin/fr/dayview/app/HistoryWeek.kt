package fr.dayview.app

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber

/** One cell in the week grid: a day key and its captured record (if any). */
data class HistoryWeekDay(val dayKey: Long, val record: DayHistoryRecord?)

/** The 7 Monday→Sunday day keys of the calendar week containing [todayKey]. */
fun weekDaysEndingAt(todayKey: Long): List<Long> {
    val today = LocalDate.fromEpochDays(todayKey.toInt())
    val monday = today.toEpochDays() - (today.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber)
    return (0..6).map { monday + it }
}
