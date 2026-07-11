package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
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
    fun shortDateDropsTheYearAndTimeWithFrenchMonths() {
        assertEquals("11 juil.", formatGoalDateShort(millis("2026-07-11T08:00"), zone))
        assertEquals("20 juil.", formatGoalDateShort(millis("2026-07-20T10:30"), zone))
        assertEquals("1 août", formatGoalDateShort(millis("2026-08-01T23:59"), zone))
    }

    @Test
    fun shortDateCoversEveryMonthAbbreviation() {
        val expected = listOf(
            "5 janv.", "5 févr.", "5 mars", "5 avr.", "5 mai", "5 juin",
            "5 juil.", "5 août", "5 sept.", "5 oct.", "5 nov.", "5 déc.",
        )
        (1..12).forEach { month ->
            val label = formatGoalDateShort(millis("2026-${month.toString().padStart(2, '0')}-05T12:00"), zone)
            assertEquals(expected[month - 1], label)
        }
    }

    @Test
    fun pickerFieldsRoundTripBackToTheCanonicalLocalString() {
        // For any local instant, seeding the pickers then rebuilding the input must
        // reproduce the same canonical dd/MM/yyyy HH:mm the deadline display uses.
        for (tz in listOf(zone, TimeZone.of("Asia/Kathmandu"), TimeZone.UTC, TimeZone.of("America/Los_Angeles"))) {
            for (iso in listOf("2026-07-11T08:00", "2026-01-01T00:00", "2026-12-31T23:59", "2026-03-30T02:30")) {
                val millis = LocalDateTime.parse(iso).toInstant(tz).toEpochMilliseconds()
                val rebuilt = formatGoalPickerInput(
                    goalPickerDateMillis(millis, tz),
                    goalPickerHour(millis, tz),
                    goalPickerMinute(millis, tz),
                )
                assertEquals(formatGoalDeadline(millis, tz), rebuilt, "tz=$tz iso=$iso")
            }
        }
    }

    @Test
    fun pickerDateMillisIsUtcMidnightRegardlessOfLocalOffset() {
        // 11 juil. 08:00 in Paris (UTC+2) still yields UTC-midnight of 11 juil.
        val millis = LocalDateTime.parse("2026-07-11T08:00").toInstant(zone).toEpochMilliseconds()
        val utcMidnight = LocalDateTime.parse("2026-07-11T00:00").toInstant(TimeZone.UTC).toEpochMilliseconds()
        assertEquals(utcMidnight, goalPickerDateMillis(millis, zone))
        assertEquals(8, goalPickerHour(millis, zone))
        assertEquals(0, goalPickerMinute(millis, zone))
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

    private fun millis(iso: String): Long = LocalDateTime.parse(iso).toInstant(zone).toEpochMilliseconds()

    @Test
    fun goalProgressIsZeroBeforeAndAtTheStart() {
        val start = millis("2026-01-05T08:00")
        val deadline = millis("2026-01-05T18:00")
        assertEquals(0f, calculateGoalProgress(start, start, deadline, 8 * 60, 18 * 60, zone))
        val before = millis("2026-01-04T20:00")
        assertEquals(0f, calculateGoalProgress(before, start, deadline, 8 * 60, 18 * 60, zone))
    }

    @Test
    fun goalProgressReachesHalfwayAcrossOneWorkingDay() {
        val start = millis("2026-01-05T08:00")
        val now = millis("2026-01-05T13:00")
        val deadline = millis("2026-01-05T18:00")
        assertEquals(0.5f, calculateGoalProgress(now, start, deadline, 8 * 60, 18 * 60, zone), 0.001f)
    }

    @Test
    fun goalProgressIsFullAtOrAfterTheDeadline() {
        val start = millis("2026-01-05T08:00")
        val deadline = millis("2026-01-05T18:00")
        assertEquals(1f, calculateGoalProgress(deadline, start, deadline, 8 * 60, 18 * 60, zone))
        val after = millis("2026-01-06T09:00")
        assertEquals(1f, calculateGoalProgress(after, start, deadline, 8 * 60, 18 * 60, zone))
    }

    @Test
    fun goalProgressIsZeroWhenStartEqualsDeadline() {
        val moment = millis("2026-01-05T12:00")
        assertEquals(0f, calculateGoalProgress(moment - 1, moment, moment, 8 * 60, 18 * 60, zone))
    }

    @Test
    fun goalProgressReachesHalfwayAcrossAMultiDaySpan() {
        // Three working days (10h each = 30h). Halfway = 15h = one full day + 5h into the second.
        val start = millis("2026-01-05T08:00")
        val now = millis("2026-01-06T13:00")
        val deadline = millis("2026-01-07T18:00")
        assertEquals(0.5f, calculateGoalProgress(now, start, deadline, 8 * 60, 18 * 60, zone), 0.001f)
    }

    @Test
    fun goalSummaryJoinsTitleAndRemainingHours() {
        val line = formatGoalSummaryLine(
            title = "Livrer la v2",
            deadlineMillis = 1_000L,
            workingMillis = 12 * 3_600_000L,
            deadlineReached = false,
        )
        assertEquals("Livrer la v2 · Encore 12 h", line)
    }

    @Test
    fun goalSummaryShowsRemainingHoursWhenTitleBlank() {
        val line = formatGoalSummaryLine(
            title = "",
            deadlineMillis = 1_000L,
            workingMillis = 12 * 3_600_000L,
            deadlineReached = false,
        )
        assertEquals("Encore 12 h", line)
    }

    @Test
    fun goalSummaryShowsTitleOnlyWhenNoDeadline() {
        val line = formatGoalSummaryLine(
            title = "Livrer la v2",
            deadlineMillis = null,
            workingMillis = 0L,
            deadlineReached = false,
        )
        assertEquals("Livrer la v2", line)
    }

    @Test
    fun goalSummaryShowsDeadlineReached() {
        val line = formatGoalSummaryLine(
            title = "Livrer la v2",
            deadlineMillis = 1_000L,
            workingMillis = 0L,
            deadlineReached = true,
        )
        assertEquals("Livrer la v2 · Échéance atteinte", line)
    }

    @Test
    fun goalSummaryShowsLessThanAnHourWhenNoWorkingTimeLeft() {
        val line = formatGoalSummaryLine(
            title = "",
            deadlineMillis = 1_000L,
            workingMillis = 0L,
            deadlineReached = false,
        )
        assertEquals("Moins d’une heure de travail", line)
    }

    @Test
    fun goalSummaryEmptyWhenNothingSet() {
        val line = formatGoalSummaryLine(
            title = "",
            deadlineMillis = null,
            workingMillis = 0L,
            deadlineReached = false,
        )
        assertEquals("", line)
    }
}
