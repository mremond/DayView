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
    fun arcContainsAngleHandlesSweepAndWraparound() {
        // Arc bas-gauche 200°..240°.
        assertEquals(true, arcContainsAngle(200f, 40f, 220f))
        // atan2 renvoie 220° comme -140° : le wraparound doit quand même l'inclure.
        assertEquals(true, arcContainsAngle(200f, 40f, -140f))
        assertEquals(false, arcContainsAngle(200f, 40f, 100f))
        // Arc au sommet, à cheval sur la frontière -90°.
        assertEquals(true, arcContainsAngle(-110f, 40f, -90f))
        assertEquals(false, arcContainsAngle(-110f, 40f, 0f))
    }

    @Test
    fun angularDistanceToArcIsZeroInsideAndGrowsOutside() {
        // Arc bas-gauche 200°..240°.
        assertEquals(0f, angularDistanceToArc(200f, 40f, 220f)) // à l'intérieur
        assertEquals(5f, angularDistanceToArc(200f, 40f, 195f)) // 5° avant le début
        assertEquals(10f, angularDistanceToArc(200f, 40f, 250f)) // 10° après la fin (240°)
        // Wraparound : -140° ≡ 220°, donc à l'intérieur.
        assertEquals(0f, angularDistanceToArc(200f, 40f, -140f))
        // Arc au sommet à cheval sur -90° : -90° est dedans, 0° est à 70° de la fin.
        assertEquals(0f, angularDistanceToArc(-100f, 20f, -90f))
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
    fun netTimeCountsCrossCalendarOverlapOnce() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone)
        val (start, end) = dayWindow(noon, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(noon, 8 * 60, 18 * 60, zone)
        // Two calendars, same 14:00-15:00 slot -> counted once, not twice.
        val busy = listOf(
            BusyInterval(noon + 2.hours, noon + 3.hours, listOf("Pro"), calendarId = "work"),
            BusyInterval(noon + 2.hours, noon + 3.hours, listOf("Perso"), calendarId = "home"),
        )
        val net = calculateNetTime(progress, noon, start, end, busy)
        assertEquals(1.hours, net.busyRemaining)
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
    fun mergeByCalendarKeepsCalendarsSeparate() {
        val merged = mergeBusyIntervalsByCalendar(
            listOf(
                BusyInterval(t(100), t(300), listOf("A"), calendarId = "work"),
                BusyInterval(t(200), t(400), listOf("B"), calendarId = "home"), // overlaps in time, other calendar
                BusyInterval(t(300), t(500), listOf("C"), calendarId = "work"), // touches A -> merges
            ),
        )
        // work: 100..500 merged; home: 200..400 separate.
        assertEquals(2, merged.size)
        val work = merged.first { it.calendarId == "work" }
        val home = merged.first { it.calendarId == "home" }
        assertEquals(t(100), work.start)
        assertEquals(t(500), work.end)
        assertEquals(t(200), home.start)
        assertEquals(t(400), home.end)
    }

    @Test
    fun busyCalendarsAssignStableColorIndexByFirstSeen() {
        val cals = busyCalendars(
            listOf(
                BusyInterval(t(300), t(400), calendarId = "home"),
                BusyInterval(t(100), t(200), calendarId = "work"), // earliest start -> index 0
                BusyInterval(t(500), t(700), calendarId = "work"),
            ),
        )
        val work = cals.first { it.calendarId == "work" }
        val home = cals.first { it.calendarId == "home" }
        assertEquals(0, work.colorIndex) // earliest start overall
        assertEquals(1, home.colorIndex)
        assertEquals(300L, work.total.inWholeMilliseconds) // 100 + 200
        assertEquals(100L, home.total.inWholeMilliseconds)
    }

    @Test
    fun busyBlockArcsProjectWithColorAndName() {
        // Fenêtre 0..1000. work 250..500 -> quart..moitié, couleur 0, nom mappé.
        val arcs = busyBlockArcs(
            t(0),
            t(1000),
            listOf(BusyInterval(t(250), t(500), listOf("Atelier"), calendarId = "work")),
            mapOf("work" to "Travail"),
        )
        assertEquals(1, arcs.size)
        assertEquals(-90f + 0.25f * 360f, arcs[0].startAngleDegrees)
        assertEquals(0.25f * 360f, arcs[0].sweepDegrees)
        assertEquals(0, arcs[0].colorIndex)
        assertEquals(listOf("Atelier"), arcs[0].titles)
        assertEquals("Travail", arcs[0].calendarName)
    }

    @Test
    fun busyBlockArcsFallBackToBlankNameForUnknownCalendar() {
        val arcs = busyBlockArcs(
            t(0),
            t(1000),
            listOf(BusyInterval(t(100), t(200), calendarId = "ghost")),
            emptyMap(),
        )
        assertEquals("", arcs[0].calendarName)
    }

    @Test
    fun busyBlockArcsClipToWindow() {
        // Créneau -200..200 sur fenêtre 0..1000 : rogné à 0..200 -> début au sommet.
        val arcs = busyBlockArcs(
            t(0),
            t(1000),
            listOf(BusyInterval(t(-200), t(200), listOf("Y"), calendarId = "work")),
            mapOf("work" to "Travail"),
        )
        assertEquals(1, arcs.size)
        assertEquals(-90f, arcs[0].startAngleDegrees)
        assertEquals(0.2f * 360f, arcs[0].sweepDegrees)
    }

    @Test
    fun busyBlockArcsHandleDegenerateWindow() {
        assertEquals(
            emptyList(),
            busyBlockArcs(
                t(500),
                t(500),
                listOf(BusyInterval(t(400), t(600), calendarId = "work")),
                mapOf("work" to "Travail"),
            ),
        )
    }
}
