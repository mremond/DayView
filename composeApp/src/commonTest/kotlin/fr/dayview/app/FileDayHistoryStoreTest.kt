package fr.dayview.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class FakeHistoryFileSystem : HistoryFileSystem {
    val files = mutableMapOf<String, String>()
    override fun read(name: String): String? = files[name]
    override fun writeAtomic(name: String, text: String) {
        files[name] = text
    }
    override fun list(): List<String> = files.keys.toList()
}

class FileDayHistoryStoreTest {
    private fun record(dayKey: Long) = DayHistoryRecord(
        dayKey = dayKey, startMinutes = 480, endMinutes = 1080, focusIntention = "",
        busyIntervals = emptyList(), calendarNames = emptyMap(), netTimeSettings = NetTimeSettings(),
        focusPresenceIntervals = emptyList(), focusSessionIntervals = emptyList(),
        detours = emptyList(), cleanSessions = CleanSessionLedger(),
        pomodoroMinutes = 25, pomodoroEnd = null, goalTitle = "", goalDeadline = null, goalStart = null,
    )

    @Test
    fun writeThenReadRoundTrips() = runTest {
        val store = FileDayHistoryStore(FakeHistoryFileSystem())
        store.write(record(100L))
        assertEquals(record(100L), store.read(100L))
    }

    @Test
    fun readMissingDayIsNull() = runTest {
        assertNull(FileDayHistoryStore(FakeHistoryFileSystem()).read(999L))
    }

    @Test
    fun corruptFileReadsAsNull() = runTest {
        val fs = FakeHistoryFileSystem().apply { files["7"] = "garbage" }
        assertNull(FileDayHistoryStore(fs).read(7L))
    }

    @Test
    fun listDaysFiltersToRangeAndSorts() = runTest {
        val fs = FakeHistoryFileSystem().apply {
            files["10"] = ""
            files["20"] = ""
            files["30"] = ""
            files["not-a-number"] = ""
        }
        val store = FileDayHistoryStore(fs)
        assertEquals(listOf(10L, 20L), store.listDays(5L..25L))
    }

    @Test
    fun writeIsIdempotentAndDoesNotClobber() = runTest {
        val fs = FakeHistoryFileSystem()
        val store = FileDayHistoryStore(fs)
        store.write(record(100L))
        val first = fs.files["100"]
        // A second write for the same day (even with different content) must not overwrite.
        store.write(record(100L).copy(focusIntention = "changed"))
        assertEquals(first, fs.files["100"])
    }

    @Test
    fun listAllDaysReturnsEveryArchivedDaySorted() = runTest {
        val store = FileDayHistoryStore(FakeHistoryFileSystem())
        store.write(record(20200))
        store.write(record(20100))
        store.write(record(20300))
        assertEquals(listOf(20100L, 20200L, 20300L), store.listAllDays())
    }
}
