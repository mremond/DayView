package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun mergeIsIdempotent() {
        val a = sampleDocument(deviceId = "a", at = 100)
        val b = sampleDocument(deviceId = "b", at = 200)
        val once = a.merge(b)
        assertEquals(once, once.merge(b))
    }
}
