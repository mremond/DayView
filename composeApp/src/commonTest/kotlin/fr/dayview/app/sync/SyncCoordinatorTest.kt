package fr.dayview.app.sync

import fr.dayview.app.DayPreferences
import fr.dayview.app.DayPreferencesSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakePrefs(initial: DayPreferencesSnapshot) : DayPreferences {
    val state = MutableStateFlow(initial)
    override val snapshots = state

    override suspend fun persist(snapshot: DayPreferencesSnapshot) {
        state.value = snapshot
    }
}

private class OneShotTransport : SyncTransport {
    var pushes = 0

    override suspend fun pull(): RemoteSnapshot? = null

    override suspend fun push(payload: String, expectedRevision: String?): PushOutcome {
        pushes++
        return PushOutcome.Applied("r1")
    }
}

class SyncCoordinatorTest {
    private fun coordinator(
        keyStore: SecureKeyStore,
        prefs: DayPreferences,
        transport: SyncTransport,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = SyncCoordinator(
        deviceId = "a",
        keyStore = keyStore,
        statePersistence = FileSyncStatePersistence(read = { null }, write = {}),
        preferences = prefs,
        transportFactory = { transport },
        codecFactory = { PlainCodec },
        scope = scope,
        now = { 1000L },
    )

    @Test
    fun notConfiguredWhenNoKey() = runTest {
        val c = coordinator(InMemorySecureKeyStore(), FakePrefs(DayPreferencesSnapshot()), OneShotTransport(), this)
        c.syncNow()
        assertEquals(SyncStatus.NotConfigured, c.status.first())
    }

    @Test
    fun syncsAndPersistsWhenConfigured() = runTest {
        val ks = InMemorySecureKeyStore().apply {
            storeKey(RawSyncKey.generate())
            storeConfig(SyncConfig("https://s", "u", "t"))
        }
        val prefs = FakePrefs(DayPreferencesSnapshot(startMinutes = 501))
        val transport = OneShotTransport()
        val c = coordinator(ks, prefs, transport, this)
        c.syncNow()
        assertEquals(SyncStatus.Ok, c.status.first())
        assertEquals(1, transport.pushes)
    }
}
