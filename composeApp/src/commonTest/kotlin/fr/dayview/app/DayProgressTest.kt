package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayProgressTest {
    private val zone = TimeZone.of("Europe/Paris")

    @Test
    fun noonLeavesHalfOfAMidnightToMidnightDay() {
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone).toEpochMilliseconds()
        val result = calculateDayProgress(noon, 0, 23 * 60 + 59, zone)

        assertTrue(result.remainingRatio in .49f..51f)
        assertEquals(11, result.remainingHours)
        assertEquals(59, result.remainingMinutes)
    }

    @Test
    fun countdownStopsAtZeroAfterTheChosenEnd() {
        val late = LocalDateTime(2026, 7, 11, 22, 30).toInstant(zone).toEpochMilliseconds()
        val result = calculateDayProgress(late, 8 * 60, 22 * 60, zone)

        assertEquals(0, result.remainingMillis)
        assertTrue(result.isFinished)
    }

    @Test
    fun circleIsFullBeforeTheChosenStart() {
        val early = LocalDateTime(2026, 7, 11, 7, 30).toInstant(zone).toEpochMilliseconds()
        val result = calculateDayProgress(early, 8 * 60, 18 * 60, zone)

        assertEquals(1f, result.remainingRatio)
        assertTrue(!result.hasStarted)
    }

    @Test
    fun exactStartIsFullButAlreadyStarted() {
        val start = LocalDateTime(2026, 7, 11, 8, 0).toInstant(zone).toEpochMilliseconds()
        val result = calculateDayProgress(start, 8 * 60, 18 * 60, zone)

        assertEquals(1f, result.remainingRatio)
        assertTrue(result.hasStarted)
        assertTrue(!result.isFinished)
    }

    @Test
    fun exactEndIsFinished() {
        val end = LocalDateTime(2026, 7, 11, 18, 0).toInstant(zone).toEpochMilliseconds()
        val result = calculateDayProgress(end, 8 * 60, 18 * 60, zone)

        assertEquals(0f, result.remainingRatio)
        assertEquals(1f, result.elapsedRatio)
        assertTrue(result.isFinished)
    }

    @Test
    fun invalidRangeIsClampedToAValidHalfHour() {
        val now = LocalDateTime(2026, 7, 11, 23, 30).toInstant(zone).toEpochMilliseconds()
        val result = calculateDayProgress(now, 2_000, -1, zone)

        assertEquals(23, result.startHour)
        assertEquals(29, result.startMinute)
        assertEquals(23, result.endHour)
        assertEquals(59, result.endMinute)
        assertTrue(result.remainingRatio in .96f..1f)
    }

    @Test
    fun progressUsesActualDurationAcrossSpringClockChange() {
        val now = LocalDateTime(2026, 3, 29, 3, 0).toInstant(zone).toEpochMilliseconds()
        val result = calculateDayProgress(now, 0, 4 * 60, zone)

        assertEquals(1 * 3_600_000L, result.remainingMillis)
        assertTrue(result.remainingRatio in .32f..34f)
    }

    @Test
    fun presentMarkerAdvancesClockwiseFromTheTop() {
        assertEquals(-90f, currentMomentAngleDegrees(1f))
        assertEquals(90f, currentMomentAngleDegrees(.5f))
        assertEquals(270f, currentMomentAngleDegrees(0f))
    }

    @Test
    fun presentMarkerAngleClampsInvalidRatios() {
        assertEquals(-90f, currentMomentAngleDegrees(2f))
        assertEquals(270f, currentMomentAngleDegrees(-1f))
    }
}
