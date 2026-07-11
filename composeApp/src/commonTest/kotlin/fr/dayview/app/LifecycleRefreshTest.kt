package fr.dayview.app

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleRefreshTest {
    @Test
    fun ticksWithTheCurrentClockOnEachResume() = runTest {
        var clock = 1_000L
        val ticks = mutableListOf<Long>()
        val events = MutableSharedFlow<Lifecycle.Event>(extraBufferCapacity = 8)

        val job = launch { refreshClockOnResume(events, now = { clock }, tick = { ticks += it }) }
        runCurrent()

        events.emit(Lifecycle.Event.ON_CREATE)
        events.emit(Lifecycle.Event.ON_START)
        events.emit(Lifecycle.Event.ON_RESUME)
        runCurrent()

        // A later resume must read the clock again, not replay the first value.
        clock = 2_000L
        events.emit(Lifecycle.Event.ON_PAUSE)
        events.emit(Lifecycle.Event.ON_RESUME)
        runCurrent()

        job.cancel()
        assertEquals(listOf(1_000L, 2_000L), ticks)
    }

    @Test
    fun ignoresNonResumeEvents() = runTest {
        val ticks = mutableListOf<Long>()
        val events = MutableSharedFlow<Lifecycle.Event>(extraBufferCapacity = 8)

        val job = launch { refreshClockOnResume(events, now = { 42L }, tick = { ticks += it }) }
        runCurrent()

        listOf(
            Lifecycle.Event.ON_CREATE,
            Lifecycle.Event.ON_START,
            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP,
        ).forEach { events.emit(it) }
        runCurrent()

        job.cancel()
        assertTrue(ticks.isEmpty())
    }
}
