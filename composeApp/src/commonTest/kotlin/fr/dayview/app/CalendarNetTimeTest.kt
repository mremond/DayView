package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CalendarNetTimeTest {
    private fun t(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)
    private fun interval(start: Long, end: Long, vararg titles: String) = BusyInterval(t(start), t(end), titles.toList())
    private fun busyAt(start: Instant, end: Instant, vararg titles: String) = BusyInterval(start, end, titles.toList())

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
        assertEquals(interval(100, 250, "A", "C"), merged[0])
        assertEquals(interval(300, 400, "B"), merged[1])
    }

    @Test
    fun mergeDropsEmptyOrInvertedIntervals() {
        val merged = mergeBusyIntervals(listOf(interval(500, 500), interval(700, 600)))
        assertEquals(emptyList(), merged)
    }

    @Test
    fun mergeAbsorbsFullyContainedInterval() {
        val merged = mergeBusyIntervals(
            listOf(interval(100, 500, "Bloc"), interval(200, 300, "Interne")),
        )
        assertEquals(1, merged.size)
        assertEquals(t(100), merged[0].start)
        assertEquals(t(500), merged[0].end) // la borne du conteneur n'est pas rognée
        assertEquals(listOf("Bloc", "Interne"), merged[0].titles)
    }

    @Test
    fun dayWindowReturnsAbsoluteBounds() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone)
        val (start, end) = dayWindow(noon, 8 * 60, 18 * 60, zone)
        assertEquals(4.hours, noon - start) // 08:00 -> 4 h avant midi
        assertEquals(6.hours, end - noon) // 18:00 -> 6 h après midi
    }

    @Test
    fun netTimeSubtractsBusyStillAhead() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone)
        val (start, end) = dayWindow(noon, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(noon, 8 * 60, 18 * 60, zone)
        // Réunion 09:00-10:00 (passée) + 14:00-15:30 (à venir)
        val busy = listOf(
            busyAt(start + 1.hours, start + 2.hours, "Standup"),
            busyAt(noon + 2.hours, noon + 3.hours + 30.minutes, "Atelier"),
        )
        val net = calculateNetTime(progress, noon, start, end, busy)
        assertEquals(1.hours + 30.minutes, net.busyRemaining) // 1 h 30 à venir
        assertEquals(progress.remaining - net.busyRemaining, net.netRemaining)
        assertEquals((end - start) - (2.hours + 30.minutes), net.netDay)
    }

    @Test
    fun netTimeIgnoresBusyEntirelyInPast() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone)
        val (start, end) = dayWindow(noon, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(noon, 8 * 60, 18 * 60, zone)
        val busy = listOf(busyAt(start + 1.hours, start + 2.hours, "Matin")) // 09:00-10:00
        val net = calculateNetTime(progress, noon, start, end, busy)
        assertEquals(Duration.ZERO, net.busyRemaining)
        assertEquals(progress.remaining, net.netRemaining)
        assertEquals((end - start) - 1.hours, net.netDay)
    }

    @Test
    fun netTimeCountsOnlyFutureHalfOfStraddlingBusy() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone)
        val (start, end) = dayWindow(noon, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(noon, 8 * 60, 18 * 60, zone)
        // Réunion 11:30-12:30, à cheval sur midi -> seule la moitié future compte comme restante.
        val busy = listOf(busyAt(noon - 30.minutes, noon + 30.minutes, "Point"))
        val net = calculateNetTime(progress, noon, start, end, busy)
        assertEquals(30.minutes, net.busyRemaining)
        assertEquals((end - start) - 1.hours, net.netDay) // l'heure entière est déduite du jour
    }

    @Test
    fun netTimeClipsBusyExtendingBeyondWindow() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone)
        val (start, end) = dayWindow(noon, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(noon, 8 * 60, 18 * 60, zone)
        // 17:00-19:00 : seule l'heure interne (17:00-18:00) compte.
        val busy = listOf(busyAt(end - 1.hours, end + 1.hours, "Soirée"))
        val net = calculateNetTime(progress, noon, start, end, busy)
        assertEquals(1.hours, net.busyRemaining)
        assertEquals((end - start) - 1.hours, net.netDay)
    }

    @Test
    fun netTimeWithoutBusyEqualsGross() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone)
        val (start, end) = dayWindow(noon, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(noon, 8 * 60, 18 * 60, zone)
        val net = calculateNetTime(progress, noon, start, end, emptyList())
        assertEquals(Duration.ZERO, net.busyRemaining)
        assertEquals(progress.remaining, net.netRemaining)
        assertEquals(end - start, net.netDay)
    }

    @Test
    fun busyArcsProjectAtElapsedFraction() {
        // Fenêtre 0..1000. Plage 250..500 -> quart..moitié.
        val arcs = busyArcs(t(0), t(1000), listOf(interval(250, 500, "X")))
        assertEquals(1, arcs.size)
        assertEquals(-90f + 0.25f * 360f, arcs[0].startAngleDegrees)
        assertEquals(0.25f * 360f, arcs[0].sweepDegrees)
        assertEquals(listOf("X"), arcs[0].titles)
    }

    @Test
    fun busyArcsClipToWindow() {
        val arcs = busyArcs(t(0), t(1000), listOf(interval(-200, 200, "Y")))
        assertEquals(-90f, arcs[0].startAngleDegrees)
        assertEquals(0.2f * 360f, arcs[0].sweepDegrees)
    }

    @Test
    fun busyArcsHandleDegenerateWindow() {
        assertEquals(emptyList(), busyArcs(t(500), t(500), listOf(interval(400, 600, "Z"))))
    }

    @Test
    fun arcContainsAngleHandlesSweepAndWraparound() {
        // Arc bas-gauche 200°..240°.
        val arc = BusyArc(startAngleDegrees = 200f, sweepDegrees = 40f, titles = listOf("R"))
        assertEquals(true, arcContainsAngle(arc, 220f))
        // atan2 renvoie 220° comme -140° : le wraparound doit quand même l'inclure.
        assertEquals(true, arcContainsAngle(arc, -140f))
        assertEquals(false, arcContainsAngle(arc, 100f))
        // Arc au sommet, à cheval sur la frontière -90°.
        val topArc = BusyArc(startAngleDegrees = -110f, sweepDegrees = 40f, titles = emptyList())
        assertEquals(true, arcContainsAngle(topArc, -90f))
        assertEquals(false, arcContainsAngle(topArc, 0f))
    }

    @Test
    fun durationFormatting() {
        assertEquals("2 h 45", formatDurationHm(2.hours + 45.minutes))
        assertEquals("27 min", formatDurationHm(27.minutes))
        assertEquals("0 min", formatDurationHm((-5).milliseconds))
    }

    @Test
    fun angleToInstantAndClockRoundTrip() {
        val zone = TimeZone.of("Europe/Paris")
        val (start, end) = dayWindow(
            LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone),
            8 * 60,
            18 * 60,
            zone,
        )
        // Milieu de la fenêtre 08:00–18:00 -> 13:00, à l'angle 90° (-90 + 0.5*360).
        assertEquals("13:00", formatClockHm(angleToInstant(90f, start, end), zone))
        assertEquals("08:00", formatClockHm(angleToInstant(-90f, start, end), zone))
    }

    @Test
    fun noopCalendarSourceIsInertAndSafe() {
        assertEquals(false, NoopCalendarSource.isSupported())
        assertEquals(false, NoopCalendarSource.hasPermission())
        assertEquals(emptyList(), NoopCalendarSource.availableCalendars())
        assertEquals(emptyList(), NoopCalendarSource.busyIntervals(t(0), t(1000), emptySet()))
    }

    @Test
    fun nextIncludedCalendarsHandlesAllSemantics() {
        val all = listOf("a", "b", "c")
        // Depuis « tous » (vide), décocher b -> {a, c}.
        assertEquals(setOf("a", "c"), nextIncludedCalendars(all, emptySet(), "b", include = false))
        // Re-cocher b pour revenir à tous -> renormalisé en vide.
        assertEquals(emptySet(), nextIncludedCalendars(all, setOf("a", "c"), "b", include = true))
        // Décocher depuis un sous-ensemble.
        assertEquals(setOf("a"), nextIncludedCalendars(all, setOf("a", "c"), "c", include = false))
    }

    @Test
    fun formatWallClock_24h_pads_both_fields() {
        assertEquals("00:00", formatWallClock(0, 0, use24Hour = true))
        assertEquals("07:05", formatWallClock(7, 5, use24Hour = true))
        assertEquals("13:30", formatWallClock(13, 30, use24Hour = true))
    }

    @Test
    fun formatWallClock_12h_uses_am_pm_without_hour_padding() {
        assertEquals("12:00 AM", formatWallClock(0, 0, use24Hour = false))
        assertEquals("7:05 AM", formatWallClock(7, 5, use24Hour = false))
        assertEquals("12:00 PM", formatWallClock(12, 0, use24Hour = false))
        assertEquals("1:05 PM", formatWallClock(13, 5, use24Hour = false))
        assertEquals("11:59 PM", formatWallClock(23, 59, use24Hour = false))
    }
}
