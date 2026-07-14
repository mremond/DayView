package fr.dayview.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class FocusContributionStoreTest {
    private fun at(s: Long) = Instant.fromEpochMilliseconds(s * 1000)
    private fun iv(s: Long, e: Long) = FocusPresenceInterval(at(s), at(e))

    private fun recordWithSession(dayKey: Long, session: List<FocusPresenceInterval>) = DayHistoryRecord(
        dayKey = dayKey, startMinutes = 480, endMinutes = 1080, focusIntention = "",
        busyIntervals = emptyList(), calendarNames = emptyMap(), netTimeSettings = NetTimeSettings(),
        focusPresenceIntervals = emptyList(), focusSessionIntervals = session,
        detours = emptyList(), cleanSessions = CleanSessionLedger(),
        pomodoroMinutes = 25, pomodoroEnd = null, goalTitle = "", goalDeadline = null, goalStart = null,
    )

    @Test
    fun storeKeepsContributionsPerDeviceAndListsThem() = runTest {
        val store = InMemoryFocusContributionStore()
        store.write(FocusContribution(20260, "aaa", listOf(iv(0, 10)), emptyList()))
        store.write(FocusContribution(20260, "bbb", emptyList(), listOf(iv(20, 30))))
        assertEquals(2, store.listForDay(20260).size)
        assertEquals(setOf(20260L to "aaa", 20260L to "bbb"), store.listKeys().toSet())
    }

    @Test
    fun withMergedFocusUnionsRecordAndContributions() {
        val record = recordWithSession(20260, listOf(iv(0, 10)))
        val merged = record.withMergedFocus(
            listOf(FocusContribution(20260, "bbb", emptyList(), listOf(iv(5, 30)))),
        )
        // 0-10 (own) unions with 5-30 (foreign) -> 0-30.
        assertEquals(listOf(iv(0, 30)), merged.focusSessionIntervals)
    }
}
