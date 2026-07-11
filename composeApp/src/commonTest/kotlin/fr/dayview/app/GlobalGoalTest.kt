package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class GlobalGoalTest {
    private val zone = TimeZone.of("Europe/Paris")

    private fun at(iso: String): Instant = LocalDateTime.parse(iso).toInstant(zone)

    @Test
    fun deadlineRoundTripsThroughTheFrenchDisplayFormat() {
        val parsed = parseGoalDeadline("24/12/2026 18:30", zone)

        assertTrue(parsed != null)
        assertEquals("24/12/2026 18:30", formatGoalDeadline(parsed, zone))
    }

    @Test
    fun shortDateDropsTheYearAndTimeWithFrenchMonths() {
        assertEquals("11 juil.", formatGoalDateShort(at("2026-07-11T08:00"), zone))
        assertEquals("20 juil.", formatGoalDateShort(at("2026-07-20T10:30"), zone))
        assertEquals("1 août", formatGoalDateShort(at("2026-08-01T23:59"), zone))
    }

    @Test
    fun shortDateCoversEveryMonthAbbreviation() {
        val expected = listOf(
            "5 janv.", "5 févr.", "5 mars", "5 avr.", "5 mai", "5 juin",
            "5 juil.", "5 août", "5 sept.", "5 oct.", "5 nov.", "5 déc.",
        )
        (1..12).forEach { month ->
            val label = formatGoalDateShort(at("2026-${month.toString().padStart(2, '0')}-05T12:00"), zone)
            assertEquals(expected[month - 1], label)
        }
    }

    @Test
    fun pickerFieldsRoundTripBackToTheCanonicalLocalString() {
        // For any local instant, seeding the pickers then rebuilding the input must
        // reproduce the same canonical dd/MM/yyyy HH:mm the deadline display uses.
        for (tz in listOf(zone, TimeZone.of("Asia/Kathmandu"), TimeZone.UTC, TimeZone.of("America/Los_Angeles"))) {
            for (iso in listOf("2026-07-11T08:00", "2026-01-01T00:00", "2026-12-31T23:59", "2026-03-30T02:30")) {
                val instant = LocalDateTime.parse(iso).toInstant(tz)
                val rebuilt = formatGoalPickerInput(
                    goalPickerDateMillis(instant, tz),
                    goalPickerHour(instant, tz),
                    goalPickerMinute(instant, tz),
                )
                assertEquals(formatGoalDeadline(instant, tz), rebuilt, "tz=$tz iso=$iso")
            }
        }
    }

    @Test
    fun pickerDateMillisIsUtcMidnightRegardlessOfLocalOffset() {
        // 11 juil. 08:00 in Paris (UTC+2) still yields UTC-midnight of 11 juil.
        val instant = LocalDateTime.parse("2026-07-11T08:00").toInstant(zone)
        val utcMidnight = LocalDateTime.parse("2026-07-11T00:00").toInstant(TimeZone.UTC).toEpochMilliseconds()
        assertEquals(utcMidnight, goalPickerDateMillis(instant, zone))
        assertEquals(8, goalPickerHour(instant, zone))
        assertEquals(0, goalPickerMinute(instant, zone))
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
        val now = at("2026-07-13T12:00")
        val deadline = at("2026-07-15T12:00")

        val remaining = calculateGoalWorkingTime(
            now = now,
            deadline = deadline,
            startMinutesOfDay = 8 * 60,
            endMinutesOfDay = 18 * 60,
            timeZone = zone,
        )

        // Monday 12–18, Tuesday 08–18, Wednesday 08–12.
        assertEquals(20.hours, remaining)
        assertEquals(GoalWorkingTime.HoursRemaining(20), goalWorkingTime(remaining, deadlineReached = false))
    }

    @Test
    fun timeOutsideTheWorkingDayIsIgnored() {
        val now = at("2026-07-13T20:00")
        val deadline = at("2026-07-14T10:00")

        val remaining = calculateGoalWorkingTime(now, deadline, 8 * 60, 18 * 60, zone)

        assertEquals(2.hours, remaining)
    }

    @Test
    fun fullWorkingDayIsCountedWhenNowIsBeforeStart() {
        val now = at("2026-07-13T06:00")
        val deadline = at("2026-07-13T20:00")

        val remaining = calculateGoalWorkingTime(now, deadline, 8 * 60, 18 * 60, zone)

        assertEquals(10.hours, remaining)
    }

    @Test
    fun noWorkIsCountedWhenDeadlineIsBeforeStart() {
        val now = at("2026-07-13T06:00")
        val deadline = at("2026-07-13T07:59")

        val remaining = calculateGoalWorkingTime(now, deadline, 8 * 60, 18 * 60, zone)

        assertEquals(Duration.ZERO, remaining)
        assertEquals(GoalWorkingTime.LessThanAnHour, goalWorkingTime(remaining, deadlineReached = false))
    }

    @Test
    fun exactWorkdayBoundariesAreIncludedOnce() {
        val now = at("2026-07-13T08:00")
        val deadline = at("2026-07-13T18:00")

        val remaining = calculateGoalWorkingTime(now, deadline, 8 * 60, 18 * 60, zone)

        assertEquals(10.hours, remaining)
    }

    @Test
    fun deadlineAtOrBeforeNowReturnsZero() {
        val now = at("2026-07-13T12:00")

        assertEquals(Duration.ZERO, calculateGoalWorkingTime(now, now, 8 * 60, 18 * 60, zone))
        assertEquals(Duration.ZERO, calculateGoalWorkingTime(now, now - 1.milliseconds, 8 * 60, 18 * 60, zone))
        assertEquals(GoalWorkingTime.DeadlineReached, goalWorkingTime(Duration.ZERO, deadlineReached = true))
    }

    @Test
    fun calculationCrossesMonthAndYearBoundaries() {
        val now = at("2026-12-31T17:00")
        val deadline = at("2027-01-01T10:00")

        val remaining = calculateGoalWorkingTime(now, deadline, 8 * 60, 18 * 60, zone)

        assertEquals(3.hours, remaining)
    }

    @Test
    fun springClockChangeUsesActualElapsedHours() {
        val now = at("2026-03-29T00:00")
        val deadline = at("2026-03-29T04:00")

        val remaining = calculateGoalWorkingTime(now, deadline, 0, 4 * 60, zone)

        assertEquals(3.hours, remaining)
    }

    @Test
    fun autumnClockChangeUsesActualElapsedHours() {
        val now = at("2026-10-25T00:00")
        val deadline = at("2026-10-25T04:00")

        val remaining = calculateGoalWorkingTime(now, deadline, 0, 4 * 60, zone)

        assertEquals(5.hours, remaining)
    }

    @Test
    fun invalidWorkRangeIsClampedToLastHalfHour() {
        val now = at("2026-07-13T23:29")
        val deadline = at("2026-07-13T23:59")

        val remaining = calculateGoalWorkingTime(now, deadline, 2_000, -1, zone)

        assertEquals(30.minutes, remaining)
    }

    @Test
    fun displayRoundsAnyPartialHourUp() {
        assertEquals(GoalWorkingTime.HoursRemaining(1), goalWorkingTime(1.milliseconds, deadlineReached = false))
        assertEquals(GoalWorkingTime.HoursRemaining(1), goalWorkingTime(1.hours, deadlineReached = false))
        assertEquals(GoalWorkingTime.HoursRemaining(2), goalWorkingTime(1.hours + 1.milliseconds, deadlineReached = false))
    }

    @Test
    fun weekendsAreExplicitlyCountedWithCurrentRules() {
        val now = at("2026-07-17T17:00")
        val deadline = at("2026-07-20T09:00")

        val remaining = calculateGoalWorkingTime(now, deadline, 8 * 60, 18 * 60, zone)

        // Friday 1h + Saturday 10h + Sunday 10h + Monday 1h.
        assertEquals(22.hours, remaining)
    }

    @Test
    fun fractionalOffsetTimeZoneKeepsTheConfiguredDuration() {
        val kathmandu = TimeZone.of("Asia/Kathmandu")
        val now = LocalDateTime(2026, 7, 13, 8, 0).toInstant(kathmandu)
        val deadline = LocalDateTime(2026, 7, 13, 18, 0).toInstant(kathmandu)

        val remaining = calculateGoalWorkingTime(now, deadline, 8 * 60, 18 * 60, kathmandu)

        assertEquals(10.hours, remaining)
    }

    @Test
    fun tenYearHorizonRemainsCorrect() {
        val now = at("2026-01-01T08:00")
        val deadline = at("2036-01-01T08:00")

        val remaining = calculateGoalWorkingTime(now, deadline, 8 * 60, 18 * 60, zone)

        // Ten years, including leap days in 2028 and 2032.
        assertEquals((3_652L * 10).hours, remaining)
    }

    @Test
    fun goalProgressIsZeroBeforeAndAtTheStart() {
        val start = at("2026-01-05T08:00")
        val deadline = at("2026-01-05T18:00")
        assertEquals(0f, calculateGoalProgress(start, start, deadline, 8 * 60, 18 * 60, zone))
        val before = at("2026-01-04T20:00")
        assertEquals(0f, calculateGoalProgress(before, start, deadline, 8 * 60, 18 * 60, zone))
    }

    @Test
    fun goalProgressReachesHalfwayAcrossOneWorkingDay() {
        val start = at("2026-01-05T08:00")
        val now = at("2026-01-05T13:00")
        val deadline = at("2026-01-05T18:00")
        assertEquals(0.5f, calculateGoalProgress(now, start, deadline, 8 * 60, 18 * 60, zone), 0.001f)
    }

    @Test
    fun goalProgressIsFullAtOrAfterTheDeadline() {
        val start = at("2026-01-05T08:00")
        val deadline = at("2026-01-05T18:00")
        assertEquals(1f, calculateGoalProgress(deadline, start, deadline, 8 * 60, 18 * 60, zone))
        val after = at("2026-01-06T09:00")
        assertEquals(1f, calculateGoalProgress(after, start, deadline, 8 * 60, 18 * 60, zone))
    }

    @Test
    fun goalProgressIsZeroWhenStartEqualsDeadline() {
        val moment = at("2026-01-05T12:00")
        assertEquals(0f, calculateGoalProgress(moment - 1.milliseconds, moment, moment, 8 * 60, 18 * 60, zone))
    }

    @Test
    fun goalProgressReachesHalfwayAcrossAMultiDaySpan() {
        // Three working days (10h each = 30h). Halfway = 15h = one full day + 5h into the second.
        val start = at("2026-01-05T08:00")
        val now = at("2026-01-06T13:00")
        val deadline = at("2026-01-07T18:00")
        assertEquals(0.5f, calculateGoalProgress(now, start, deadline, 8 * 60, 18 * 60, zone), 0.001f)
    }

    @Test
    fun goalSummaryJoinsTitleAndRemainingHours() {
        val line = formatGoalSummaryLine(
            title = "Livrer la v2",
            workingHoursLabel = "Encore 12 h",
        )
        assertEquals("Livrer la v2 · Encore 12 h", line)
    }

    @Test
    fun goalSummaryShowsRemainingHoursWhenTitleBlank() {
        val line = formatGoalSummaryLine(
            title = "",
            workingHoursLabel = "Encore 12 h",
        )
        assertEquals("Encore 12 h", line)
    }

    @Test
    fun goalSummaryShowsTitleOnlyWhenNoDeadline() {
        val line = formatGoalSummaryLine(
            title = "Livrer la v2",
            workingHoursLabel = null,
        )
        assertEquals("Livrer la v2", line)
    }

    @Test
    fun goalSummaryShowsDeadlineReached() {
        val line = formatGoalSummaryLine(
            title = "Livrer la v2",
            workingHoursLabel = "Échéance atteinte",
        )
        assertEquals("Livrer la v2 · Échéance atteinte", line)
    }

    @Test
    fun goalSummaryShowsLessThanAnHourWhenNoWorkingTimeLeft() {
        val line = formatGoalSummaryLine(
            title = "",
            workingHoursLabel = "Moins d’une heure de travail",
        )
        assertEquals("Moins d’une heure de travail", line)
    }

    @Test
    fun goalSummaryEmptyWhenNothingSet() {
        val line = formatGoalSummaryLine(
            title = "",
            workingHoursLabel = null,
        )
        assertEquals("", line)
    }
}
