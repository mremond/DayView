package fr.dayview.app

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorReportingTest {
    @Test
    fun reportTransientLogsAndPostsToast() = runTest {
        val bus = AppEventBus()
        val received = mutableListOf<AppEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        runCurrent()

        bus.reportTransient("storage", RuntimeException("disk full"), ToastKind.SaveFailed)
        runCurrent()

        assertEquals<List<AppEvent>>(listOf(AppEvent.Toast(ToastKind.SaveFailed)), received)
        job.cancel()
    }
}
