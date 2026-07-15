package fr.dayview.app.sync

import fr.dayview.app.AppRef
import fr.dayview.app.DayPreferencesSnapshot
import fr.dayview.app.DetourEpisode
import fr.dayview.app.NetTimeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
