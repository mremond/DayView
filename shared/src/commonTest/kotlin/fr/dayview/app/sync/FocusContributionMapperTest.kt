package fr.dayview.app.sync

import fr.dayview.app.FocusClosureOutcome
import fr.dayview.app.FocusContribution
import fr.dayview.app.FocusPresenceInterval
import fr.dayview.app.FocusSessionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class FocusContributionMapperTest {
    private fun iv(s: Long, e: Long) = FocusPresenceInterval(Instant.fromEpochMilliseconds(s), Instant.fromEpochMilliseconds(e))

    @Test
    fun roundTripsThroughJson() {
        val c = FocusContribution(20260, "dev1", listOf(iv(1, 2)), listOf(iv(3, 4), iv(5, 6)))
        val back = FocusContributionMapper.deserialize(FocusContributionMapper.serialize(c))
        assertEquals(c, back)
    }

    @Test
    fun malformedJsonDeserializesToNull() {
        assertNull(FocusContributionMapper.deserialize("{ not json"))
    }

    @Test
    fun roundTripsRecords() {
        val c = FocusContribution(
            dayKey = 5L,
            deviceId = "d",
            presence = emptyList(),
            session = emptyList(),
            records = listOf(
                FocusSessionRecord(
                    Instant.fromEpochMilliseconds(1),
                    Instant.fromEpochMilliseconds(2),
                    "x",
                    FocusClosureOutcome.COMPLETED,
                ),
            ),
        )
        val decoded = FocusContributionMapper.deserialize(FocusContributionMapper.serialize(c))
        assertEquals(c.records, decoded?.records)
    }
}
