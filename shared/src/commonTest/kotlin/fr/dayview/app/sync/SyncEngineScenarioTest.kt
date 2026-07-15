package fr.dayview.app.sync

import fr.dayview.app.DayPreferencesSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * End-to-end sync scenarios across two devices sharing one server. These cover the pull → merge →
 * apply → persist round-trip that per-field [SyncDocument.merge] unit tests miss: several real bugs
 * (fresh-device clobber, empty-goal clobber, and an UpToDate short-circuit that dropped pulled
 * changes) produced a correct merge yet the wrong end state because of how the *engine* used it.
 */
class SyncEngineScenarioTest {
    /** A single shared document with compare-and-swap on revision, like the real HTTP backend. */
    private class SharedServer {
        var snapshot: RemoteSnapshot? = null
        var pushes = 0
            private set
        private var rev = 0

        fun transport(): SyncTransport = object : SyncTransport {
            override suspend fun pull(): RemoteSnapshot? = snapshot

            override suspend fun push(payload: String, expectedRevision: String?): PushOutcome {
                val current = snapshot
                return if (expectedRevision == current?.revision) {
                    pushes++
                    rev++
                    RemoteSnapshot(payload, "r$rev").also { snapshot = it }
                    PushOutcome.Applied("r$rev")
                } else {
                    PushOutcome.Rejected(current!!)
                }
            }

            override suspend fun putHistoryDay(opaqueKey: String, payload: String) = Unit
            override suspend fun getHistoryDay(opaqueKey: String): String? = null
        }
    }

    /** One device: its local prefs and persisted sync state, mirroring what SyncCoordinator does. */
    private class Device(val id: String, private val server: SharedServer) {
        var prefs = DayPreferencesSnapshot()
        var state = SyncState(null, null)

        suspend fun sync(now: Long, strategy: FirstSyncStrategy? = null): SyncResult {
            val result = SyncEngine(server.transport(), PlainCodec, deviceId = id).sync(prefs, state, now, strategy)
            if (result is SyncResult.Applied) {
                prefs = result.snapshot
                state = result.state
            }
            return result
        }
    }

    private fun DayPreferencesSnapshot.withGoal(title: String) = copy(goalTitle = title, goalDeadline = Instant.fromEpochMilliseconds(2000), goalStart = Instant.fromEpochMilliseconds(1500))

    private fun DayPreferencesSnapshot.withoutGoal() = copy(goalTitle = "", goalDeadline = null, goalStart = null)

    @Test
    fun secondDeviceAdoptsGoalSetByFirstAfterBothConverged() = runTest {
        val server = SharedServer()
        val a = Device("a", server)
        val b = Device("b", server)

        a.sync(now = 1) // push A's defaults
        b.sync(now = 2, strategy = FirstSyncStrategy.Merge) // B's first sync establishes its base

        a.prefs = a.prefs.withGoal("Ship")
        a.sync(now = 3) // push the goal

        val result = b.sync(now = 4) // B has nothing to push; it must still pull the goal
        assertEquals("Ship", b.prefs.goalTitle)
        assertTrue(result is SyncResult.Applied) // adopted, not a silent UpToDate
    }

    @Test
    fun freshSecondDeviceAsksThenAdoptsServerGoal() = runTest {
        val server = SharedServer()
        val a = Device("a", server)
        val b = Device("b", server)
        a.prefs = a.prefs.withGoal("Ship")
        a.sync(now = 1)

        assertIs<SyncResult.FirstSyncChoiceNeeded>(b.sync(now = 2)) // stops and asks
        b.sync(now = 3, strategy = FirstSyncStrategy.AdoptServer)
        assertEquals("Ship", b.prefs.goalTitle)
    }

    @Test
    fun freshSecondDeviceMergeKeepsServerGoal() = runTest {
        val server = SharedServer()
        val a = Device("a", server)
        val b = Device("b", server)
        a.prefs = a.prefs.withGoal("Ship")
        a.sync(now = 1)

        b.sync(now = 2) // choice
        b.sync(now = 3, strategy = FirstSyncStrategy.Merge)
        assertEquals("Ship", b.prefs.goalTitle) // tombstone rule protects it even under Merge
    }

    @Test
    fun emptyDeviceWithHigherClockDoesNotClobberGoal() = runTest {
        val server = SharedServer()
        val a = Device("a", server)
        val b = Device("b", server)
        a.prefs = a.prefs.withGoal("Ship")
        a.sync(now = 5) // goal stamped at a low clock

        // B's clock runs far ahead; its empty default must not out-timestamp and wipe A's goal.
        b.sync(now = 1000) // choice
        b.sync(now = 1001, strategy = FirstSyncStrategy.Merge)
        assertEquals("Ship", b.prefs.goalTitle)
        a.sync(now = 6)
        assertEquals("Ship", a.prefs.goalTitle) // server still holds it
    }

    @Test
    fun clearingGoalPropagatesToOtherDevice() = runTest {
        val server = SharedServer()
        val a = Device("a", server)
        val b = Device("b", server)
        a.prefs = a.prefs.withGoal("Ship")
        a.sync(now = 1)
        b.sync(now = 2, strategy = FirstSyncStrategy.AdoptServer)
        assertEquals("Ship", b.prefs.goalTitle)

        a.prefs = a.prefs.withoutGoal() // deliberate deletion
        a.sync(now = 3)

        b.sync(now = 4)
        assertEquals("", b.prefs.goalTitle) // the clear reaches B, not just a no-op
    }

    @Test
    fun concurrentGoalEditsConvergeCommutatively() = runTest {
        val server = SharedServer()
        val a = Device("a", server)
        val b = Device("b", server)
        a.sync(now = 1)
        b.sync(now = 2, strategy = FirstSyncStrategy.Merge)

        // Both edit the goal without seeing each other, then sync in an interleaved order.
        a.prefs = a.prefs.withGoal("Alpha")
        b.prefs = b.prefs.withGoal("Beta")
        a.sync(now = 10)
        b.sync(now = 20) // rejected against A's newer revision, retries and merges
        a.sync(now = 30) // pulls the converged doc

        assertEquals(a.prefs.goalTitle, b.prefs.goalTitle)
        assertEquals("Beta", a.prefs.goalTitle) // later stamp wins deterministically
    }

    @Test
    fun reSyncAfterConvergenceIsUpToDateAndDoesNotPush() = runTest {
        val server = SharedServer()
        val a = Device("a", server)
        a.prefs = a.prefs.withGoal("Ship")
        a.sync(now = 1)
        val pushesAfterFirst = server.pushes

        val again = a.sync(now = 2) // nothing changed anywhere
        assertIs<SyncResult.UpToDate>(again)
        assertEquals(pushesAfterFirst, server.pushes) // no redundant push
    }
}
