package fr.dayview.app.sync

import fr.dayview.app.FocusContribution
import fr.dayview.app.FocusPresenceInterval
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
}
