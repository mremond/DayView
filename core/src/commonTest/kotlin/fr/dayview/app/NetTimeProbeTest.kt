package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NetTimeProbeTest {
    private class FakeCalendarSource(
        var permission: Boolean = true,
        var calendars: List<CalendarInfo> = emptyList(),
        var busy: List<BusyInterval> = emptyList(),
        var throwOnBusy: Boolean = false,
    ) : CalendarSource {
        var permissionChecks = 0
        var busyReads = 0

        override fun isSupported() = true

        override fun hasPermission(): Boolean {
            permissionChecks++
            return permission
        }

        override fun requestPermission() = Unit

        override fun availableCalendars(): List<CalendarInfo> = calendars

        override fun busyIntervals(
            windowStart: Instant,
            windowEnd: Instant,
            includedCalendarIds: Set<String>,
        ): List<BusyInterval> {
            busyReads++
            if (throwOnBusy) error("calendar read failed")
            return busy
        }
    }

    private val windowStart = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val windowEnd = Instant.fromEpochMilliseconds(1_700_030_000_000L)

    @Test
    fun disabledProbeSkipsTheSourceEntirely() {
        val source = FakeCalendarSource()
        val result = probeNetTime(source, enabled = false, includedCalendarIds = emptySet(), windowStart, windowEnd)
        assertEquals(NetTimeProbeResult(permission = false), result)
        assertEquals(0, source.permissionChecks)
        assertEquals(0, source.busyReads)
    }

    @Test
    fun enabledWithoutPermissionReportsNoPermission() {
        val source = FakeCalendarSource(permission = false)
        val result = probeNetTime(source, enabled = true, includedCalendarIds = emptySet(), windowStart, windowEnd)
        assertEquals(NetTimeProbeResult(permission = false), result)
        assertEquals(0, source.busyReads)
    }

    @Test
    fun happyPathPassesIntervalsAndCalendarsThrough() {
        val busy = listOf(BusyInterval(windowStart, windowEnd, titles = listOf("Standup"), calendarId = "c1"))
        val calendars = listOf(CalendarInfo("c1", "Work"))
        val source = FakeCalendarSource(busy = busy, calendars = calendars)
        val result = probeNetTime(source, enabled = true, includedCalendarIds = emptySet(), windowStart, windowEnd)
        assertEquals(NetTimeProbeResult(permission = true, busy = busy, calendars = calendars), result)
    }

    @Test
    fun throwingBusyReadSurfacesAsReadErrorWithCalendarsIntact() {
        val calendars = listOf(CalendarInfo("c1", "Work"))
        val source = FakeCalendarSource(calendars = calendars, throwOnBusy = true)
        val result = probeNetTime(source, enabled = true, includedCalendarIds = emptySet(), windowStart, windowEnd)
        assertEquals(
            NetTimeProbeResult(permission = true, busy = emptyList(), calendars = calendars, readError = true),
            result,
        )
    }
}
