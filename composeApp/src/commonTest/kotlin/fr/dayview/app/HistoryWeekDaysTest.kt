package fr.dayview.app

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryWeekDaysTest {
    @Test
    fun weekDaysEndingAtReturnsMondayThroughSundayForAMidWeekDay() {
        val wednesday = LocalDate(2026, 7, 15).toEpochDays()
        val week = weekDaysEndingAt(wednesday)
        val labels = week.map { LocalDate.fromEpochDays(it.toInt()).dayOfWeek.name }
        assertEquals(
            listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"),
            labels,
        )
        assertEquals(LocalDate(2026, 7, 13).toEpochDays(), week.first())
        assertEquals(LocalDate(2026, 7, 19).toEpochDays(), week.last())
    }

    @Test
    fun weekDaysEndingAtHandlesASundayBoundary() {
        val sunday = LocalDate(2026, 7, 12).toEpochDays()
        val week = weekDaysEndingAt(sunday)
        assertEquals(LocalDate(2026, 7, 6).toEpochDays(), week.first())
        assertEquals(LocalDate(2026, 7, 12).toEpochDays(), week.last())
    }
}
