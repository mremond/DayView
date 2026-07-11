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
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleRefreshTest {
    private fun t(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

    @Test
    fun ticksWithTheCurrentClockOnEachResume() = runTest {
        var clock = t(1_000L)
        val ticks = mutableListOf<Instant>()
        val events = MutableSharedFlow<Lifecycle.Event>(extraBufferCapacity = 8)

        val job = launch { refreshClockOnResume(events, now = { clock }, tick = { ticks += it }) }
        runCurrent()

        events.emit(Lifecycle.Event.ON_CREATE)
        events.emit(Lifecycle.Event.ON_START)
        events.emit(Lifecycle.Event.ON_RESUME)
        runCurrent()

        // A later resume must read the clock again, not replay the first value.
        clock = t(2_000L)
        events.emit(Lifecycle.Event.ON_PAUSE)
        events.emit(Lifecycle.Event.ON_RESUME)
        runCurrent()

        job.cancel()
        assertEquals(listOf(t(1_000L), t(2_000L)), ticks)
    }

    @Test
    fun ignoresNonResumeEvents() = runTest {
        val ticks = mutableListOf<Instant>()
        val events = MutableSharedFlow<Lifecycle.Event>(extraBufferCapacity = 8)

        val job = launch { refreshClockOnResume(events, now = { t(42L) }, tick = { ticks += it }) }
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
