package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarNetTimeTest {
    private fun interval(start: Long, end: Long, vararg titles: String) = BusyInterval(start, end, titles.toList())

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
    fun mergeAbsorbsFullyContainedInterval() {
        val merged = mergeBusyIntervals(
            listOf(interval(100, 500, "Bloc"), interval(200, 300, "Interne")),
        )
        assertEquals(1, merged.size)
        assertEquals(100, merged[0].startMillis)
        assertEquals(500, merged[0].endMillis) // la borne du conteneur n'est pas rognée
        assertEquals(listOf("Bloc", "Interne"), merged[0].titles)
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
    fun netTimeIgnoresBusyEntirelyInPast() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone).toEpochMilliseconds()
        val (start, end) = dayWindowMillis(noon, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(noon, 8 * 60, 18 * 60, zone)
        val busy = listOf(interval(start + 3_600_000, start + 2 * 3_600_000, "Matin")) // 09:00-10:00
        val net = calculateNetTime(progress, noon, start, end, busy)
        assertEquals(0, net.busyRemainingMillis)
        assertEquals(progress.remainingMillis, net.netRemainingMillis)
        assertEquals((end - start) - 3_600_000, net.netDayMillis)
    }

    @Test
    fun netTimeCountsOnlyFutureHalfOfStraddlingBusy() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone).toEpochMilliseconds()
        val (start, end) = dayWindowMillis(noon, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(noon, 8 * 60, 18 * 60, zone)
        // Réunion 11:30-12:30, à cheval sur midi -> seule la moitié future compte comme restante.
        val busy = listOf(interval(noon - 1_800_000, noon + 1_800_000, "Point"))
        val net = calculateNetTime(progress, noon, start, end, busy)
        assertEquals(1_800_000, net.busyRemainingMillis)
        assertEquals((end - start) - 3_600_000, net.netDayMillis) // l'heure entière est déduite du jour
    }

    @Test
    fun netTimeClipsBusyExtendingBeyondWindow() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone).toEpochMilliseconds()
        val (start, end) = dayWindowMillis(noon, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(noon, 8 * 60, 18 * 60, zone)
        // 17:00-19:00 : seule l'heure interne (17:00-18:00) compte.
        val busy = listOf(interval(end - 3_600_000, end + 3_600_000, "Soirée"))
        val net = calculateNetTime(progress, noon, start, end, busy)
        assertEquals(3_600_000, net.busyRemainingMillis)
        assertEquals((end - start) - 3_600_000, net.netDayMillis)
    }

    @Test
    fun netTimeWithoutBusyEqualsGross() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone).toEpochMilliseconds()
        val (start, end) = dayWindowMillis(noon, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(noon, 8 * 60, 18 * 60, zone)
        val net = calculateNetTime(progress, noon, start, end, emptyList())
        assertEquals(0, net.busyRemainingMillis)
        assertEquals(progress.remainingMillis, net.netRemainingMillis)
        assertEquals(end - start, net.netDayMillis)
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
    fun busyArcsHandleDegenerateWindow() {
        assertEquals(emptyList(), busyArcs(500, 500, listOf(interval(400, 600, "Z"))))
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
        assertEquals("2 h 45", formatDurationHm(2 * 3_600_000 + 45 * 60_000L))
        assertEquals("27 min", formatDurationHm(27 * 60_000L))
        assertEquals("0 min", formatDurationHm(-5))
    }

    @Test
    fun angleToMillisAndClockRoundTrip() {
        val zone = TimeZone.of("Europe/Paris")
        val (start, end) = dayWindowMillis(
            LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone).toEpochMilliseconds(),
            8 * 60,
            18 * 60,
            zone,
        )
        // Milieu de la fenêtre 08:00–18:00 -> 13:00, à l'angle 90° (-90 + 0.5*360).
        assertEquals("13:00", formatClockHm(angleToMillis(90f, start, end), zone))
        assertEquals("08:00", formatClockHm(angleToMillis(-90f, start, end), zone))
    }

    @Test
    fun noopCalendarSourceIsInertAndSafe() {
        assertEquals(false, NoopCalendarSource.isSupported())
        assertEquals(false, NoopCalendarSource.hasPermission())
        assertEquals(emptyList(), NoopCalendarSource.availableCalendars())
        assertEquals(emptyList(), NoopCalendarSource.busyIntervals(0, 1000, emptySet()))
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
}
