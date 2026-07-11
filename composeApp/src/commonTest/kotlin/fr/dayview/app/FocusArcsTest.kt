package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusArcsTest {
    // Window 0..360_000 ms maps linearly to 0..360°, starting at -90°.
    private val start = 0L
    private val end = 360_000L

    @Test
    fun intervalProjectsToExpectedAngleAndSweep() {
        val arcs = focusArcs(start, end, listOf(FocusPresenceInterval(0L, 90_000L)))
        assertEquals(1, arcs.size)
        assertEquals(-90f, arcs[0].startAngleDegrees)
        assertEquals(90f, arcs[0].sweepDegrees) // 25% of the ring
    }

    @Test
    fun focusedMillisClipsToTheWindowAndSums() {
        val intervals = listOf(
            FocusPresenceInterval(-10_000L, 60_000L), // clipped start → 60s in-window
            FocusPresenceInterval(300_000L, 400_000L), // clipped end → 60s in-window
        )
        assertEquals(120_000L, focusedMillis(start, end, intervals))
    }
}
