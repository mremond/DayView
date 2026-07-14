package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetRefreshScheduleTest {
    private val zone = TimeZone.of("Europe/Paris")

    private fun at(hour: Int, minute: Int, day: Int = 11): Long = LocalDateTime(2026, 7, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    @Test
    fun beforeStartWakesAtTheDayStart() {
        val next = nextWidgetRefreshMillis(at(7, 30), 8 * 60, 18 * 60, zone)

        assertEquals(at(8, 0), next)
    }

    @Test
    fun duringTheDayWakesAtTheTopOfTheNextHour() {
        val next = nextWidgetRefreshMillis(at(9, 15), 8 * 60, 18 * 60, zone)

        assertEquals(at(10, 0), next)
    }

    @Test
    fun inTheFinalHourWakesAtTheDayEndRatherThanPastIt() {
        val next = nextWidgetRefreshMillis(at(17, 40), 8 * 60, 18 * 60, zone)

        assertEquals(at(18, 0), next)
    }

    @Test
    fun afterTheDayEndsWakesAtTomorrowsStart() {
        val next = nextWidgetRefreshMillis(at(20, 0), 8 * 60, 18 * 60, zone)

        assertEquals(at(8, 0, day = 12), next)
    }

    @Test
    fun exactlyOnTheHourWakesAtTheFollowingHour() {
        val next = nextWidgetRefreshMillis(at(9, 0), 8 * 60, 18 * 60, zone)

        assertEquals(at(10, 0), next)
    }

    @Test
    fun neverReturnsAnInstantInThePast() {
        val now = at(12, 34)
        val next = nextWidgetRefreshMillis(now, 8 * 60, 18 * 60, zone)

        assertTrue(next > now)
    }
}
