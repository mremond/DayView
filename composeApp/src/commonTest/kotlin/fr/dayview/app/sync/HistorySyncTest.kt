package fr.dayview.app.sync

import fr.dayview.app.CleanSessionLedger
import fr.dayview.app.DayHistoryRecord
import fr.dayview.app.InMemoryDayHistoryStore
import fr.dayview.app.NetTimeSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun record(dayKey: Long) = DayHistoryRecord(
    dayKey = dayKey,
    startMinutes = 480,
    endMinutes = 1320,
    focusIntention = "",
    busyIntervals = emptyList(),
    calendarNames = emptyMap(),
    netTimeSettings = NetTimeSettings(),
    focusPresenceIntervals = emptyList(),
    detours = emptyList(),
    cleanSessions = CleanSessionLedger(),
    pomodoroMinutes = 0,
    pomodoroEnd = null,
    goalTitle = "",
    goalDeadline = null,
    goalStart = null,
)

/** In-memory transport backed by a shared opaqueKey->blob map, mirroring the server. */
private class FakeHistoryTransport : SyncTransport {
    val blobs = mutableMapOf<String, String>()
    override suspend fun pull(): RemoteSnapshot? = null
    override suspend fun push(payload: String, expectedRevision: String?) = PushOutcome.Applied("r1")
    override suspend fun putHistoryDay(opaqueKey: String, payload: String) {
        blobs.putIfAbsent(opaqueKey, payload) // write-once
    }
    override suspend fun getHistoryDay(opaqueKey: String): String? = blobs[opaqueKey]
}

class HistorySyncTest {
    private val key = RawSyncKey(ByteArray(32) { it.toByte() })
    private fun sync(store: InMemoryDayHistoryStore, transport: SyncTransport, max: Int = 50) = HistorySync(store, transport, HistoryBlobCodec(key), HistoryKey(key), maxPerCycle = max)

    @Test
    fun uploadsLocalDaysMissingFromManifest() = runTest {
        val store = InMemoryDayHistoryStore().apply {
            write(record(1))
            write(record(2))
        }
        val transport = FakeHistoryTransport()
        val manifest = sync(store, transport).reconcile(knownManifest = emptyList())
        assertEquals(listOf(1L, 2L), manifest)
        assertEquals(2, transport.blobs.size)
    }

    @Test
    fun downloadsManifestDaysMissingLocally() = runTest {
        // Device A uploads day 5.
        val a = InMemoryDayHistoryStore().apply { write(record(5)) }
        val transport = FakeHistoryTransport()
        sync(a, transport).reconcile(knownManifest = emptyList())
        // Device B has nothing locally but sees day 5 in the manifest.
        val b = InMemoryDayHistoryStore()
        sync(b, transport).reconcile(knownManifest = listOf(5L))
        assertNotNull(b.read(5))
    }

    @Test
    fun returnsUnionOfManifestAndUploads() = runTest {
        val store = InMemoryDayHistoryStore().apply { write(record(3)) }
        val manifest = sync(store, FakeHistoryTransport()).reconcile(knownManifest = listOf(1L))
        assertEquals(listOf(1L, 3L), manifest)
    }

    @Test
    fun boundsUploadsPerCycle() = runTest {
        val store = InMemoryDayHistoryStore().apply { (1L..10L).forEach { write(record(it)) } }
        val transport = FakeHistoryTransport()
        val manifest = sync(store, transport, max = 4).reconcile(knownManifest = emptyList())
        assertEquals(4, transport.blobs.size)
        assertEquals(4, manifest.size)
    }

    @Test
    fun boundsDownloadsPerCycle() = runTest {
        // Device A uploads 10 days into the shared transport.
        val a = InMemoryDayHistoryStore().apply { (1L..10L).forEach { write(record(it)) } }
        val transport = FakeHistoryTransport()
        sync(a, transport).reconcile(knownManifest = emptyList())
        assertEquals(10, transport.blobs.size)
        // Device B has nothing locally but sees all 10 days in the manifest; one cycle is capped at 4.
        val b = InMemoryDayHistoryStore()
        sync(b, transport, max = 4).reconcile(knownManifest = (1L..10L).toList())
        assertEquals(4, b.listAllDays().size)
    }

    @Test
    fun missingRemoteBlobIsSkippedNotFatal() = runTest {
        // Manifest claims day 9 but the server never got the blob (e.g. earlier crash).
        val store = InMemoryDayHistoryStore()
        val manifest = sync(store, FakeHistoryTransport()).reconcile(knownManifest = listOf(9L))
        assertTrue(store.listAllDays().isEmpty()) // nothing written
        assertEquals(listOf(9L), manifest) // day kept for a later retry
    }
}
