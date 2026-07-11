package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarNetTimeTest {
    private fun interval(start: Long, end: Long, vararg titles: String) =
        BusyInterval(start, end, titles.toList())

    @Test
    fun mergeCombinesOverlappingAndTouchingIntervals() {
        val merged = mergeBusyIntervals(
            listOf(
                interval(300, 400, "B"),
                interval(100, 200, "A"),
                interval(200, 250, "C"), // touche A
            ),
        )
        assertEquals(2, merged.size)
        assertEquals(BusyInterval(100, 250, listOf("A", "C")), merged[0])
        assertEquals(BusyInterval(300, 400, listOf("B")), merged[1])
    }

    @Test
    fun mergeDropsEmptyOrInvertedIntervals() {
        val merged = mergeBusyIntervals(listOf(interval(500, 500), interval(700, 600)))
        assertEquals(emptyList(), merged)
    }
}
