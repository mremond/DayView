package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class AppEventAndUndoTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun controller(bus: AppEventBus) = DayViewController(
        DefaultDayPreferences,
        CoroutineScope(Dispatchers.Unconfined),
        initialSnapshot = DayPreferencesSnapshot(),
        initialNow = now,
        appEventBus = bus,
    )

    @Test
    fun removeDetourPostsToastAndRestoreReinserts() = runTest {
        val bus = AppEventBus()
        val received = mutableListOf<AppEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        runCurrent()

        val c = controller(bus)
        c.addDetour("Social", durationMinutes = 15)
        assertEquals(1, c.state.detoursToday.size)

        c.removeDetour(0)
        runCurrent()
        assertTrue(c.state.detoursToday.isEmpty())
        assertEquals(AppEvent.Toast(ToastKind.DetourRemoved, "Social"), received.last())

        c.restoreLastRemovedDetour()
        assertEquals(1, c.state.detoursToday.size)
        assertEquals("Social", c.state.detoursToday.first().category)
        job.cancel()
    }

    @Test
    fun removeObligationPostsToastAndRestoreReinsertsAtIndex() = runTest {
        val bus = AppEventBus()
        val received = mutableListOf<AppEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        runCurrent()

        val c = controller(bus)
        c.addPlannedObligation("Alpha")
        c.addPlannedObligation("Beta")

        c.removePlannedObligation("Alpha")
        runCurrent()
        assertEquals(listOf("Beta"), c.state.plannedObligationsToday)
        assertEquals(AppEvent.Toast(ToastKind.ObligationRemoved, "Alpha"), received.last())

        c.restoreLastRemovedObligation()
        assertEquals(listOf("Alpha", "Beta"), c.state.plannedObligationsToday)
        job.cancel()
    }
}
