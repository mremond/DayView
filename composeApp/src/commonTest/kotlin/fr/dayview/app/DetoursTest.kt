package fr.dayview.app

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun t(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

class DetoursTest {
    @Test
    fun sanitizeStripsNewlinesTrimsAndBounds() {
        assertEquals("appel urgent", sanitizeDetourMotif("  appel\nurgent \r"))
        assertEquals(60, sanitizeDetourMotif("x".repeat(80)).length)
    }

    @Test
    fun detoursEncodeDecodeRoundTripsMotifsWithCommas() {
        val episodes = listOf(
            DetourEpisode(t(1_000L), t(2_000L), "Appel, urgent"),
            DetourEpisode(t(3_000L), t(4_000L), "Slack"),
        )
        assertEquals(episodes, decodeDetours(encodeDetours(episodes)))
    }

    @Test
    fun decodeSkipsMalformedBlankAndEmptyMotifLines() {
        val encoded = "not a line\n1000,2000,ok\n5000,4000,inverted\n1000,2000,\n\n"
        assertEquals(listOf(DetourEpisode(t(1_000L), t(2_000L), "ok")), decodeDetours(encoded))
    }

    @Test
    fun recentMotifsEncodeDecodeRoundTrips() {
        val motifs = listOf("Appel, urgent", "Slack")
        assertEquals(motifs, decodeRecentDetourMotifs(encodeRecentDetourMotifs(motifs)))
        assertEquals(emptyList(), decodeRecentDetourMotifs(""))
    }

    @Test
    fun pushRecentDedupesCaseInsensitivelyAndCaps() {
        val once = pushRecentDetourMotif(listOf("Slack", "Appels"), "slack")
        assertEquals(listOf("slack", "Appels"), once)
        var recents = emptyList<String>()
        repeat(15) { recents = pushRecentDetourMotif(recents, "motif $it") }
        assertEquals(MAX_RECENT_DETOUR_MOTIFS, recents.size)
        assertEquals("motif 14", recents.first())
    }

    @Test
    fun pushRecentIgnoresBlankMotifs() {
        assertEquals(listOf("Slack"), pushRecentDetourMotif(listOf("Slack"), "  \n"))
    }

    @Test
    fun removeRecentDropsMotifCaseInsensitively() {
        assertEquals(listOf("Slack"), removeRecentDetourMotif(listOf("Slack", "dsfdsf"), "DSFDSF"))
        assertEquals(emptyList(), removeRecentDetourMotif(listOf("Slack"), "  slack  "))
    }

    @Test
    fun removeRecentLeavesListUntouchedWhenAbsent() {
        assertEquals(listOf("Slack", "Appels"), removeRecentDetourMotif(listOf("Slack", "Appels"), "absent"))
        assertEquals(listOf("Slack"), removeRecentDetourMotif(listOf("Slack"), "  "))
    }

    @Test
    fun dayKeyMatchesLocalCalendarDay() {
        val zone = TimeZone.of("Europe/Paris")
        // 2026-07-12 08:00 and 17:00 Paris are the same day; 2026-07-13 01:00 is the next.
        val morning = Instant.parse("2026-07-12T06:00:00Z")
        val evening = Instant.parse("2026-07-12T15:00:00Z")
        val nextDay = Instant.parse("2026-07-12T23:30:00Z")
        assertEquals(dayKeyOf(morning, zone), dayKeyOf(evening, zone))
        assertTrue(dayKeyOf(nextDay, zone) > dayKeyOf(evening, zone))
    }

    @Test
    fun sourcesAggregateByNormalizedMotifHeaviestFirst() {
        val episodes = listOf(
            DetourEpisode(t(0L), t(1_200_000L), "Slack"), // 20 min
            DetourEpisode(t(2_000_000L), t(3_200_000L), "Appels"), // 20 min
            DetourEpisode(t(4_000_000L), t(4_900_000L), "slack"), // 15 min
        )
        val sources = detourSources(episodes)
        assertEquals(listOf("Slack", "Appels"), sources.map { it.label })
        assertEquals(listOf(0, 1), sources.map { it.colorIndex })
        assertEquals(35, sources.first().total.inWholeMinutes)
    }

    @Test
    fun sourceColorFollowsEarliestEpisodeAfterRetroactiveInsert() {
        val base = listOf(DetourEpisode(t(5_000_000L), t(6_000_000L), "Slack"))
        val withEarlier = base + DetourEpisode(t(0L), t(1_000_000L), "Appels")
        // "Appels" now starts the day, so it takes color 0 even though captured later.
        val sources = detourSources(withEarlier)
        assertEquals(0, sources.first { it.label == "Appels" }.colorIndex)
        assertEquals(1, sources.first { it.label == "Slack" }.colorIndex)
    }

    @Test
    fun detoursTotalSumsDurations() {
        val episodes = listOf(
            DetourEpisode(t(0L), t(600_000L), "a"), // 10 min
            DetourEpisode(t(0L), t(1_200_000L), "b"), // 20 min
        )
        assertEquals(30, detoursTotal(episodes).inWholeMinutes)
    }

    @Test
    fun bodiesSitAtTheEpisodeMidpointAngle() {
        val start = t(0L)
        val end = t(36_000_000L) // 10 h window
        // 0 → 1 h episode: midpoint 30 min = 5 % of the window → -90° + 18°.
        val body = detourBodies(start, end, listOf(DetourEpisode(t(0L), t(3_600_000L), "Slack"))).single()
        assertEquals(-72f, body.angleDegrees, absoluteTolerance = .01f)
    }

    @Test
    fun bodySizeFractionScalesBySqrtUpToThreeHours() {
        val start = t(0L)
        val end = t(36_000_000L) // 10 h window
        fun sizeOf(minutes: Long): Float = detourBodies(
            start,
            end,
            listOf(DetourEpisode(t(7_200_000L), t(7_200_000L + minutes * 60_000L), "x")),
        ).single().sizeFraction
        assertEquals(0f, sizeOf(5)) // floor: 5 min → 0
        assertEquals(1f, sizeOf(180)) // ceiling: 3 h → 1
        assertEquals(1f, sizeOf(240)) // clamped past the 3 h cap
        // Square-root growth: steep early, gentle late. A 60 min body no longer saturates.
        assertEquals(.5606f, sizeOf(60), absoluteTolerance = .001f) // sqrt((60-5)/175)
        assertEquals(.6969f, sizeOf(90), absoluteTolerance = .001f) // sqrt((90-5)/175)
        assertTrue(sizeOf(30) < sizeOf(60))
        assertTrue(sizeOf(60) < sizeOf(90))
        assertTrue(sizeOf(90) < sizeOf(120))
    }

    @Test
    fun bodiesOutsideTheWindowAreDropped() {
        val start = t(10_000_000L)
        val end = t(20_000_000L)
        val before = DetourEpisode(t(0L), t(1_000_000L), "early")
        assertEquals(emptyList(), detourBodies(start, end, listOf(before)))
    }

    @Test
    fun hitTestFindsTheBodyUnderThePointer() {
        val body = DetourBody(
            angleDegrees = -90f,
            sizeFraction = 1f,
            colorIndex = 0,
            motif = "Slack",
            start = t(0L),
            end = t(1L),
        )
        // Top of a 400×400 dial, on the ring radius.
        assertEquals(body, hitTestDetourBody(200f, 25f, 400, 400, listOf(body)))
        // Center of the dial: not on the ring.
        assertEquals(null, hitTestDetourBody(200f, 200f, 400, 400, listOf(body)))
        // Bottom of the dial: on the ring but 180° away.
        assertEquals(null, hitTestDetourBody(200f, 378f, 400, 400, listOf(body)))
    }

    @Test
    fun detourEpisodeAtBuildsOnTheSameLocalDay() {
        val zone = TimeZone.of("Europe/Paris")
        val reference = Instant.parse("2026-07-12T10:00:00Z")
        val episode = detourEpisodeAt(reference, 9 * 60 + 30, 45, " appel ", zone)
        assertEquals("appel", episode.motif)
        assertEquals(45, episode.duration.inWholeMinutes)
        assertEquals("09:30", formatClockHm(episode.start, zone))
    }

    @Test
    fun defaultStartMinutesEndsNow() {
        val zone = TimeZone.of("Europe/Paris")
        val now = Instant.parse("2026-07-12T10:00:00Z") // 12:00 local
        assertEquals(12 * 60 - 15, detourDefaultStartMinutes(now, 15, zone))
        assertEquals(12 * 60 - 60, detourDefaultStartMinutes(now, 60, zone))
    }

    @Test
    fun defaultStartMinutesClampsToMidnight() {
        val zone = TimeZone.of("Europe/Paris")
        val now = Instant.parse("2026-07-11T22:20:00Z") // 00:20 local on 07-12
        assertEquals(0, detourDefaultStartMinutes(now, 60, zone))
    }

    @Test
    fun sanitizeIsIdempotentAtTheTruncationBoundary() {
        val raw = "a".repeat(59) + " bcd" // truncates to 59 'a' + trailing space
        val once = sanitizeDetourMotif(raw)
        assertEquals(once, sanitizeDetourMotif(once))
        assertEquals("a".repeat(59), once)
    }

    @Test
    fun bodiesKeepEpisodesWithLongTruncatedMotifs() {
        val motif = "a".repeat(59) + " bcd"
        val body = detourBodies(
            t(0L),
            t(36_000_000L),
            listOf(DetourEpisode(t(0L), t(1_800_000L), motif)),
        ).single()
        assertEquals("a".repeat(59), body.motif)
    }

    @Test
    fun midpointOutsideWindowMatchesBodyDrop() {
        val start = t(10_000_000L)
        val end = t(20_000_000L)
        val before = DetourEpisode(t(0L), t(1_000_000L), "early") // midpoint 500_000 < start
        val inside = DetourEpisode(t(12_000_000L), t(14_000_000L), "mid") // midpoint 13_000_000 in window
        assertTrue(detourMidpointOutsideWindow(before, start, end))
        assertFalse(detourMidpointOutsideWindow(inside, start, end))
        // An episode dropped from the ring is exactly one flagged off-window.
        assertEquals(emptyList(), detourBodies(start, end, listOf(before)))
        assertEquals(1, detourBodies(start, end, listOf(inside)).size)
    }

    @Test
    fun offWindowTotalSumsOnlyDroppedEpisodes() {
        val start = t(10_000_000L)
        val end = t(20_000_000L)
        val before = DetourEpisode(t(0L), t(2_000_000L), "early") // 2000s duration, midpoint 1_000_000 outside
        val inside = DetourEpisode(t(12_000_000L), t(15_000_000L), "mid") // 3000s, midpoint 13_500_000 inside
        val total = offWindowDetoursTotal(start, end, listOf(before, inside))
        assertEquals(before.duration, total)
        assertEquals(kotlin.time.Duration.ZERO, offWindowDetoursTotal(start, end, emptyList()))
        assertEquals(kotlin.time.Duration.ZERO, offWindowDetoursTotal(start, end, listOf(inside)))
    }

    @Test
    fun startOfLocalDayIsMidnightOfTheInstantsDay() {
        val zone = TimeZone.UTC
        val noon = Instant.parse("2026-07-12T12:34:56Z")
        assertEquals(Instant.parse("2026-07-12T00:00:00Z"), startOfLocalDay(noon, zone))
    }
}
