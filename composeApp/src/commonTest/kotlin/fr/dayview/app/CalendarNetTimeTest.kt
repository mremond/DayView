package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarNetTimeTest {
    private fun interval(start: Long, end: Long, vararg titles: String) =
        BusyInterval(start, end, titles.toList())

    @Test
    fun mergeCombinesOverlappingAndTouchingIntervals() {
        val merged = mergeBusyIntervals(
            listOf(
                interval(300, 400, "B"),
                interval(100, 200, "A"),
                interval(200, 250, "C"), // touche A
            ),
        )
        assertEquals(2, merged.size)
        assertEquals(BusyInterval(100, 250, listOf("A", "C")), merged[0])
        assertEquals(BusyInterval(300, 400, listOf("B")), merged[1])
    }

    @Test
    fun mergeDropsEmptyOrInvertedIntervals() {
        val merged = mergeBusyIntervals(listOf(interval(500, 500), interval(700, 600)))
        assertEquals(emptyList(), merged)
    }

    @Test
    fun dayWindowReturnsAbsoluteBounds() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0)
            .toInstant(zone).toEpochMilliseconds()
        val (start, end) = dayWindowMillis(noon, 8 * 60, 18 * 60, zone)
        assertEquals(4L * 3_600_000, noon - start) // 08:00 -> 4 h avant midi
        assertEquals(6L * 3_600_000, end - noon) // 18:00 -> 6 h après midi
    }

    @Test
    fun netTimeSubtractsBusyStillAhead() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0)
            .toInstant(zone).toEpochMilliseconds()
        val (start, end) = dayWindowMillis(noon, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(noon, 8 * 60, 18 * 60, zone)
        // Réunion 09:00-10:00 (passée) + 14:00-15:30 (à venir)
        val busy = listOf(
            interval(start + 1L * 3_600_000, start + 2L * 3_600_000, "Standup"),
            interval(noon + 2L * 3_600_000, noon + 3L * 3_600_000 + 1_800_000, "Atelier"),
        )
        val net = calculateNetTime(progress, noon, start, end, busy)
        assertEquals(3_600_000 + 1_800_000L, net.busyRemainingMillis) // 1 h 30 à venir
        assertEquals(progress.remainingMillis - net.busyRemainingMillis, net.netRemainingMillis)
        assertEquals((end - start) - (3_600_000 + 3_600_000 + 1_800_000L), net.netDayMillis)
    }

    @Test
    fun busyArcsProjectAtElapsedFraction() {
        // Fenêtre 0..1000. Plage 250..500 -> quart..moitié.
        val arcs = busyArcs(0, 1000, listOf(interval(250, 500, "X")))
        assertEquals(1, arcs.size)
        assertEquals(-90f + 0.25f * 360f, arcs[0].startAngleDegrees)
        assertEquals(0.25f * 360f, arcs[0].sweepDegrees)
        assertEquals(listOf("X"), arcs[0].titles)
    }

    @Test
    fun busyArcsClipToWindow() {
        val arcs = busyArcs(0, 1000, listOf(interval(-200, 200, "Y")))
        assertEquals(-90f, arcs[0].startAngleDegrees)
        assertEquals(0.2f * 360f, arcs[0].sweepDegrees)
    }

    @Test
    fun noopCalendarSourceIsInertAndSafe() {
        assertEquals(false, NoopCalendarSource.isSupported())
        assertEquals(false, NoopCalendarSource.hasPermission())
        assertEquals(emptyList(), NoopCalendarSource.availableCalendars())
        assertEquals(emptyList(), NoopCalendarSource.busyIntervals(0, 1000, emptySet()))
    }
}
