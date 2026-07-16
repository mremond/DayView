package fr.dayview.app

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class UpcomingAvailabilityTest {
    private val tz = TimeZone.UTC
    private val from = LocalDate(2026, 1, 15)

    private fun at(day: Int, hour: Int, minute: Int = 0): Instant =
        LocalDateTime(2026, 1, day, hour, minute).toInstant(tz)

    private fun busy(startDay: Int, startHour: Int, endDay: Int, endHour: Int, vararg titles: String) =
        BusyInterval(at(startDay, startHour), at(endDay, endHour), titles.toList())

    private fun compute(busy: List<BusyInterval>) =
        calculateUpcomingAvailability(from, 3, 8 * 60, 18 * 60, busy, tz)

    @Test
    fun noBusyMeansNetEqualsFullWindow() {
        val days = compute(emptyList())
        assertEquals(3, days.size)
        days.forEach {
            assertEquals(10.hours, it.window)
            assertEquals(Duration.ZERO, it.busy)
            assertEquals(10.hours, it.net)
        }
        assertEquals(LocalDate(2026, 1, 15), days[0].date)
        assertEquals(LocalDate(2026, 1, 17), days[2].date)
    }

    @Test
    fun partialBusySubtractsFromNet() {
        val days = compute(listOf(busy(15, 9, 15, 11)))
        assertEquals(2.hours, days[0].busy)
        assertEquals(8.hours, days[0].net)
        assertEquals(10.hours, days[1].net)
    }

    @Test
    fun overlappingBusyIsMergedNotDoubleCounted() {
        val days = compute(listOf(busy(15, 9, 15, 11), busy(15, 10, 15, 12)))
        assertEquals(3.hours, days[0].busy)
        assertEquals(7.hours, days[0].net)
    }

    @Test
    fun busyOutsideWindowIsIgnored() {
        val days = compute(listOf(busy(15, 6, 15, 7), busy(15, 19, 15, 20)))
        assertEquals(Duration.ZERO, days[0].busy)
        assertEquals(10.hours, days[0].net)
    }

    @Test
    fun fullyBookedDayHasZeroNet() {
        val days = compute(listOf(busy(16, 8, 16, 18)))
        assertEquals(10.hours, days[1].busy)
        assertEquals(Duration.ZERO, days[1].net)
    }

    @Test
    fun busySpanningDayBoundaryIsClippedPerDay() {
        // 15th 17:00 -> 16th 09:00: one hour inside day 0's window, one inside day 1's.
        val days = compute(listOf(busy(15, 17, 16, 9)))
        assertEquals(1.hours, days[0].busy)
        assertEquals(1.hours, days[1].busy)
        assertEquals(9.hours, days[0].net)
        assertEquals(9.hours, days[1].net)
    }

    @Test
    fun unionWindowSpansFirstStartToLastEnd() {
        val (start, end) = upcomingUnionWindow(from, 3, 8 * 60, 18 * 60, tz)
        assertEquals(at(15, 8), start)
        assertEquals(at(17, 18), end)
    }
}
