package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
