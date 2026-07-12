package fr.dayview.app

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun bodySizeFractionClampsBetween5And60Minutes() {
        val start = t(0L)
        val end = t(36_000_000L)
        fun sizeOf(minutes: Long): Float = detourBodies(
            start,
            end,
            listOf(DetourEpisode(t(7_200_000L), t(7_200_000L + minutes * 60_000L), "x")),
        ).single().sizeFraction
        assertEquals(0f, sizeOf(5))
        assertEquals(1f, sizeOf(60))
        assertEquals(1f, sizeOf(90))
        assertEquals(.4909f, sizeOf(32), absoluteTolerance = .01f) // (32 − 5) / 55
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
}
