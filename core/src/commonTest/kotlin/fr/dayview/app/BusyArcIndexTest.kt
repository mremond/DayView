package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class BusyArcIndexTest {
    private fun arc(start: Double, sweep: Double) = BusyArcSnapshot(startAngleDegrees = start, sweepDegrees = sweep, colorIndex = 0L, hoverLabel = "x")

    @Test
    fun findsTheContainingArc() {
        assertEquals(0, busyArcIndexAt(listOf(arc(-90.0, 30.0)), -75.0))
    }

    @Test
    fun missesTheGaps() {
        assertEquals(-1, busyArcIndexAt(listOf(arc(-90.0, 30.0)), -20.0))
    }

    @Test
    fun boundariesAreInclusive() {
        val arcs = listOf(arc(-90.0, 30.0))
        assertEquals(0, busyArcIndexAt(arcs, -90.0)) // start edge
        assertEquals(0, busyArcIndexAt(arcs, -60.0)) // end edge
    }

    @Test
    fun wrapsAcrossTheAnchor() {
        // 240° + 40° sweep crosses 270° (= the -90° anchor) and reaches -80°.
        val arcs = listOf(arc(240.0, 40.0))
        assertEquals(0, busyArcIndexAt(arcs, -85.0)) // == 275°, inside the wrapped tail
        assertEquals(-1, busyArcIndexAt(arcs, -60.0)) // == 300°, past the tail
    }

    @Test
    fun emptyListReturnsMinusOne() {
        assertEquals(-1, busyArcIndexAt(emptyList(), 0.0))
    }

    @Test
    fun firstOfOverlappingArcsWins() {
        assertEquals(0, busyArcIndexAt(listOf(arc(0.0, 60.0), arc(30.0, 60.0)), 45.0))
    }

    @Test
    fun shortArcGetsAHoverMargin() {
        val arcs = listOf(arc(0.0, 2.0)) // a ~10-minute sliver
        assertEquals(0, busyArcIndexAt(arcs, 5.0)) // 3° past the end: within the margin
        assertEquals(-1, busyArcIndexAt(arcs, 10.0)) // 8° past: outside
    }

    @Test
    fun nearestArcWinsWithinTheMargin() {
        val arcs = listOf(arc(0.0, 10.0), arc(20.0, 10.0))
        // 16°: 6° past the first arc's end, 4° before the second's start.
        assertEquals(1, busyArcIndexAt(arcs, 16.0))
    }
}
