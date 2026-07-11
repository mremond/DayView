package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusStartTest {
    @Test
    fun addsDurationInMinutesToNow() {
        assertEquals(1_000L + 25 * 60_000L, focusStartEndMillis(1_000L, 25))
    }

    @Test
    fun coercesDurationBelowFiveMinutesUpToFive() {
        assertEquals(5 * 60_000L, focusStartEndMillis(0L, 1))
    }

    @Test
    fun coercesDurationAboveOneEightyMinutesDownToOneEighty() {
        assertEquals(180 * 60_000L, focusStartEndMillis(0L, 500))
    }
}
