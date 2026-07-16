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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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

/** A server that already holds [document]; the first pull returns it so first-sync triggers. */
private class ExistingServerTransport(document: SyncDocument) : SyncTransport {
    var remote: RemoteSnapshot? = RemoteSnapshot(document.encodeToString(), "r1")
    var pushes = 0

    override suspend fun pull(): RemoteSnapshot? = remote

    override suspend fun push(payload: String, expectedRevision: String?): PushOutcome {
        pushes++
        remote = RemoteSnapshot(payload, "r${pushes + 1}")
        return PushOutcome.Applied("r${pushes + 1}")
    }

    override suspend fun putHistoryDay(opaqueKey: String, payload: String) = Unit

    override suspend fun getHistoryDay(opaqueKey: String): String? = null
}

private class AuthenticationFailureTransport : SyncTransport {
    var pulls = 0

    override suspend fun pull(): RemoteSnapshot? {
        pulls++
        throw SyncAuthenticationException()
    }

    override suspend fun push(payload: String, expectedRevision: String?): PushOutcome = error("not reached")

    override suspend fun putHistoryDay(opaqueKey: String, payload: String) = Unit

    override suspend fun getHistoryDay(opaqueKey: String): String? = null
}

/** Fails the next [failuresRemaining] pulls with a connection-style error, then succeeds. */
private class FlakyTransport(var failuresRemaining: Int) : SyncTransport {
    var pulls = 0
    private var pushes = 0

    override suspend fun pull(): RemoteSnapshot? {
        pulls++
        if (failuresRemaining > 0) {
            failuresRemaining--
            throw IllegalStateException("connection refused")
        }
        return null
    }

    override suspend fun push(payload: String, expectedRevision: String?): PushOutcome {
        pushes++
        return PushOutcome.Applied("r$pushes")
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
    focusSessionRecords = emptyList(),
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

    private fun configuredKeyStore() = InMemorySecureKeyStore().apply {
        storeKey(RawSyncKey.generate())
        storeConfig(SyncConfig("https://s", "u", "t"))
    }

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
    fun verifyPerformsFirstSyncOnlyAfterReadProbeSucceeds() = runTest {
        val ks = InMemorySecureKeyStore().apply {
            storeKey(RawSyncKey.generate())
            storeConfig(SyncConfig("https://s", "u", "t"))
        }
        val transport = OneShotTransport()
        val c = coordinator(ks, FakePrefs(DayPreferencesSnapshot()), transport, this)

        assertEquals(SyncSetupResult.Success, c.verifyAndSync())
        assertEquals(1, transport.pushes)
        assertEquals(SyncStatus.Ok, c.status.first())
    }

    @Test
    fun verifyReportsAuthenticationSeparately() = runTest {
        val ks = InMemorySecureKeyStore().apply {
            storeKey(RawSyncKey.generate())
            storeConfig(SyncConfig("https://s", "u", "bad"))
        }
        val c = coordinator(ks, FakePrefs(DayPreferencesSnapshot()), AuthenticationFailureTransport(), this)

        assertEquals(SyncSetupResult.AuthenticationFailed, c.verifyAndSync())
        assertEquals(SyncStatus.Failed, c.status.first())
    }

    @Test
    fun firstSyncAgainstExistingServerStopsForChoice() = runTest {
        val ks = InMemorySecureKeyStore().apply {
            storeKey(RawSyncKey.generate())
            storeConfig(SyncConfig("https://s", "u", "t"))
        }
        val transport = ExistingServerTransport(sampleDocument(deviceId = "other", at = 50))
        val c = coordinator(ks, FakePrefs(DayPreferencesSnapshot()), transport, this)

        c.syncNow()

        assertEquals(SyncStatus.NeedsChoice, c.status.first())
        assertEquals(true, c.firstSyncChoicePending.first())
        assertEquals(0, transport.pushes)
    }

    @Test
    fun resolveFirstSyncAdoptServerAppliesRemoteAndClearsPending() = runTest {
        val ks = InMemorySecureKeyStore().apply {
            storeKey(RawSyncKey.generate())
            storeConfig(SyncConfig("https://s", "u", "t"))
        }
        val prefs = FakePrefs(DayPreferencesSnapshot())
        val transport = ExistingServerTransport(sampleDocument(deviceId = "other", at = 50))
        val c = coordinator(ks, prefs, transport, this)
        c.syncNow()

        c.resolveFirstSync(FirstSyncStrategy.AdoptServer)

        assertEquals(SyncStatus.Ok, c.status.first())
        assertEquals(false, c.firstSyncChoicePending.first())
        assertEquals("Ship", prefs.state.value.goalTitle) // server goal retrieved
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
    fun transientFailureSchedulesAutomaticRetry() = runTest {
        val transport = FlakyTransport(failuresRemaining = 1)
        val c = coordinator(configuredKeyStore(), FakePrefs(DayPreferencesSnapshot()), transport, backgroundScope)

        c.syncNow()
        assertEquals(SyncStatus.Failed, c.status.first())
        assertEquals(1, transport.pulls)

        // The retry fires 15s later and succeeds without any external trigger.
        testScheduler.advanceTimeBy(15.seconds)
        testScheduler.runCurrent()
        assertEquals(2, transport.pulls)
        assertEquals(SyncStatus.Ok, c.status.first())

        // Once healthy, nothing else is scheduled.
        testScheduler.advanceTimeBy(1.hours)
        testScheduler.runCurrent()
        assertEquals(2, transport.pulls)
    }

    @Test
    fun authenticationFailureIsNotRetried() = runTest {
        val transport = AuthenticationFailureTransport()
        val c = coordinator(configuredKeyStore(), FakePrefs(DayPreferencesSnapshot()), transport, backgroundScope)

        c.syncNow()
        assertEquals(SyncStatus.Failed, c.status.first())

        testScheduler.advanceTimeBy(1.hours)
        testScheduler.runCurrent()
        assertEquals(1, transport.pulls)
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

    @Test
    fun backoffGrowsToFiveMinuteCap() = runTest {
        val transport = FlakyTransport(failuresRemaining = Int.MAX_VALUE)
        val c = coordinator(configuredKeyStore(), FakePrefs(DayPreferencesSnapshot()), transport, backgroundScope)

        c.syncNow() // failure #1 schedules the first retry
        var pulls = 1
        assertEquals(pulls, transport.pulls)

        val expectedDelays = listOf(15.seconds, 30.seconds, 1.minutes, 2.minutes, 5.minutes, 5.minutes, 5.minutes)
        for (expected in expectedDelays) {
            // Just before the deadline nothing has fired…
            testScheduler.advanceTimeBy(expected - 1.seconds)
            testScheduler.runCurrent()
            assertEquals(pulls, transport.pulls)
            // …and at the deadline exactly one retry runs.
            testScheduler.advanceTimeBy(1.seconds)
            testScheduler.runCurrent()
            pulls++
            assertEquals(pulls, transport.pulls)
        }
    }

    @Test
    fun successResetsBackoffProgression() = runTest {
        val transport = FlakyTransport(failuresRemaining = 2)
        val c = coordinator(configuredKeyStore(), FakePrefs(DayPreferencesSnapshot()), transport, backgroundScope)

        c.syncNow() // failure #1
        testScheduler.advanceTimeBy(15.seconds)
        testScheduler.runCurrent() // failure #2 → next delay would be 30s
        testScheduler.advanceTimeBy(30.seconds)
        testScheduler.runCurrent() // success
        assertEquals(SyncStatus.Ok, c.status.first())
        assertEquals(3, transport.pulls)

        // A fresh failure streak starts back at 15s, not at the next step.
        transport.failuresRemaining = 1
        c.syncNow() // failure
        assertEquals(4, transport.pulls)
        testScheduler.advanceTimeBy(15.seconds)
        testScheduler.runCurrent() // retry succeeds
        assertEquals(5, transport.pulls)
        assertEquals(SyncStatus.Ok, c.status.first())
    }

    @Test
    fun manualSyncReplacesPendingRetry() = runTest {
        val transport = FlakyTransport(failuresRemaining = 1)
        val c = coordinator(configuredKeyStore(), FakePrefs(DayPreferencesSnapshot()), transport, backgroundScope)

        c.syncNow() // fails, retry pending at +15s
        assertEquals(1, transport.pulls)

        c.syncNow() // manual trigger succeeds and cancels the pending retry
        assertEquals(2, transport.pulls)
        assertEquals(SyncStatus.Ok, c.status.first())

        testScheduler.advanceTimeBy(1.hours)
        testScheduler.runCurrent()
        assertEquals(2, transport.pulls) // the 15s retry never fired
    }
}
