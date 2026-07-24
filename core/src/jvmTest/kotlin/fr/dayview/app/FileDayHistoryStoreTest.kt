package fr.dayview.app

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDayHistoryStoreTest {
    private val dir = "/history".toPath()

    private fun newStore(fs: FakeFileSystem) = FileDayHistoryStore(OkioHistoryFileSystem(fs, dir))

    private fun record(dayKey: Long) = DayHistoryRecord(
        dayKey = dayKey, startMinutes = 480, endMinutes = 1080, focusIntention = "",
        busyIntervals = emptyList(), calendarNames = emptyMap(), netTimeSettings = NetTimeSettings(),
        focusPresenceIntervals = emptyList(), focusSessionIntervals = emptyList(),
        focusSessionRecords = emptyList(),
        detours = emptyList(), cleanSessions = CleanSessionLedger(),
        pomodoroMinutes = 25, pomodoroEnd = null, goalTitle = "", goalDeadline = null, goalStart = null,
    )

    @Test
    fun writeThenReadRoundTripsThroughARealFilesystem() = runTest {
        val store = newStore(FakeFileSystem())
        store.write(record(100L))
        assertEquals(record(100L), store.read(100L))
    }

    @Test
    fun readMissingDayIsNull() = runTest {
        assertNull(newStore(FakeFileSystem()).read(999L))
    }

    @Test
    fun corruptFileReadsAsNull() = runTest {
        val fs = FakeFileSystem()
        val store = newStore(fs)
        OkioHistoryFileSystem(fs, dir).writeAtomic("7", "garbage")
        assertNull(store.read(7L))
    }

    @Test
    fun writeIsIdempotentAndDoesNotClobber() = runTest {
        val fs = FakeFileSystem()
        val store = newStore(fs)
        store.write(record(100L))
        val first = fs.read(dir / "100") { readUtf8() }
        // A stale day can be archived twice (cold launch, then a rollover tick): the first
        // record must win, even when the second carries different content.
        store.write(record(100L).copy(focusIntention = "changed"))
        assertEquals(first, fs.read(dir / "100") { readUtf8() })
    }

    @Test
    fun listDaysFiltersToRangeAndSorts() = runTest {
        val fs = FakeFileSystem()
        val store = newStore(fs)
        store.write(record(10L))
        store.write(record(20L))
        store.write(record(30L))
        assertEquals(listOf(10L, 20L), store.listDays(5L..25L))
    }

    @Test
    fun listAllDaysReturnsEveryArchivedDaySorted() = runTest {
        val store = newStore(FakeFileSystem())
        store.write(record(20200))
        store.write(record(20100))
        store.write(record(20300))
        assertEquals(listOf(20100L, 20200L, 20300L), store.listAllDays())
    }

    @Test
    fun temporaryFilesAreNeitherLeftBehindNorListedAsDays() = runTest {
        val fs = FakeFileSystem()
        val store = newStore(fs)
        store.write(record(42L))
        // The atomic write must move its scratch file, not copy it.
        assertTrue(fs.list(dir).none { it.name.endsWith(".tmp") }, "a .tmp file was left behind")

        // A leftover from an interrupted write must never read as an archived day.
        fs.write(dir / "99.tmp") { writeUtf8("interrupted") }
        assertEquals(listOf(42L), store.listAllDays())
    }

    @Test
    fun writingCreatesTheDirectoryWhenItDoesNotExist() = runTest {
        val fs = FakeFileSystem()
        // No createDirectories call: the store is the first thing to touch this path.
        newStore(fs).write(record(7L))
        assertEquals(record(7L), newStore(fs).read(7L))
    }

    @Test
    fun theFileSystemListItselfExcludesTmpLeftovers() = runTest {
        val fs = FakeFileSystem()
        val historyFs = OkioHistoryFileSystem(fs, dir)
        historyFs.writeAtomic("42", "content")
        // A leftover from an interrupted write must not be listed by the filesystem itself,
        // independent of whatever numeric filtering the store layers on top.
        fs.write(dir / "99.tmp") { writeUtf8("interrupted") }
        val names = historyFs.list()
        assertTrue("42" in names, "expected the real day file to be listed")
        assertTrue("99.tmp" !in names, "a .tmp file was listed")
    }

    @Test
    fun listingBeforeAnythingIsEverWrittenReturnsNoDays() = runTest {
        val fs = FakeFileSystem()
        // The directory has never been created: this is every fresh install's first launch.
        assertEquals(emptyList(), newStore(fs).listAllDays())
    }

    @Test
    fun nonNumericFileNamesAreNotListedAsDays() = runTest {
        val fs = FakeFileSystem()
        val store = newStore(fs)
        store.write(record(10L))
        // FileFocusContributionStore writes focus_<day>_<device> files into this same
        // directory (see DayHistoryStore.kt's contract note); they must never surface as
        // archived days.
        fs.write(dir / "focus_10_deviceA") { writeUtf8("not a day record") }
        assertEquals(listOf(10L), store.listAllDays())
    }
}
