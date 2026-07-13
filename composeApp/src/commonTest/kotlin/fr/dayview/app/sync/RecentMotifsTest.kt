package fr.dayview.app.sync

import fr.dayview.app.DayPreferencesSnapshot
import fr.dayview.app.MAX_RECENT_DETOUR_CATEGORIES
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
        assertEquals(listOf("zebra", "mango", "apple"), result.recentDetourCategories) // by stamp desc, not alphabetical
    }

    @Test
    fun applyTruncatesToCapKeepingMostRecent() {
        val many = (1..(MAX_RECENT_DETOUR_CATEGORIES + 3)).map { item("m$it", it.toLong()) }
        val doc = sampleDocument().copy(recentDetourMotifs = many)
        val result = applyDocument(doc, DayPreferencesSnapshot())
        assertEquals(MAX_RECENT_DETOUR_CATEGORIES, result.recentDetourCategories.size)
        assertEquals("m${MAX_RECENT_DETOUR_CATEGORIES + 3}", result.recentDetourCategories.first()) // newest kept
    }

    @Test
    fun buildBoundsRecentMotifTombstones() {
        val base = DayPreferencesSnapshot()
        var doc: SyncDocument? = null
        // Repeatedly rebuild with an entirely disjoint set of motifs each round, so every
        // previous round's live entries turn into tombstones. Without bounding, tombstones
        // would accumulate without limit across rounds.
        repeat(MAX_RECENT_DETOUR_CATEGORIES + 5) { round ->
            val motifs = (0 until MAX_RECENT_DETOUR_CATEGORIES).map { "round$round-m$it" }
            doc = buildDocument(
                base.copy(recentDetourCategories = motifs),
                base = doc,
                deviceId = "a",
                now = (round + 1).toLong() * 1000,
            )
        }
        assertTrue(doc!!.recentDetourMotifs.size <= 2 * MAX_RECENT_DETOUR_CATEGORIES)
    }

    @Test
    fun buildBoundsTombstonesWithRecurringLiveMotif() {
        val base = DayPreferencesSnapshot()
        var doc: SyncDocument? = null
        // One motif ("pinned") is reused unchanged every round, so buildItems never restamps it —
        // it keeps round 0's ancient stamp forever. The other MAX_RECENT_DETOUR_CATEGORIES - 1 slots
        // churn: each round introduces a fresh disjoint set, which turns into tombstones the next
        // round. Live count per round is exactly MAX_RECENT_DETOUR_CATEGORIES (1 pinned + churn), so
        // none of them get truncated from the live side — "pinned" is always retained and never
        // reset. Under the old cutoff-based algorithm, the cutoff was pinned's ancient stamp, so
        // every tombstone ever produced (all newer than that) was kept forever: unbounded growth.
        val churnPerRound = MAX_RECENT_DETOUR_CATEGORIES - 1
        repeat(MAX_RECENT_DETOUR_CATEGORIES + 5) { round ->
            val churn = (0 until churnPerRound).map { "round$round-c$it" }
            val motifs = listOf("pinned") + churn
            doc = buildDocument(
                base.copy(recentDetourCategories = motifs),
                base = doc,
                deviceId = "a",
                now = (round + 1).toLong() * 1000,
            )
        }
        assertTrue(doc!!.recentDetourMotifs.size <= 2 * MAX_RECENT_DETOUR_CATEGORIES)
    }
}
