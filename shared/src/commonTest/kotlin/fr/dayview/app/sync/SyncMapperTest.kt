package fr.dayview.app.sync

import fr.dayview.app.AppRef
import fr.dayview.app.DayPreferencesSnapshot
import fr.dayview.app.DetourEpisode
import fr.dayview.app.NetTimeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SyncMapperTest {
    private val base = DayPreferencesSnapshot(
        startMinutes = 480,
        endMinutes = 1080,
        netTimeSettings = NetTimeSettings(enabled = true, includedCalendarIds = setOf("cal-1")),
        onGoalApps = emptySet(),
        fontScale = 1.3f,
    )

    @Test
    fun buildStampsAllFieldsOnFirstBuild() {
        val doc = buildDocument(base, base = null, deviceId = "a", now = 100)
        assertEquals(480, doc.dayWindow.value.start)
        assertEquals(Stamp(100, "a"), doc.dayWindow.stamp)
        // device-local calendar ids are NOT in the document (only the enabled toggle is)
        assertTrue(doc.netTimeEnabled.value)
    }

    @Test
    fun buildKeepsBaseStampWhenFieldUnchanged() {
        val first = buildDocument(base, base = null, deviceId = "a", now = 100)
        val second = buildDocument(base, base = first, deviceId = "a", now = 200)
        assertEquals(Stamp(100, "a"), second.dayWindow.stamp) // unchanged → old stamp kept
    }

    @Test
    fun buildRestampsChangedField() {
        val first = buildDocument(base, base = null, deviceId = "a", now = 100)
        val changed = base.copy(startMinutes = 500)
        val second = buildDocument(changed, base = first, deviceId = "a", now = 200)
        assertEquals(Stamp(200, "a"), second.dayWindow.stamp)
    }

    @Test
    fun buildEmitsTombstoneForRemovedDetour() {
        val withDetour = base.copy(
            detoursDayKey = 19000,
            detours = listOf(DetourEpisode(Instant.fromEpochMilliseconds(10), Instant.fromEpochMilliseconds(20), "coffee")),
        )
        val first = buildDocument(withDetour, base = null, deviceId = "a", now = 100)
        val removed = withDetour.copy(detours = emptyList())
        val second = buildDocument(removed, base = first, deviceId = "a", now = 200)
        val item = second.detours.items.single()
        assertTrue(item.deleted)
    }

    @Test
    fun detourCategoryAndDescriptionRoundTripThroughSync() {
        val withDetour = base.copy(
            detoursDayKey = 19000,
            detours = listOf(DetourEpisode(Instant.fromEpochMilliseconds(10), Instant.fromEpochMilliseconds(20), "Slack", "reading threads")),
        )
        val doc = buildDocument(withDetour, base = null, deviceId = "a", now = 100)
        val restored = applyDocument(doc, base).detours.single()
        assertEquals("Slack", restored.category)
        assertEquals("reading threads", restored.description) // description must not be dropped on sync
    }

    @Test
    fun applyPreservesDeviceLocalFields() {
        val doc = buildDocument(base.copy(startMinutes = 500), base = null, deviceId = "a", now = 100)
        val local = base.copy(startMinutes = 999) // remote should overwrite this…
        val result = applyDocument(doc, local)
        assertEquals(500, result.startMinutes) // synced field applied
        assertEquals(setOf("cal-1"), result.netTimeSettings.includedCalendarIds) // preserved
        assertEquals(1.3f, result.fontScale) // preserved
    }

    @Test
    fun applyDropsTombstonedDetours() {
        val withDetour = base.copy(
            detoursDayKey = 19000,
            detours = listOf(DetourEpisode(Instant.fromEpochMilliseconds(10), Instant.fromEpochMilliseconds(20), "coffee")),
        )
        val first = buildDocument(withDetour, base = null, deviceId = "a", now = 100)
        val second = buildDocument(withDetour.copy(detours = emptyList()), base = first, deviceId = "a", now = 200)
        assertTrue(applyDocument(second, withDetour).detours.isEmpty())
    }

    @Test
    fun buildCarriesTombstoneForwardOnNoOpRebuild() {
        val withDetour = base.copy(
            detoursDayKey = 19000,
            detours = listOf(DetourEpisode(Instant.fromEpochMilliseconds(10), Instant.fromEpochMilliseconds(20), "coffee")),
        )
        val removed = withDetour.copy(detours = emptyList())
        val first = buildDocument(withDetour, base = null, deviceId = "a", now = 100)
        val second = buildDocument(removed, base = first, deviceId = "a", now = 200)
        // Third build is a no-op rebuild against a base that already holds the tombstone.
        val third = buildDocument(removed, base = second, deviceId = "a", now = 300)
        val item = third.detours.items.single()
        assertTrue(item.deleted) // must survive the no-op rebuild, not vanish
    }

    @Test
    fun buildLeavesNeverSetGoalUnmarked() {
        val doc = buildDocument(base, base = null, deviceId = "a", now = 100)
        assertTrue(doc.goal.value.title.isEmpty())
        assertEquals(false, doc.goal.value.cleared) // default empty is not a deletion
    }

    @Test
    fun buildMarksGoalClearedWhenUserRemovesRealGoal() {
        val withGoal = base.copy(goalTitle = "Ship", goalDeadline = Instant.fromEpochMilliseconds(2000))
        val first = buildDocument(withGoal, base = null, deviceId = "a", now = 100)
        val cleared = base.copy(goalTitle = "", goalDeadline = null)
        val second = buildDocument(cleared, base = first, deviceId = "a", now = 200)
        assertEquals(true, second.goal.value.cleared)
        assertEquals(Stamp(200, "a"), second.goal.stamp) // fresh stamp so the deletion propagates
    }

    @Test
    fun buildCarriesGoalTombstoneForwardOnNoOpRebuild() {
        val withGoal = base.copy(goalTitle = "Ship", goalDeadline = Instant.fromEpochMilliseconds(2000))
        val cleared = base.copy(goalTitle = "", goalDeadline = null)
        val first = buildDocument(withGoal, base = null, deviceId = "a", now = 100)
        val second = buildDocument(cleared, base = first, deviceId = "a", now = 200)
        val third = buildDocument(cleared, base = second, deviceId = "a", now = 300)
        assertEquals(true, third.goal.value.cleared)
        assertEquals(Stamp(200, "a"), third.goal.stamp) // tombstone stamp kept, not re-stamped to 300
    }

    @Test
    fun applyDocumentPreservesOnGoalApps() {
        val onGoalApps = setOf(AppRef("com.example.focus", "Focus App"))
        val local = base.copy(onGoalApps = onGoalApps)
        val doc = buildDocument(base.copy(startMinutes = 500), base = null, deviceId = "a", now = 100)
        val result = applyDocument(doc, local)
        assertEquals(onGoalApps, result.onGoalApps)
    }

    @Test
    fun pomodoroRoundTripCarriesSessionAndBreak() {
        val snapshot = base.copy(
            pomodoroMinutes = 25,
            pomodoroEnd = Instant.fromEpochMilliseconds(1_000L),
            pomodoroSessionMinutes = 5,
            breakStart = Instant.fromEpochMilliseconds(2_000L),
        )
        val doc = buildDocument(snapshot, base = null, deviceId = "a", now = 100)
        val restored = applyDocument(doc, base)
        assertEquals(5, restored.pomodoroSessionMinutes)
        assertEquals(Instant.fromEpochMilliseconds(2_000L), restored.breakStart)
    }

    // A live break and a live open detour must never coexist (the detour always replaces the
    // break — see startOpenDetour / closePomodoro / closeFocusSnapshot). `pomodoro` and
    // `openDetour` are merged as two independent last-writer-wins registers (SyncMerge.kt), so
    // this reconstructs the real cross-device scenario: device A closes to a break while device
    // B, offline, starts a detour; each device's local build only restamps the field it actually
    // touched, so a plain per-field merge can legitimately pick the break from A and the detour
    // from B and hand back both live at once. This is not a contrived document — it is exactly
    // what `SyncDocument.merge` produces from two ordinary, unmodified device histories.
    @Test
    fun applyDocumentClearsBreakWhenMergedDocumentHasBothLive() {
        val docBase = buildDocument(base, base = null, deviceId = "seed", now = 0)
        val docA = buildDocument(
            base.copy(breakStart = Instant.fromEpochMilliseconds(5_000L)),
            base = docBase,
            deviceId = "a",
            now = 100,
        )
        val docB = buildDocument(
            base.copy(openDetourStart = Instant.fromEpochMilliseconds(6_000L), openDetourCategory = "Slack"),
            base = docBase,
            deviceId = "b",
            now = 50,
        )
        val merged = docA.merge(docB)
        // Sanity: confirm the merge itself (LWW semantics, untouched by this change) really does
        // produce both fields live — this is the hole being closed, not a fixture mistake.
        assertTrue(merged.pomodoro.value.breakStart > 0L)
        assertTrue(merged.openDetour.value.start > 0L)

        val result = applyDocument(merged, base)
        assertNull(result.breakStart) // the detour wins
        assertEquals(Instant.fromEpochMilliseconds(6_000L), result.openDetourStart)
        assertEquals("Slack", result.openDetourCategory)
    }

    @Test
    fun applyDocumentLeavesBreakAloneWhenNoDetourIsLive() {
        val docBase = buildDocument(base, base = null, deviceId = "seed", now = 0)
        val docA = buildDocument(
            base.copy(breakStart = Instant.fromEpochMilliseconds(5_000L)),
            base = docBase,
            deviceId = "a",
            now = 100,
        )
        val result = applyDocument(docA, base)
        assertEquals(Instant.fromEpochMilliseconds(5_000L), result.breakStart)
        assertNull(result.openDetourStart)
    }

    @Test
    fun applyDocumentLeavesDetourAloneWhenNoBreakIsLive() {
        val docBase = buildDocument(base, base = null, deviceId = "seed", now = 0)
        val docB = buildDocument(
            base.copy(openDetourStart = Instant.fromEpochMilliseconds(6_000L), openDetourCategory = "Slack"),
            base = docBase,
            deviceId = "b",
            now = 50,
        )
        val result = applyDocument(docB, base)
        assertEquals(Instant.fromEpochMilliseconds(6_000L), result.openDetourStart)
        assertNull(result.breakStart)
    }
}
