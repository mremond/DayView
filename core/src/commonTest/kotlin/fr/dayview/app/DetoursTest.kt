package fr.dayview.app

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun t(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

// Reuses DetoursTest's existing `t(ms)` helper for instants.
private fun ep(startMs: Long, endMs: Long, category: String, description: String = "") = DetourEpisode(t(startMs), t(endMs), category, description)

class DetoursTest {
    @Test
    fun sanitizeStripsNewlinesTrimsAndBounds() {
        assertEquals("appel urgent", sanitizeDetourCategory("  appel\nurgent \r"))
        assertEquals(60, sanitizeDetourCategory("x".repeat(80)).length)
    }

    @Test
    fun detoursEncodeDecodeRoundTrips() {
        val episodes = listOf(
            DetourEpisode(t(1_000L), t(2_000L), "Appel urgent"),
            DetourEpisode(t(3_000L), t(4_000L), "Slack"),
        )
        assertEquals(episodes, decodeDetours(encodeDetours(episodes)))
    }

    @Test
    fun decodeSkipsMalformedBlankAndEmptyCategoryLines() {
        val encoded = "not a line\n1000,2000,ok\n5000,4000,inverted\n1000,2000,\n\n"
        assertEquals(listOf(DetourEpisode(t(1_000L), t(2_000L), "ok")), decodeDetours(encoded))
    }

    @Test
    fun encodeDecodeRoundTripsDescription() {
        val episodes = listOf(
            ep(1_000, 2_000, "Slack", "reading threads, then more"), // description keeps commas
            ep(3_000, 4_000, "Pause", ""),
        )
        assertEquals(episodes, decodeDetours(encodeDetours(episodes)))
    }

    @Test
    fun decodeStripsCommaFromCategoryOnEncode() {
        val decoded = decodeDetours(encodeDetours(listOf(ep(1_000, 2_000, "a,b", "d"))))
        assertEquals("a b", decoded.single().category) // comma → space
        assertEquals("d", decoded.single().description)
    }

    @Test
    fun encodeDecodeOfEmptyListUsesBareMarker() {
        assertEquals("@2", encodeDetours(emptyList()))
        assertEquals(emptyList(), decodeDetours("@2"))
    }

    @Test
    fun decodeLegacyBlobMapsMotifToCategory() {
        // Legacy 3-field lines (no version marker); motif becomes category, description empty.
        val legacy = "1000,2000,café\n3000,4000,appel,imprévu"
        val decoded = decodeDetours(legacy)
        assertEquals(2, decoded.size)
        assertEquals("café", decoded[0].category)
        assertEquals("", decoded[0].description)
        assertEquals("appel imprévu", decoded[1].category) // legacy comma folded to a space
        assertEquals("", decoded[1].description)
    }

    @Test
    fun decodeSkipsMalformedMarkedLines() {
        val blob = "@2\n1000,2000,ok,note\nblank\n5000,4000,inverted,x\n7000,8000,,nocat"
        val decoded = decodeDetours(blob)
        assertEquals(1, decoded.size)
        assertEquals("ok", decoded.single().category)
        assertEquals("note", decoded.single().description)
    }

    @Test
    fun recentCategoriesEncodeDecodeRoundTrips() {
        val categories = listOf("Appel, urgent", "Slack")
        assertEquals(categories, decodeRecentDetourCategories(encodeRecentDetourCategories(categories)))
        assertEquals(emptyList(), decodeRecentDetourCategories(""))
    }

    @Test
    fun pushRecentDedupesCaseInsensitivelyAndCaps() {
        val once = pushRecentDetourCategory(listOf("Slack", "Appels"), "slack")
        assertEquals(listOf("slack", "Appels"), once)
        var recents = emptyList<String>()
        repeat(15) { recents = pushRecentDetourCategory(recents, "category $it") }
        assertEquals(MAX_RECENT_DETOUR_CATEGORIES, recents.size)
        assertEquals("category 14", recents.first())
    }

    @Test
    fun pushRecentIgnoresBlankCategories() {
        assertEquals(listOf("Slack"), pushRecentDetourCategory(listOf("Slack"), "  \n"))
    }

    @Test
    fun removeRecentDropsCategoryCaseInsensitively() {
        assertEquals(listOf("Slack"), removeRecentDetourCategory(listOf("Slack", "dsfdsf"), "DSFDSF"))
        assertEquals(emptyList(), removeRecentDetourCategory(listOf("Slack"), "  slack  "))
    }

    @Test
    fun removeRecentLeavesListUntouchedWhenAbsent() {
        assertEquals(listOf("Slack", "Appels"), removeRecentDetourCategory(listOf("Slack", "Appels"), "absent"))
        assertEquals(listOf("Slack"), removeRecentDetourCategory(listOf("Slack"), "  "))
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
    fun sourcesAggregateByNormalizedCategoryHeaviestFirst() {
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
    fun bodiesFormArcsAcrossTheirDuration() {
        val start = t(0L)
        val end = t(36_000_000L) // 10 h window
        // 0 → 1 h episode: starts at the window start (-90°), spans 1/10 of the window (36°).
        val body = detourBodies(start, end, listOf(DetourEpisode(t(0L), t(3_600_000L), "Slack"))).single()
        assertEquals(-90f, body.startAngleDegrees, absoluteTolerance = .01f)
        assertEquals(36f, body.sweepDegrees, absoluteTolerance = .01f)
    }

    @Test
    fun bodiesClipToTheWindowWhenTheEpisodeStartsBefore() {
        val start = t(0L)
        val end = t(36_000_000L) // 10 h window
        // Starts 1 h before the window, ends 1 h in; midpoint is exactly windowStart (inside).
        val body = detourBodies(
            start,
            end,
            listOf(DetourEpisode(t(-3_600_000L), t(3_600_000L), "Slack")),
        ).single()
        // Clipped to [windowStart, clippedEnd]: fStart = 0 → -90°, fEnd = 0.1 → 36° sweep.
        assertEquals(-90f, body.startAngleDegrees, absoluteTolerance = .01f)
        assertEquals(36f, body.sweepDegrees, absoluteTolerance = .01f)
    }

    @Test
    fun shortDetoursGetAMinimumSweepCenteredOnTheMidpoint() {
        val start = t(0L)
        val end = t(86_400_000L) // 24 h window: 15°/h
        // 6 h → 6 h 04 min: raw sweep 1° is floored to 3.5°, kept centred on the 06:02 midpoint.
        val body = detourBodies(
            start,
            end,
            listOf(DetourEpisode(t(21_600_000L), t(21_840_000L), "x")),
        ).single()
        assertEquals(3.5f, body.sweepDegrees, absoluteTolerance = .01f)
        // Midpoint fraction .251388 → angle 0.5°; arc centre = start + sweep/2.
        assertEquals(0.5f, body.startAngleDegrees + body.sweepDegrees / 2f, absoluteTolerance = .05f)
    }

    @Test
    fun bodiesOutsideTheWindowAreDropped() {
        val start = t(10_000_000L)
        val end = t(20_000_000L)
        val before = DetourEpisode(t(0L), t(1_000_000L), "early")
        assertEquals(emptyList(), detourBodies(start, end, listOf(before)))
    }

    @Test
    fun hitTestFindsTheArcUnderThePointer() {
        val body = DetourBody(
            startAngleDegrees = -100f,
            sweepDegrees = 20f, // covers -100°..-80°, i.e. the top of the dial
            colorIndex = 0,
            category = "Slack",
            description = "",
            start = t(0L),
            end = t(1L),
        )
        // Top of a 400×400 dial, on the outer detour lane (radiusFraction .925): found.
        assertEquals(body, hitTestDetourBody(200f, 15f, 400, 400, listOf(body)))
        // Center of the dial: not on the lane.
        assertEquals(null, hitTestDetourBody(200f, 200f, 400, 400, listOf(body)))
        // Bottom of the dial: on the lane but 180° away.
        assertEquals(null, hitTestDetourBody(200f, 385f, 400, 400, listOf(body)))
        // Inner band (radiusFraction .80), where the old beads lived: no longer a hit.
        assertEquals(null, hitTestDetourBody(200f, 40f, 400, 400, listOf(body)))
    }

    @Test
    fun sanitizeDetourCategoryStripsCommasAndCaps() {
        // Comma → space (inputs without a trailing space avoid a double space).
        assertEquals("Reseaux sociaux", sanitizeDetourCategory("Reseaux,sociaux"))
        assertEquals(60, sanitizeDetourCategory("x".repeat(200)).length)
    }

    @Test
    fun sanitizeDetourDescriptionStripsNewlinesAndCaps() {
        assertEquals("a b", sanitizeDetourDescription("a\nb"))
        assertEquals(200, sanitizeDetourDescription("y".repeat(300)).length)
        assertEquals("with, comma", sanitizeDetourDescription("with, comma")) // commas kept
    }

    @Test
    fun detourEpisodeCarriesDescription() {
        val episode = detourEpisodeAt(t(0), 12 * 60, 15, "Slack", "reading threads")
        assertEquals("Slack", episode.category)
        assertEquals("reading threads", episode.description)
    }

    @Test
    fun detourEpisodeAtBuildsOnTheSameLocalDay() {
        val zone = TimeZone.of("Europe/Paris")
        val reference = Instant.parse("2026-07-12T10:00:00Z")
        val episode = detourEpisodeAt(reference, 9 * 60 + 30, 45, " appel ", timeZone = zone)
        assertEquals("appel", episode.category)
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
        val once = sanitizeDetourCategory(raw)
        assertEquals(once, sanitizeDetourCategory(once))
        assertEquals("a".repeat(59), once)
    }

    @Test
    fun bodiesKeepEpisodesWithLongTruncatedCategories() {
        val category = "a".repeat(59) + " bcd"
        val body = detourBodies(
            t(0L),
            t(36_000_000L),
            listOf(DetourEpisode(t(0L), t(1_800_000L), category)),
        ).single()
        assertEquals("a".repeat(59), body.category)
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

    @Test
    fun detourBodyAtAngleFindsBodyWithinItsArc() {
        val windowStart = t(0L)
        val windowEnd = t(24L * 60 * 60 * 1000) // 24 h window: 15°/h
        // A 5 h → 7 h detour: arc from -15° to 15°.
        val episodes = listOf(DetourEpisode(t(5L * 3_600_000), t(7L * 3_600_000), "Slack"))
        val bodies = detourBodies(windowStart, windowEnd, episodes)
        assertEquals(1, bodies.size)
        val body = bodies.first()
        // Inside the arc it is found; far away (180° across) it is not.
        assertEquals(body, detourBodyAtAngle(bodies, 0f))
        assertNull(detourBodyAtAngle(bodies, 180f))
    }
}
