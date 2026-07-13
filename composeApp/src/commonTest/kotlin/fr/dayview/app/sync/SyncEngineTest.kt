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
) : SyncTransport {
    var pushes = 0
    override suspend fun pull() = remote
    override suspend fun push(payload: String, expectedRevision: String?): PushOutcome {
        pushes++
        // Simulates a concurrent writer beating this device to the first revision.
        if (rejectFirst && pushes == 1) return PushOutcome.Rejected(remote ?: RemoteSnapshot(payload, "r1"))
        remote = RemoteSnapshot(payload, "r${pushes + 1}")
        return PushOutcome.Applied("r${pushes + 1}")
    }
}

/** Identity codec: keeps the loop logic under test independent of real crypto. */
private object PlainCodec : PayloadCodec {
    override suspend fun encrypt(plaintext: String) = plaintext
    override suspend fun decrypt(ciphertext: String) = ciphertext
}

private object FailKeyCodec : PayloadCodec {
    override suspend fun encrypt(plaintext: String) = plaintext
    override suspend fun decrypt(ciphertext: String): String = throw SyncKeyMismatchException(null)
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
        val transport = FakeTransport(remote = null, rejectFirst = true)
        val engine = SyncEngine(transport, PlainCodec, deviceId = "a")
        val result = engine.sync(local, SyncState(null, null), now = 100)
        assertIs<SyncResult.Applied>(result)
        assertTrue(transport.pushes >= 2)
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
        }
        val result = SyncEngine(throwing, PlainCodec, deviceId = "a").sync(local, SyncState(null, null), now = 100)
        assertIs<SyncResult.Failed>(result)
    }
}
