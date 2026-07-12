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
    fun dayKeyMatchesLocalCalendarDay() {
        val zone = TimeZone.of("Europe/Paris")
        // 2026-07-12 08:00 and 17:00 Paris are the same day; 2026-07-13 01:00 is the next.
        val morning = Instant.parse("2026-07-12T06:00:00Z")
        val evening = Instant.parse("2026-07-12T15:00:00Z")
        val nextDay = Instant.parse("2026-07-12T23:30:00Z")
        assertEquals(dayKeyOf(morning, zone), dayKeyOf(evening, zone))
        assertTrue(dayKeyOf(nextDay, zone) > dayKeyOf(evening, zone))
    }
}
