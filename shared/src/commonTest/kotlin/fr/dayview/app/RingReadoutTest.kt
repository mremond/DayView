package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RingReadoutTest {
    @Test
    fun readoutReportsTheSessionUnderTheAngle() {
        val ws = Instant.fromEpochMilliseconds(0)
        val we = Instant.fromEpochMilliseconds(4_000)
        val rec = FocusSessionRecord(
            Instant.fromEpochMilliseconds(1_000),
            Instant.fromEpochMilliseconds(2_000),
            "x",
            FocusClosureOutcome.COMPLETED,
        )
        val bands = focusSessionBands(ws, we, listOf(rec))
        val mid = bands[0].startAngleDegrees + bands[0].sweepDegrees / 2f
        val readout = ringReadoutAt(mid, ws, we, emptyList(), emptyList(), emptyList(), bands, null)
        assertEquals(rec, readout.session)
    }
}
