package fr.dayview.app.sync

import fr.dayview.app.CleanSessionLedger
import fr.dayview.app.DayHistoryRecord
import fr.dayview.app.DayPreferences
import fr.dayview.app.DayPreferencesSnapshot
import fr.dayview.app.InMemoryDayHistoryStore
import fr.dayview.app.NetTimeSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
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

    override suspend fun putHistoryDay(opaqueKey: String, payload: String) = Unit

    override suspend fun getHistoryDay(opaqueKey: String): String? = null
}

/** Wraps a transport and records the maximum number of overlapping [push] calls it observed. */
private class ConcurrencyTrackingTransport(private val inner: SyncTransport = OneShotTransport()) : SyncTransport {
    private val inFlight = AtomicInteger(0)
    private val maxInFlight = AtomicInteger(0)
    val observedMax: Int get() = maxInFlight.get()

    override suspend fun pull(): RemoteSnapshot? = inner.pull()

    override suspend fun push(payload: String, expectedRevision: String?): PushOutcome {
        val current = inFlight.incrementAndGet()
        maxInFlight.updateAndGet { existing -> maxOf(existing, current) }
        yield()
        delay(10)
        try {
            return inner.push(payload, expectedRevision)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override suspend fun putHistoryDay(opaqueKey: String, payload: String) = inner.putHistoryDay(opaqueKey, payload)

    override suspend fun getHistoryDay(opaqueKey: String): String? = inner.getHistoryDay(opaqueKey)
}

private fun historyRecord(dayKey: Long) = DayHistoryRecord(
    dayKey = dayKey,
    startMinutes = 480,
    endMinutes = 1320,
    focusIntention = "",
    busyIntervals = emptyList(),
    calendarNames = emptyMap(),
    netTimeSettings = NetTimeSettings(),
    focusPresenceIntervals = emptyList(),
    focusSessionIntervals = emptyList(),
    detours = emptyList(),
    cleanSessions = CleanSessionLedger(),
    pomodoroMinutes = 0,
    pomodoroEnd = null,
    goalTitle = "",
    goalDeadline = null,
    goalStart = null,
)

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

    @Test
    fun concurrentSyncNowCallsDoNotOverlap() = runTest {
        val ks = InMemorySecureKeyStore().apply {
            storeKey(RawSyncKey.generate())
            storeConfig(SyncConfig("https://s", "u", "t"))
        }
        val prefs = FakePrefs(DayPreferencesSnapshot(startMinutes = 501))
        val transport = ConcurrencyTrackingTransport()
        val c = coordinator(ks, prefs, transport, this)
        List(5) { launch { c.syncNow() } }.joinAll()
        assertEquals(1, transport.observedMax)
        assertEquals(SyncStatus.Ok, c.status.first())
    }

    @Test
    fun syncNowUploadsLocalHistoryDayIntoManifest() = runTest {
        val store = InMemoryDayHistoryStore().apply { write(historyRecord(dayKey = 42)) }
        val transport = OneShotTransport()
        val ks = InMemorySecureKeyStore().apply {
            storeKey(RawSyncKey(ByteArray(32) { it.toByte() }))
            storeConfig(SyncConfig("https://x", "u1", "t"))
        }
        var stored: String? = null
        val statePersistence = FileSyncStatePersistence(read = { stored }, write = { stored = it })
        val c = SyncCoordinator(
            deviceId = "a",
            keyStore = ks,
            statePersistence = statePersistence,
            preferences = FakePrefs(DayPreferencesSnapshot()),
            transportFactory = { transport },
            codecFactory = { PlainCodec },
            scope = this.backgroundScope,
            now = { 100L },
            historyStore = store,
        )
        c.syncNow()
        assertEquals(listOf(42L), statePersistence.load().baseDocument?.historyDays)
    }
}
