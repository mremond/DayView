package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeOfDayInputTest {
    @Test
    fun snapMovesMisalignedValueToNearestMultipleInPressedDirection() {
        // 14:32 → "+" lands on 14:35, "−" lands on 14:30.
        assertEquals(14 * 60 + 35, snapToFive(14 * 60 + 32, +1))
        assertEquals(14 * 60 + 30, snapToFive(14 * 60 + 32, -1))
        // One minute off either side of a multiple.
        assertEquals(875, snapToFive(871, +1))
        assertEquals(870, snapToFive(874, -1))
    }

    @Test
    fun snapStepsAlignedValueByFive() {
        assertEquals(14 * 60 + 35, snapToFive(14 * 60 + 30, +1))
        assertEquals(14 * 60 + 25, snapToFive(14 * 60 + 30, -1))
        assertEquals(5, snapToFive(0, +1))
        assertEquals(-5, snapToFive(0, -1)) // call sites clamp
    }

    @Test
    fun parsesColonAndFrenchHourSeparators() {
        assertEquals(14 * 60 + 32, parseMinutesOfDay("14:32", use24Hour = true))
        assertEquals(9 * 60 + 5, parseMinutesOfDay("9:05", use24Hour = true))
        assertEquals(14 * 60 + 32, parseMinutesOfDay("14h32", use24Hour = true))
        assertEquals(14 * 60, parseMinutesOfDay("14h", use24Hour = true))
        assertEquals(14 * 60 + 32, parseMinutesOfDay("  14:32  ", use24Hour = true))
    }

    @Test
    fun parsesBareDigits() {
        assertEquals(14 * 60 + 32, parseMinutesOfDay("1432", use24Hour = true))
        assertEquals(9 * 60 + 32, parseMinutesOfDay("932", use24Hour = true))
        assertEquals(14 * 60, parseMinutesOfDay("14", use24Hour = true))
        assertEquals(9 * 60, parseMinutesOfDay("9", use24Hour = true))
        assertEquals(0, parseMinutesOfDay("0", use24Hour = true))
        assertEquals(23 * 60 + 59, parseMinutesOfDay("2359", use24Hour = true))
    }

    @Test
    fun parsesTwelveHourSuffixesOnlyInTwelveHourMode() {
        assertEquals(14 * 60 + 30, parseMinutesOfDay("2:30 pm", use24Hour = false))
        assertEquals(14 * 60 + 30, parseMinutesOfDay("2:30PM", use24Hour = false))
        assertEquals(0, parseMinutesOfDay("12am", use24Hour = false))
        assertEquals(12 * 60, parseMinutesOfDay("12pm", use24Hour = false))
        assertEquals(9 * 60 + 15, parseMinutesOfDay("9:15a", use24Hour = false))
        // Without a suffix, 12h mode still reads the text as 24-hour.
        assertEquals(14 * 60 + 30, parseMinutesOfDay("14:30", use24Hour = false))
        // Suffixes are rejected in 24-hour mode.
        assertNull(parseMinutesOfDay("2:30 pm", use24Hour = true))
    }

    @Test
    fun rejectsInvalidWallClockText() {
        assertNull(parseMinutesOfDay("", use24Hour = true))
        assertNull(parseMinutesOfDay("24:00", use24Hour = true))
        assertNull(parseMinutesOfDay("9:75", use24Hour = true))
        assertNull(parseMinutesOfDay("9:5", use24Hour = true))
        assertNull(parseMinutesOfDay("abc", use24Hour = true))
        assertNull(parseMinutesOfDay("12345", use24Hour = true))
        assertNull(parseMinutesOfDay("0am", use24Hour = false))
        assertNull(parseMinutesOfDay("13pm", use24Hour = false))
    }

    @Test
    fun parsesDurationsWithinRange() {
        assertEquals(45, parseDurationMinutes("45"))
        assertEquals(5, parseDurationMinutes("5"))
        assertEquals(90, parseDurationMinutes("1h30"))
        assertEquals(90, parseDurationMinutes("1:30"))
        assertEquals(120, parseDurationMinutes("2h"))
        assertEquals(720, parseDurationMinutes("720"))
    }

    @Test
    fun rejectsInvalidDurations() {
        assertNull(parseDurationMinutes(""))
        assertNull(parseDurationMinutes("4")) // below the 5-minute floor
        assertNull(parseDurationMinutes("721"))
        assertNull(parseDurationMinutes("13h")) // 780 min > 720
        assertNull(parseDurationMinutes("1h75"))
        assertNull(parseDurationMinutes("abc"))
    }
}
