package fr.dayview.app

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class DayWindowForTest {
    @Test
    fun buildsWindowForGivenDateInUtc() {
        val date = LocalDate(2026, 1, 15)
        val (start, end) = dayWindowFor(date, 8 * 60, 18 * 60, TimeZone.UTC)
        assertEquals(LocalDateTime(2026, 1, 15, 8, 0).toInstant(TimeZone.UTC), start)
        assertEquals(LocalDateTime(2026, 1, 15, 18, 0).toInstant(TimeZone.UTC), end)
    }

    @Test
    fun dayWindowDelegatesToTodaysLocalDate() {
        val now = LocalDateTime(2026, 1, 15, 12, 30).toInstant(TimeZone.UTC)
        assertEquals(
            dayWindowFor(LocalDate(2026, 1, 15), 8 * 60, 18 * 60, TimeZone.UTC),
            dayWindow(now, 8 * 60, 18 * 60, TimeZone.UTC),
        )
    }

    @Test
    fun windowMinutesAreClampedToLegalRange() {
        // start below 0 clamps to 0; end below start+30 clamps up to start+30.
        val (start, end) = dayWindowFor(LocalDate(2026, 1, 15), -100, 10, TimeZone.UTC)
        assertEquals(LocalDateTime(2026, 1, 15, 0, 0).toInstant(TimeZone.UTC), start)
        assertEquals(LocalDateTime(2026, 1, 15, 0, 30).toInstant(TimeZone.UTC), end)
    }
}
