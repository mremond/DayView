package fr.dayview.app.sync

import fr.dayview.app.FocusContribution
import fr.dayview.app.FocusPresenceInterval
import fr.dayview.app.InMemoryFocusContributionStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** In-memory transport backed by a shared opaqueKey->blob map, mirroring the server. */
private class FakeFocusTransport : SyncTransport {
    val blobs = mutableMapOf<String, String>()
    override suspend fun pull(): RemoteSnapshot? = null
    override suspend fun push(payload: String, expectedRevision: String?) = PushOutcome.Applied("r1")
    override suspend fun putHistoryDay(opaqueKey: String, payload: String) {
        blobs.putIfAbsent(opaqueKey, payload) // write-once
    }
    override suspend fun getHistoryDay(opaqueKey: String): String? = blobs[opaqueKey]

    fun seedFocus(opaqueKey: String, blob: String) {
        blobs[opaqueKey] = blob
    }
}

class FocusContributionSyncTest {
    private fun iv(s: Long, e: Long) = FocusPresenceInterval(Instant.fromEpochMilliseconds(s), Instant.fromEpochMilliseconds(e))
    private val key = RawSyncKey(ByteArray(32) { 7 })

    @Test
    fun uploadsOwnContributionsAndAddsThemToManifest() = runTest {
        val transport = FakeFocusTransport()
        val store = InMemoryFocusContributionStore().apply {
            write(FocusContribution(20260, "self", emptyList(), listOf(iv(0, 10))))
        }
        val sync = FocusContributionSync(store, transport, HistoryBlobCodec(key), HistoryKey(key), deviceId = "self")
        val manifest = sync.reconcile(emptyList())
        assertEquals(listOf("20260:self"), manifest)
    }

    @Test
    fun downloadsForeignContributionsIntoTheStore() = runTest {
        val transport = FakeFocusTransport()
        // Seed the server with device "other"'s blob under opaqueFocusKey(20260, "other").
        val other = FocusContribution(20260, "other", emptyList(), listOf(iv(20, 30)))
        transport.seedFocus(
            HistoryKey(key).opaqueFocusKey(20260, "other"),
            HistoryBlobCodec(key).encryptFocus(20260, "other", FocusContributionMapper.serialize(other)),
        )
        val store = InMemoryFocusContributionStore()
        val sync = FocusContributionSync(store, transport, HistoryBlobCodec(key), HistoryKey(key), deviceId = "self")
        sync.reconcile(listOf("20260:other"))
        assertEquals(other, store.read(20260, "other"))
    }

    @Test
    fun skipsDownloadOfOwnDeviceEntries() = runTest {
        val transport = FakeFocusTransport()
        val store = InMemoryFocusContributionStore()
        val sync = FocusContributionSync(store, transport, HistoryBlobCodec(key), HistoryKey(key), deviceId = "self")
        sync.reconcile(listOf("20260:self")) // our own entry, nothing to fetch
        assertTrue(store.listKeys().isEmpty())
    }
}
