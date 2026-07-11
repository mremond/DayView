package fr.dayview.app

import kotlinx.datetime.TimeZone
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlobalGoalTest {
    private val zone = TimeZone.of("Europe/Paris")

    @Test
    fun deadlineRoundTripsThroughTheFrenchDisplayFormat() {
        val parsed = parseGoalDeadline("24/12/2026 18:30", zone)

        assertTrue(parsed != null)
        assertEquals("24/12/2026 18:30", formatGoalDeadline(parsed, zone))
    }

    @Test
    fun invalidCalendarDateIsRejected() {
        assertNull(parseGoalDeadline("31/02/2026 18:30", zone))
        assertNull(parseGoalDeadline("29/02/2026 18:30", zone))
        assertNull(parseGoalDeadline("01/01/2026 24:00", zone))
        assertNull(parseGoalDeadline("01/01/2026 12:60", zone))
        assertNull(parseGoalDeadline("1/1/2026 12:00", zone))
        assertNull(parseGoalDeadline("demain", zone))
    }

    @Test
    fun leapDayIsAccepted() {
        val parsed = parseGoalDeadline("29/02/2028 09:15", zone)

        assertTrue(parsed != null)
        assertEquals("29/02/2028 09:15", formatGoalDeadline(parsed, zone))
    }

    @Test
    fun nonexistentLocalTimeIsRejectedInsteadOfShifted() {
        // Europe/Paris jumps from 01:59 to 03:00 on this date.
        assertNull(parseGoalDeadline("29/03/2026 02:30", zone))
    }

    @Test
    fun ambiguousAutumnTimeRemainsRoundTrippable() {
        val parsed = parseGoalDeadline("25/10/2026 02:30", zone)

        assertTrue(parsed != null)
        assertEquals("25/10/2026 02:30", formatGoalDeadline(parsed, zone))
    }

    @Test
    fun globalGoalCountsOnlyConfiguredWorkingHours() {
        val now = LocalDateTime(2026, 7, 13, 12, 0).toInstant(zone).toEpochMilliseconds()
        val deadline = LocalDateTime(2026, 7, 15, 12, 0).toInstant(zone).toEpochMilliseconds()

        val remaining = calculateGoalWorkingMillis(
            nowMillis = now,
            deadlineMillis = deadline,
            startMinutesOfDay = 8 * 60,
            endMinutesOfDay = 18 * 60,
            timeZone = zone,
        )

        // Monday 12–18, Tuesday 08–18, Wednesday 08–12.
        assertEquals(20 * 3_600_000L, remaining)
        assertEquals("Encore 20 h", formatGoalWorkingHours(remaining, deadlineReached = false))
    }

    @Test
    fun timeOutsideTheWorkingDayIsIgnored() {
        val now = LocalDateTime(2026, 7, 13, 20, 0).toInstant(zone).toEpochMilliseconds()
        val deadline = LocalDateTime(2026, 7, 14, 10, 0).toInstant(zone).toEpochMilliseconds()

        val remaining = calculateGoalWorkingMillis(now, deadline, 8 * 60, 18 * 60, zone)

        assertEquals(2 * 3_600_000L, remaining)
    }

    @Test
    fun fullWorkingDayIsCountedWhenNowIsBeforeStart() {
        val now = LocalDateTime(2026, 7, 13, 6, 0).toInstant(zone).toEpochMilliseconds()
        val deadline = LocalDateTime(2026, 7, 13, 20, 0).toInstant(zone).toEpochMilliseconds()

        val remaining = calculateGoalWorkingMillis(now, deadline, 8 * 60, 18 * 60, zone)

        assertEquals(10 * 3_600_000L, remaining)
    }

    @Test
    fun noWorkIsCountedWhenDeadlineIsBeforeStart() {
        val now = LocalDateTime(2026, 7, 13, 6, 0).toInstant(zone).toEpochMilliseconds()
        val deadline = LocalDateTime(2026, 7, 13, 7, 59).toInstant(zone).toEpochMilliseconds()

        val remaining = calculateGoalWorkingMillis(now, deadline, 8 * 60, 18 * 60, zone)

        assertEquals(0L, remaining)
        assertEquals("Moins d’une heure de travail", formatGoalWorkingHours(remaining, deadlineReached = false))
    }

    @Test
    fun exactWorkdayBoundariesAreIncludedOnce() {
        val now = LocalDateTime(2026, 7, 13, 8, 0).toInstant(zone).toEpochMilliseconds()
        val deadline = LocalDateTime(2026, 7, 13, 18, 0).toInstant(zone).toEpochMilliseconds()

        val remaining = calculateGoalWorkingMillis(now, deadline, 8 * 60, 18 * 60, zone)

        assertEquals(10 * 3_600_000L, remaining)
    }

    @Test
    fun deadlineAtOrBeforeNowReturnsZero() {
        val now = LocalDateTime(2026, 7, 13, 12, 0).toInstant(zone).toEpochMilliseconds()

        assertEquals(0L, calculateGoalWorkingMillis(now, now, 8 * 60, 18 * 60, zone))
        assertEquals(0L, calculateGoalWorkingMillis(now, now - 1, 8 * 60, 18 * 60, zone))
        assertEquals("Échéance atteinte", formatGoalWorkingHours(42, deadlineReached = true))
    }

    @Test
    fun calculationCrossesMonthAndYearBoundaries() {
        val now = LocalDateTime(2026, 12, 31, 17, 0).toInstant(zone).toEpochMilliseconds()
        val deadline = LocalDateTime(2027, 1, 1, 10, 0).toInstant(zone).toEpochMilliseconds()

        val remaining = calculateGoalWorkingMillis(now, deadline, 8 * 60, 18 * 60, zone)

        assertEquals(3 * 3_600_000L, remaining)
    }

    @Test
    fun springClockChangeUsesActualElapsedHours() {
        val now = LocalDateTime(2026, 3, 29, 0, 0).toInstant(zone).toEpochMilliseconds()
        val deadline = LocalDateTime(2026, 3, 29, 4, 0).toInstant(zone).toEpochMilliseconds()

        val remaining = calculateGoalWorkingMillis(now, deadline, 0, 4 * 60, zone)

        assertEquals(3 * 3_600_000L, remaining)
    }

    @Test
    fun autumnClockChangeUsesActualElapsedHours() {
        val now = LocalDateTime(2026, 10, 25, 0, 0).toInstant(zone).toEpochMilliseconds()
        val deadline = LocalDateTime(2026, 10, 25, 4, 0).toInstant(zone).toEpochMilliseconds()

        val remaining = calculateGoalWorkingMillis(now, deadline, 0, 4 * 60, zone)

        assertEquals(5 * 3_600_000L, remaining)
    }

    @Test
    fun invalidWorkRangeIsClampedToLastHalfHour() {
        val now = LocalDateTime(2026, 7, 13, 23, 29).toInstant(zone).toEpochMilliseconds()
        val deadline = LocalDateTime(2026, 7, 13, 23, 59).toInstant(zone).toEpochMilliseconds()

        val remaining = calculateGoalWorkingMillis(now, deadline, 2_000, -1, zone)

        assertEquals(30 * 60_000L, remaining)
    }

    @Test
    fun displayRoundsAnyPartialHourUp() {
        assertEquals("Encore 1 h", formatGoalWorkingHours(1L, deadlineReached = false))
        assertEquals("Encore 1 h", formatGoalWorkingHours(3_600_000L, deadlineReached = false))
        assertEquals("Encore 2 h", formatGoalWorkingHours(3_600_001L, deadlineReached = false))
        assertFalse(formatGoalWorkingHours(3_600_001L, false).contains("j"))
    }

    @Test
    fun weekendsAreExplicitlyCountedWithCurrentRules() {
        val now = LocalDateTime(2026, 7, 17, 17, 0).toInstant(zone).toEpochMilliseconds()
        val deadline = LocalDateTime(2026, 7, 20, 9, 0).toInstant(zone).toEpochMilliseconds()

        val remaining = calculateGoalWorkingMillis(now, deadline, 8 * 60, 18 * 60, zone)

        // Friday 1h + Saturday 10h + Sunday 10h + Monday 1h.
        assertEquals(22 * 3_600_000L, remaining)
    }

    @Test
    fun fractionalOffsetTimeZoneKeepsTheConfiguredDuration() {
        val kathmandu = TimeZone.of("Asia/Kathmandu")
        val now = LocalDateTime(2026, 7, 13, 8, 0).toInstant(kathmandu).toEpochMilliseconds()
        val deadline = LocalDateTime(2026, 7, 13, 18, 0).toInstant(kathmandu).toEpochMilliseconds()

        val remaining = calculateGoalWorkingMillis(now, deadline, 8 * 60, 18 * 60, kathmandu)

        assertEquals(10 * 3_600_000L, remaining)
    }

    @Test
    fun tenYearHorizonRemainsCorrect() {
        val now = LocalDateTime(2026, 1, 1, 8, 0).toInstant(zone).toEpochMilliseconds()
        val deadline = LocalDateTime(2036, 1, 1, 8, 0).toInstant(zone).toEpochMilliseconds()

        val remaining = calculateGoalWorkingMillis(now, deadline, 8 * 60, 18 * 60, zone)

        // Ten years, including leap days in 2028 and 2032.
        assertEquals(3_652L * 10 * 3_600_000L, remaining)
    }
}
