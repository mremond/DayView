package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StampTest {
    @Test
    fun laterTimestampWins() {
        assertTrue(Stamp(20, "a").wins(Stamp(10, "b")))
        assertFalse(Stamp(10, "a").wins(Stamp(20, "b")))
    }

    @Test
    fun equalTimestampBreaksTieByDeviceIdLexicographically() {
        assertTrue(Stamp(10, "b").wins(Stamp(10, "a")))
        assertFalse(Stamp(10, "a").wins(Stamp(10, "b")))
    }

    @Test
    fun identicalStampDoesNotWin() {
        assertFalse(Stamp(10, "a").wins(Stamp(10, "a")))
    }
}
