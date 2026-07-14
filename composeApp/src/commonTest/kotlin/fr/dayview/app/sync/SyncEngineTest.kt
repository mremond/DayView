package fr.dayview.app.sync

import fr.dayview.app.DayPreferencesSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeTransport(
    var remote: RemoteSnapshot?,
    private val rejectFirst: Boolean = false,
    /** Newer remote document installed by the "concurrent writer" that causes the first push to be rejected. */
    private val rejectWithRemote: RemoteSnapshot? = null,
) : SyncTransport {
    var pushes = 0
    override suspend fun pull() = remote
    override suspend fun push(payload: String, expectedRevision: String?): PushOutcome {
        pushes++
        // Simulates a concurrent writer beating this device to the first revision, publishing
        // a newer remote document that the engine must pull and merge against on retry.
        if (rejectFirst && pushes == 1) {
            val rejected = rejectWithRemote ?: remote ?: RemoteSnapshot(payload, "r1")
            remote = rejected
            return PushOutcome.Rejected(rejected)
        }
        remote = RemoteSnapshot(payload, "r${pushes + 1}")
        return PushOutcome.Applied("r${pushes + 1}")
    }

    override suspend fun putHistoryDay(opaqueKey: String, payload: String) = Unit

    override suspend fun getHistoryDay(opaqueKey: String): String? = null
}

class SyncEngineTest {
    private val local = DayPreferencesSnapshot(startMinutes = 500)

    @Test
    fun firstSyncPushesAndReturnsApplied() = runTest {
        val transport = FakeTransport(remote = null)
        val engine = SyncEngine(transport, PlainCodec, deviceId = "a")
        val result = engine.sync(local, SyncState(null, null), now = 100)
        assertIs<SyncResult.Applied>(result)
        assertEquals(1, transport.pushes)
        assertEquals(500, result.snapshot.startMinutes)
    }

    @Test
    fun rejectedPushRetriesAgainstNewerRemote() = runTest {
        // A conflicting remote document, stamped older than `now` everywhere except for a single
        // field carrying a distinctive value stamped strictly newer, so it alone must win the merge.
        val newerRemoteDoc = sampleDocument(deviceId = "other", at = 50).copy(
            focusIntention = Versioned("remote-wins", Stamp(at = 500, by = "remote")),
        )
        val rejectingRemote = RemoteSnapshot(newerRemoteDoc.encodeToString(), "r-conflict")
        val transport = FakeTransport(remote = null, rejectFirst = true, rejectWithRemote = rejectingRemote)
        val engine = SyncEngine(transport, PlainCodec, deviceId = "a")

        val result = engine.sync(local, SyncState(null, null), now = 100)

        assertIs<SyncResult.Applied>(result)
        assertTrue(transport.pushes >= 2)
        // Proves the retry actually pulled and merged against the newer remote document, not the
        // stale one seen before the rejection.
        assertEquals("remote-wins", result.snapshot.focusIntention)
        assertEquals("remote-wins", result.state.baseDocument?.focusIntention?.value)
    }

    @Test
    fun decryptFailureReturnsKeyError() = runTest {
        val transport = FakeTransport(remote = RemoteSnapshot("cipher", "r1"))
        val engine = SyncEngine(transport, FailKeyCodec, deviceId = "a")
        assertIs<SyncResult.KeyError>(engine.sync(local, SyncState(null, null), now = 100))
        assertEquals(0, transport.pushes) // never pushed
    }

    @Test
    fun transportErrorReturnsFailed() = runTest {
        val throwing = object : SyncTransport {
            override suspend fun pull(): RemoteSnapshot? = throw RuntimeException("network")
            override suspend fun push(payload: String, expectedRevision: String?) = throw RuntimeException("network")
            override suspend fun putHistoryDay(opaqueKey: String, payload: String) = throw RuntimeException("network")
            override suspend fun getHistoryDay(opaqueKey: String): String? = throw RuntimeException("network")
        }
        val result = SyncEngine(throwing, PlainCodec, deviceId = "a").sync(local, SyncState(null, null), now = 100)
        assertIs<SyncResult.Failed>(result)
    }

    @Test
    fun staleBaseRevisionWithEmptyRemoteCreatesInsteadOfFailing() = runTest {
        // The persisted state carries a stale baseRevision (e.g. from a prior sync against a
        // different server/user), but the remote is empty (pull() returns null). The push must
        // be a create (expectedRevision = null, If-None-Match: *), never an If-Match against the
        // stale revision -- that would 412 forever with no self-recovery.
        var recordedExpectedRevision: String? = "not-recorded"
        val transport = object : SyncTransport {
            override suspend fun pull(): RemoteSnapshot? = null

            override suspend fun push(payload: String, expectedRevision: String?): PushOutcome {
                recordedExpectedRevision = expectedRevision
                return PushOutcome.Applied("1")
            }

            override suspend fun putHistoryDay(opaqueKey: String, payload: String) = Unit

            override suspend fun getHistoryDay(opaqueKey: String): String? = null
        }
        val engine = SyncEngine(transport, PlainCodec, deviceId = "a")

        val result = engine.sync(local, SyncState(baseRevision = "5", baseDocument = null), now = 100)

        assertIs<SyncResult.Applied>(result)
        assertEquals(null, recordedExpectedRevision)
    }

    @Test
    fun upToDateWhenRemoteAlreadyMatchesReturnsUpToDateAndDoesNotPush() = runTest {
        // Converge once: first sync against an empty server establishes the steady-state document.
        val firstTransport = FakeTransport(remote = null)
        val firstResult = SyncEngine(firstTransport, PlainCodec, deviceId = "a")
            .sync(local, SyncState(null, null), now = 100)
        assertIs<SyncResult.Applied>(firstResult)
        val converged = firstResult.state.baseDocument!!

        // Second sync: the server already holds exactly the converged document and local hasn't
        // changed, so no encrypt/push should happen.
        val secondTransport = FakeTransport(remote = RemoteSnapshot(converged.encodeToString(), "r1"))
        val secondResult = SyncEngine(secondTransport, PlainCodec, deviceId = "a")
            .sync(local, SyncState("r1", converged), now = 100)

        assertIs<SyncResult.UpToDate>(secondResult)
        assertEquals(0, secondTransport.pushes)
    }
}
