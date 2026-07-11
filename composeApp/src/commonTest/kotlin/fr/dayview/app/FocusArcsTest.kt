package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FocusArcsTest {
    private fun t(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

    // Window 0..360_000 ms maps linearly to 0..360°, starting at -90°.
    private val start = t(0L)
    private val end = t(360_000L)

    @Test
    fun intervalProjectsToExpectedAngleAndSweep() {
        val arcs = focusArcs(start, end, listOf(FocusPresenceInterval(t(0L), t(90_000L))))
        assertEquals(1, arcs.size)
        assertEquals(-90f, arcs[0].startAngleDegrees)
        assertEquals(90f, arcs[0].sweepDegrees) // 25% of the ring
    }

    @Test
    fun focusedTimeClipsToTheWindowAndSums() {
        val intervals = listOf(
            FocusPresenceInterval(t(-10_000L), t(60_000L)), // clipped start → 60s in-window
            FocusPresenceInterval(t(300_000L), t(400_000L)), // clipped end → 60s in-window
        )
        assertEquals(2.minutes, focusedTime(start, end, intervals))
    }
}
