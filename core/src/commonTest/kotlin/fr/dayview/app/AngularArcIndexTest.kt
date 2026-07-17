package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AngularArcIndexTest {
    @Test
    fun containmentAndGap() {
        assertEquals(0, angularArcIndexAt(listOf(-90.0), listOf(30.0), -75.0, 5.0))
        assertEquals(-1, angularArcIndexAt(listOf(-90.0), listOf(30.0), -20.0, 5.0))
    }

    @Test
    fun wrapsAcrossTheAnchor() {
        assertEquals(0, angularArcIndexAt(listOf(240.0), listOf(40.0), -85.0, 5.0))
    }

    @Test
    fun toleranceBoundaryIsInclusive() {
        // 3° sliver at 0°; probe 6° past the end is within a 6° tolerance, outside a 5° one.
        assertEquals(0, angularArcIndexAt(listOf(0.0), listOf(3.0), 9.0, 6.0))
        assertEquals(-1, angularArcIndexAt(listOf(0.0), listOf(3.0), 9.0, 5.0))
    }

    @Test
    fun emptyIsMinusOne() {
        assertEquals(-1, angularArcIndexAt(emptyList(), emptyList(), 0.0, 6.0))
    }

    @Test
    fun detourBodyHelperUsesSixDegreeTolerance() {
        val bodies = listOf(
            DetourBodySnapshot(startAngleDegrees = 0.0, sweepDegrees = 3.0, colorIndex = 0L, hoverLabel = "x"),
        )
        assertEquals(0, detourBodyIndexAt(bodies, 9.0)) // 6° past the end: within 6°
        assertEquals(-1, detourBodyIndexAt(bodies, 11.0)) // 8° past: outside
    }
}
