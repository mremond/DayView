package fr.dayview.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class FileFocusContributionStoreTest {
    private fun at(s: Long) = Instant.fromEpochMilliseconds(s * 1000)
    private fun iv(s: Long, e: Long) = FocusPresenceInterval(at(s), at(e))

    @Test
    fun writeThenReadRoundTrips() = runTest {
        val store = FileFocusContributionStore(FakeHistoryFileSystem())
        val contribution = FocusContribution(20260L, "deviceA", listOf(iv(0, 10)), listOf(iv(20, 30)))
        store.write(contribution)
        assertEquals(contribution, store.read(20260L, "deviceA"))
    }

    @Test
    fun readMissingKeyIsNull() = runTest {
        assertNull(FileFocusContributionStore(FakeHistoryFileSystem()).read(999L, "unknown"))
    }

    @Test
    fun corruptFileReadsAsNull() = runTest {
        val fs = FakeHistoryFileSystem().apply { files["focus_7_deviceA"] = "garbage" }
        assertNull(FileFocusContributionStore(fs).read(7L, "deviceA"))
    }

    @Test
    fun listForDayReturnsOnlyThatDayAcrossDevices() = runTest {
        val store = FileFocusContributionStore(FakeHistoryFileSystem())
        store.write(FocusContribution(20260L, "deviceA", listOf(iv(0, 10)), emptyList()))
        store.write(FocusContribution(20260L, "deviceB", emptyList(), listOf(iv(20, 30))))
        store.write(FocusContribution(20261L, "deviceA", listOf(iv(40, 50)), emptyList()))
        assertEquals(
            setOf(20260L to "deviceA", 20260L to "deviceB"),
            store.listForDay(20260L).map { it.dayKey to it.deviceId }.toSet(),
        )
    }

    @Test
    fun listKeysIgnoresHistoryFilesAndMalformedNames() = runTest {
        val fs = FakeHistoryFileSystem().apply {
            files["100"] = "" // a FileDayHistoryStore day record, not a focus contribution
            files["focus_"] = "" // malformed: no device suffix
            files["focus_notaday_deviceA"] = "" // malformed: non-numeric day
        }
        val store = FileFocusContributionStore(fs)
        assertEquals(emptyList(), store.listKeys())
    }

    @Test
    fun writeOverwritesExistingContributionForSameKey() = runTest {
        val store = FileFocusContributionStore(FakeHistoryFileSystem())
        store.write(FocusContribution(20260L, "deviceA", listOf(iv(0, 10)), emptyList()))
        store.write(FocusContribution(20260L, "deviceA", listOf(iv(0, 20)), emptyList()))
        assertEquals(listOf(iv(0, 20)), store.read(20260L, "deviceA")?.presence)
    }
}
