package fr.dayview.app

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DayMeetingsTest {
    private class FakeCalendarSource(
        var permission: Boolean = true,
        var busy: List<BusyInterval> = emptyList(),
        var throwOnPermission: Boolean = false,
        var throwOnBusy: Boolean = false,
    ) : CalendarSource {
        var askedWindow: Pair<Instant, Instant>? = null

        override fun isSupported() = true

        override fun hasPermission(): Boolean {
            if (throwOnPermission) error("permission check failed")
            return permission
        }

        override fun requestPermission() = Unit

        override fun availableCalendars(): List<CalendarInfo> = emptyList()

        override fun busyIntervals(
            windowStart: Instant,
            windowEnd: Instant,
            includedCalendarIds: Set<String>,
        ): List<BusyInterval> {
            askedWindow = windowStart to windowEnd
            if (throwOnBusy) error("calendar read failed")
            return busy
        }
    }

    private val paris = TimeZone.of("Europe/Paris")

    @Test
    fun queriesWholeLocalDayRegardlessOfNetTime() {
        val source = FakeCalendarSource()
        probeDayMeetings(source, emptySet(), Instant.parse("2026-11-18T09:00:00Z"), paris)
        assertEquals(
            Instant.parse("2026-11-17T23:00:00Z") to Instant.parse("2026-11-18T23:00:00Z"),
            source.askedWindow,
        )
    }

    @Test
    fun excludesFocusTitledEventsAndMerges() {
        val day = Instant.parse("2026-11-18T00:00:00Z")
        val source = FakeCalendarSource(
            busy = listOf(
                BusyInterval(day + 9.hours, day + 10.hours, listOf("Standup")),
                BusyInterval(day + 9.hours + 30.minutes, day + 10.hours + 30.minutes, listOf("Review")),
                BusyInterval(day + 14.hours, day + 15.hours, listOf("Focus writing")),
            ),
        )
        val result = probeDayMeetings(source, emptySet(), day + 10.hours, paris)
        assertEquals(1, result.size)
        assertEquals(day + 9.hours, result[0].start)
        assertEquals(day + 10.hours + 30.minutes, result[0].end)
    }

    @Test
    fun emptyWithoutPermission() {
        val source = FakeCalendarSource(permission = false, busy = listOf(BusyInterval(Instant.fromEpochMilliseconds(0), Instant.fromEpochMilliseconds(1))))
        assertTrue(probeDayMeetings(source, emptySet(), Instant.parse("2026-11-18T09:00:00Z"), paris).isEmpty())
    }

    @Test
    fun emptyWhenPermissionCheckThrows() {
        val source = FakeCalendarSource(throwOnPermission = true)
        assertTrue(probeDayMeetings(source, emptySet(), Instant.parse("2026-11-18T09:00:00Z"), paris).isEmpty())
    }

    @Test
    fun emptyWhenReadThrows() {
        val source = FakeCalendarSource(throwOnBusy = true)
        assertTrue(probeDayMeetings(source, emptySet(), Instant.parse("2026-11-18T09:00:00Z"), paris).isEmpty())
    }

    @Test
    fun localDayBoundsOnDstSpringDay() {
        val (start, end) = localDayBounds(Instant.parse("2026-03-29T10:00:00Z"), paris)
        assertEquals(23.hours, end - start)
    }
}
