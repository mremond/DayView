package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class FocusSessionRecordTest {
    private fun at(ms: Long) = Instant.fromEpochMilliseconds(ms)

    @Test
    fun roundTripsRecordsIncludingSeparatorsInIntention() {
        val records = listOf(
            FocusSessionRecord(at(1_000), at(2_000), "Write, spec\nline two", FocusClosureOutcome.COMPLETED),
            FocusSessionRecord(at(3_000), at(4_000), "", FocusClosureOutcome.TO_RESUME),
        )
        val decoded = decodeFocusSessionRecords(encodeFocusSessionRecords(records))
        assertEquals(records, decoded)
    }

    @Test
    fun emptyEncodesAndDecodesToEmpty() {
        assertEquals(emptyList(), decodeFocusSessionRecords(encodeFocusSessionRecords(emptyList())))
        assertEquals(emptyList(), decodeFocusSessionRecords(""))
    }

    @Test
    fun roundTripsANullOutcomeAsNull() {
        // A Stop-button abort records no outcome; it must survive encode -> decode as null.
        val records = listOf(FocusSessionRecord(at(7_000), at(8_000), "aborted", null))
        val decoded = decodeFocusSessionRecords(encodeFocusSessionRecords(records))
        assertEquals(records, decoded)
    }

    @Test
    fun skipsMalformedAndUnknownOutcomeLines() {
        // 4 fields required; bad instant, then unknown outcome name, then a good line.
        val blob = "@1\nxxx,2,COMPLETED,hi\n1,2,NOPE,hi\n5,6,COMPLETED,b2s="
        val decoded = decodeFocusSessionRecords(blob)
        assertEquals(listOf(FocusSessionRecord(at(5), at(6), "ok", FocusClosureOutcome.COMPLETED)), decoded)
    }

    @Test
    fun engagedAndDeepFocusClipToTheSessionWindow() {
        val rec = FocusSessionRecord(at(0), at(1_000), "x", FocusClosureOutcome.COMPLETED)
        val session = listOf(FocusPresenceInterval(at(0), at(600)), FocusPresenceInterval(at(2_000), at(3_000)))
        val presence = listOf(FocusPresenceInterval(at(100), at(400)))
        assertEquals(600, engagedTimeForSession(rec, session).inWholeMilliseconds)
        assertEquals(300, deepFocusTimeForSession(rec, presence).inWholeMilliseconds)
    }

    @Test
    fun bandSpansWholeSessionWindowAndIsHitByItsAngle() {
        val ws = at(0)
        val we = at(4_000)
        val rec = FocusSessionRecord(at(1_000), at(2_000), "x", FocusClosureOutcome.COMPLETED)
        val bands = focusSessionBands(ws, we, listOf(rec))
        assertEquals(1, bands.size)
        val mid = bands[0].startAngleDegrees + bands[0].sweepDegrees / 2f
        assertEquals(rec, focusSessionBandAtAngle(bands, mid)?.record)
        // An angle far from the band misses.
        assertEquals(null, focusSessionBandAtAngle(bands, bands[0].startAngleDegrees + 180f))
    }
}
