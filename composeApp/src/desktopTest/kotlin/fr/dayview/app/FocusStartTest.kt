package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class FocusStartTest {
    private fun t(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

    @Test
    fun addsDurationInMinutesToNow() {
        assertEquals(t(1_000L + 25 * 60_000L), focusStartEnd(t(1_000L), 25))
    }

    @Test
    fun coercesDurationBelowFiveMinutesUpToFive() {
        assertEquals(t(5 * 60_000L), focusStartEnd(t(0L), 1))
    }

    @Test
    fun coercesDurationAboveOneEightyMinutesDownToOneEighty() {
        assertEquals(t(180 * 60_000L), focusStartEnd(t(0L), 500))
    }
}
