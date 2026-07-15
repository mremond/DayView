package fr.dayview.app.sync

import fr.dayview.app.MAX_RECENT_DETOUR_CATEGORIES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncMergeTest {
    @Test
    fun nullRemoteReturnsLocal() {
        val local = sampleDocument()
        assertEquals(local, local.merge(null))
    }

    @Test
    fun newerFieldWins() {
        val a = sampleDocument(deviceId = "a", at = 100).let { it.copy(goal = Versioned(it.goal.value.copy(title = "old"), Stamp(100, "a"))) }
        val b = sampleDocument(deviceId = "b", at = 200).let { it.copy(goal = Versioned(it.goal.value.copy(title = "new"), Stamp(200, "b"))) }
        assertEquals("new", a.merge(b).goal.value.title)
        assertEquals("new", b.merge(a).goal.value.title) // commutative
    }

    private fun goalDoc(deviceId: String, at: Long, value: GoalDto): SyncDocument = sampleDocument(deviceId, at).let { it.copy(goal = Versioned(value, Stamp(at, deviceId))) }

    private val emptyDefaultGoal = GoalDto(title = "", deadline = -1L, start = -1L, cleared = false)
    private val emptyClearedGoal = GoalDto(title = "", deadline = -1L, start = -1L, cleared = true)
    private val contentGoal = GoalDto(title = "Ship", deadline = 2000L, start = 1500L)

    @Test
    fun defaultEmptyGoalNeverOverwritesContentEvenWhenNewer() {
        val content = goalDoc("a", at = 100, contentGoal)
        val freshDefault = goalDoc("b", at = 999, emptyDefaultGoal) // newer, but never-set
        assertEquals("Ship", content.merge(freshDefault).goal.value.title)
        assertEquals("Ship", freshDefault.merge(content).goal.value.title) // commutative
    }

    @Test
    fun voluntaryClearWinsWhenNewer() {
        val content = goalDoc("a", at = 100, contentGoal)
        val cleared = goalDoc("b", at = 200, emptyClearedGoal) // user deleted it, newer
        assertTrue(content.merge(cleared).goal.value.title.isEmpty())
        assertTrue(content.merge(cleared).goal.value.cleared)
        assertEquals(content.merge(cleared), cleared.merge(content)) // commutative
    }

    @Test
    fun reSettingGoalAfterClearRestoresIt() {
        val cleared = goalDoc("a", at = 100, emptyClearedGoal) // older deletion
        val content = goalDoc("b", at = 200, contentGoal) // re-set later
        assertEquals("Ship", cleared.merge(content).goal.value.title)
    }

    @Test
    fun sameDayDetoursUnionByKey() {
        val s = Stamp(100, "a")
        val a = sampleDocument().copy(detours = DayScoped(19000, listOf(SyncItem("k1", DetourDto(10, 20, "coffee"), false, s))))
        val b = sampleDocument().copy(detours = DayScoped(19000, listOf(SyncItem("k2", DetourDto(30, 40, "call"), false, Stamp(150, "b")))))
        assertEquals(2, a.merge(b).detours.items.count { !it.deleted })
    }

    @Test
    fun newerDayReplacesOlderDayWholesale() {
        val old = sampleDocument().copy(detours = DayScoped(19000, listOf(SyncItem("k1", DetourDto(10, 20, "x"), false, Stamp(100, "a")))))
        val new = sampleDocument().copy(detours = DayScoped(19001, emptyList()))
        assertEquals(19001, old.merge(new).detours.dayKey)
        assertEquals(0, old.merge(new).detours.items.size)
    }

    @Test
    fun tombstoneWinsOverStaleAdd() {
        val add = SyncItem("k1", DetourDto(10, 20, "x"), false, Stamp(100, "a"))
        val del = SyncItem("k1", DetourDto(10, 20, "x"), true, Stamp(200, "b"))
        val a = sampleDocument().copy(detours = DayScoped(19000, listOf(add)))
        val b = sampleDocument().copy(detours = DayScoped(19000, listOf(del)))
        assertEquals(true, a.merge(b).detours.items.single().deleted)
    }

    @Test
    fun cleanTodayTakesMaxAtEqualDay() {
        val a = sampleDocument().copy(cleanSessions = Versioned(CleanDto(19000, 3, 5, 18999), Stamp(100, "a")))
        val b = sampleDocument().copy(cleanSessions = Versioned(CleanDto(19000, 1, 7, 19000), Stamp(200, "b")))
        val merged = a.merge(b).cleanSessions.value
        assertEquals(3, merged.cleanToday) // max
        assertEquals(7, merged.streakDays) // from greater streakLastDayKey
        assertEquals(19000, merged.streakLastDayKey)
    }

    @Test
    fun mergeIsCommutativeForMultipleItems() {
        val a = sampleDocument().copy(detours = DayScoped(19000, listOf(SyncItem("k1", DetourDto(10, 20, "coffee"), false, Stamp(100, "a")))))
        val b = sampleDocument().copy(detours = DayScoped(19000, listOf(SyncItem("k2", DetourDto(30, 40, "call"), false, Stamp(150, "b")))))
        assertEquals(a.merge(b), b.merge(a))
    }

    @Test
    fun mergeUnionsCompletedObligationsAcrossDevices() {
        val a = sampleDocument("dev-a", at = 100).copy(
            plannedObligationsCompleted = DayScoped(19000, listOf(SyncItem("x", "x", false, Stamp(100, "dev-a")))),
        )
        val b = sampleDocument("dev-b", at = 200).copy(
            plannedObligationsCompleted = DayScoped(19000, listOf(SyncItem("y", "y", false, Stamp(200, "dev-b")))),
        )
        val merged = a.merge(b)
        assertEquals(setOf("x", "y"), merged.plannedObligationsCompleted.items.filterNot { it.deleted }.map { it.value }.toSet())
    }

    @Test
    fun mergeIsIdempotent() {
        val a = sampleDocument(deviceId = "a", at = 100)
        val b = sampleDocument(deviceId = "b", at = 200)
        val once = a.merge(b)
        assertEquals(once, once.merge(b))
    }

    // Simulates SyncEngine's real loop: each round a device builds a local doc with a fresh,
    // disjoint set of recent motifs, merges it against the accumulated base, then the merged
    // result is fed back in as the next base (mirroring SyncEngine persisting `merged` as
    // state.baseDocument). buildDocument's own bound only ever sees the pre-merge local doc, so
    // without a bound applied to the merge output itself, mergeItems' plain per-id union
    // re-admits every tombstone either side had already dropped and the base grows without
    // bound. This test fails (accumulated size far exceeds 2 * MAX_RECENT_DETOUR_CATEGORIES) if the
    // merge-level bound in SyncDocument.merge is reverted, confirmed by temporarily reverting the
    // `boundRecentMotifItems(...)` wrapper around `mergeItems(...)` there.
    @Test
    fun mergeKeepsRecentMotifsBoundedAcrossLoop() {
        var base: SyncDocument? = null
        repeat(30) { round ->
            val motifs = (0 until MAX_RECENT_DETOUR_CATEGORIES).map {
                SyncItem("round$round-m$it", "round$round-m$it", false, Stamp((round + 1).toLong() * 1000, "dev"))
            }
            val local = sampleDocument(deviceId = "dev", at = (round + 1).toLong() * 1000)
                .copy(recentDetourMotifs = motifs)
            val merged = local.merge(base)
            assertTrue(
                merged.recentDetourMotifs.size <= 2 * MAX_RECENT_DETOUR_CATEGORIES,
                "round $round: recentDetourMotifs grew to ${merged.recentDetourMotifs.size}",
            )
            base = merged
        }
    }

    @Test
    fun mergeUnionsHistoryDays() {
        val a = sampleDocument(deviceId = "a", at = 10).copy(historyDays = listOf(1L, 3L))
        val b = sampleDocument(deviceId = "b", at = 10).copy(historyDays = listOf(3L, 2L))
        assertEquals(listOf(1L, 2L, 3L), a.merge(b).historyDays)
        // commutative + deduped + sorted
        assertEquals(a.merge(b).historyDays, b.merge(a).historyDays)
    }

    @Test
    fun focusContributionsMergeByUnion() {
        val a = sampleDocument().copy(focusContributions = listOf("20260:aaa"))
        val b = sampleDocument().copy(focusContributions = listOf("20260:bbb", "20260:aaa"))
        assertEquals(listOf("20260:aaa", "20260:bbb"), a.merge(b).focusContributions)
    }
}
