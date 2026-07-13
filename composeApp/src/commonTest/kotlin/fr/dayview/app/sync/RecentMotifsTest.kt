package fr.dayview.app.sync

import fr.dayview.app.DayPreferencesSnapshot
import fr.dayview.app.MAX_RECENT_DETOUR_MOTIFS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentMotifsTest {
    private fun item(v: String, at: Long) = SyncItem(v, v, false, Stamp(at, "a"))

    @Test
    fun applyReturnsMotifsMostRecentFirst() {
        val doc = sampleDocument().copy(
            recentDetourMotifs = listOf(item("apple", 100), item("zebra", 300), item("mango", 200)),
        )
        val result = applyDocument(doc, DayPreferencesSnapshot())
        assertEquals(listOf("zebra", "mango", "apple"), result.recentDetourMotifs) // by stamp desc, not alphabetical
    }

    @Test
    fun applyTruncatesToCapKeepingMostRecent() {
        val many = (1..(MAX_RECENT_DETOUR_MOTIFS + 3)).map { item("m$it", it.toLong()) }
        val doc = sampleDocument().copy(recentDetourMotifs = many)
        val result = applyDocument(doc, DayPreferencesSnapshot())
        assertEquals(MAX_RECENT_DETOUR_MOTIFS, result.recentDetourMotifs.size)
        assertEquals("m${MAX_RECENT_DETOUR_MOTIFS + 3}", result.recentDetourMotifs.first()) // newest kept
    }

    @Test
    fun buildBoundsRecentMotifTombstones() {
        val base = DayPreferencesSnapshot()
        var doc: SyncDocument? = null
        // Repeatedly rebuild with an entirely disjoint set of motifs each round, so every
        // previous round's live entries turn into tombstones. Without bounding, tombstones
        // would accumulate without limit across rounds.
        repeat(MAX_RECENT_DETOUR_MOTIFS + 5) { round ->
            val motifs = (0 until MAX_RECENT_DETOUR_MOTIFS).map { "round$round-m$it" }
            doc = buildDocument(
                base.copy(recentDetourMotifs = motifs),
                base = doc,
                deviceId = "a",
                now = (round + 1).toLong() * 1000,
            )
        }
        assertTrue(doc!!.recentDetourMotifs.size <= 2 * MAX_RECENT_DETOUR_MOTIFS)
    }
}
