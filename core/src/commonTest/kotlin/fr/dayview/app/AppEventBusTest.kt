package fr.dayview.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AppEventBusTest {
    @Test
    fun postDeliversToActiveCollector() = runTest {
        val bus = AppEventBus()
        val received = mutableListOf<AppEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        runCurrent()

        bus.post(AppEvent.Toast(ToastKind.SyncSucceeded))
        bus.post(AppEvent.Toast(ToastKind.DetourRemoved, arg = "Social"))
        runCurrent()

        assertEquals(
            listOf<AppEvent>(
                AppEvent.Toast(ToastKind.SyncSucceeded),
                AppEvent.Toast(ToastKind.DetourRemoved, arg = "Social"),
            ),
            received,
        )
        job.cancel()
    }
}
