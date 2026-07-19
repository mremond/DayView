package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Sync merges `pomodoro` (carries breakStart) and `openDetour` (carries openDetourStart) as
 * two independent last-writer-wins registers (SyncMerge.kt), so two devices editing
 * concurrently and offline can merge into a snapshot with both fields live even though every
 * same-device write path (closePomodoro, closeFocusSnapshot, startOpenDetour) forbids it.
 * `DayViewController.coerced()` is the boundary both routes a restored snapshot travels
 * through — the constructor's `initialSnapshot` (disk restore, via `toUiState`) and
 * `onPreferencesChanged` (sync-applied snapshots, via `withPersisted`) — so that is where the
 * reconciliation belongs, and this file exercises both entry points.
 */
class SyncSnapshotReconciliationTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun controller(snapshot: DayPreferencesSnapshot = DayPreferencesSnapshot()) = DayViewController(
        DefaultDayPreferences,
        CoroutineScope(Dispatchers.Unconfined),
        initialSnapshot = snapshot,
        initialNow = now,
    )

    @Test
    fun restoringASnapshotWithBothLiveClearsTheBreak() {
        val c = controller(
            DayPreferencesSnapshot(
                breakStart = now - 5.minutes,
                openDetourStart = now - 2.minutes,
            ),
        )
        assertNull(c.state.breakStart)
        assertEquals(now - 2.minutes, c.state.openDetourStart)
    }

    @Test
    fun onPreferencesChangedWithBothLiveClearsTheBreak() {
        val c = controller()
        c.onPreferencesChanged(
            DayPreferencesSnapshot(
                breakStart = now - 5.minutes,
                openDetourStart = now - 2.minutes,
            ),
        )
        assertNull(c.state.breakStart)
        assertEquals(now - 2.minutes, c.state.openDetourStart)
    }

    @Test
    fun restoringASnapshotWithOnlyABreakLiveLeavesItUnaffected() {
        val c = controller(DayPreferencesSnapshot(breakStart = now - 5.minutes))
        assertEquals(now - 5.minutes, c.state.breakStart)
        assertNull(c.state.openDetourStart)
    }

    @Test
    fun onPreferencesChangedWithOnlyABreakLiveLeavesItUnaffected() {
        val c = controller()
        c.onPreferencesChanged(DayPreferencesSnapshot(breakStart = now - 5.minutes))
        assertEquals(now - 5.minutes, c.state.breakStart)
        assertNull(c.state.openDetourStart)
    }

    @Test
    fun restoringASnapshotWithOnlyADetourLiveLeavesItUnaffected() {
        val c = controller(DayPreferencesSnapshot(openDetourStart = now - 5.minutes))
        assertNull(c.state.breakStart)
        assertEquals(now - 5.minutes, c.state.openDetourStart)
    }

    @Test
    fun onPreferencesChangedWithOnlyADetourLiveLeavesItUnaffected() {
        val c = controller()
        c.onPreferencesChanged(DayPreferencesSnapshot(openDetourStart = now - 5.minutes))
        assertNull(c.state.breakStart)
        assertEquals(now - 5.minutes, c.state.openDetourStart)
    }
}
