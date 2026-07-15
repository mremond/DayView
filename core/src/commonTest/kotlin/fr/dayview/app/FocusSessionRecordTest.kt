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
    fun skipsMalformedAndUnknownOutcomeLines() {
        // 4 fields required; bad instant, then unknown outcome name, then a good line.
        val blob = "@1\nxxx,2,COMPLETED,hi\n1,2,NOPE,hi\n5,6,COMPLETED,b2s="
        val decoded = decodeFocusSessionRecords(blob)
        assertEquals(listOf(FocusSessionRecord(at(5), at(6), "ok", FocusClosureOutcome.COMPLETED)), decoded)
    }
}
