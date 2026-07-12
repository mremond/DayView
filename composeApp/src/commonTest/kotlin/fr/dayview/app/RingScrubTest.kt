package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun ms(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

class RingScrubTest {
    private val windowStart = ms(0L)
    private val windowEnd = ms(24L * 60 * 60 * 1000) // 24 h: angle = -90 + 360 * fraction

    @Test
    fun emptyRingReportsOnlyTimeOfDay() {
        val r = ringReadoutAt(
            angleDegrees = 0f, // fraction .25 -> 06:00
            windowStart = windowStart,
            windowEnd = windowEnd,
            busyBlockArcs = emptyList(),
            detourBodies = emptyList(),
            focusArcs = emptyList(),
            momentAngleDegrees = null,
        )
        assertEquals(ms(6L * 3_600_000), r.time)
        assertFalse(r.isNow)
        assertNull(r.busy)
        assertNull(r.detour)
        assertFalse(r.focus)
    }

    @Test
    fun busyArcUnderAngleIsReported() {
        val arc = BusyBlockArc(
            startAngleDegrees = -5f,
            sweepDegrees = 10f,
            colorIndex = 0,
            titles = listOf("Standup"),
            calendarName = "Work",
        )
        val r = ringReadoutAt(
            0f,
            windowStart,
            windowEnd,
            busyBlockArcs = listOf(arc),
            detourBodies = emptyList(),
            focusArcs = emptyList(),
            momentAngleDegrees = null,
        )
        assertEquals(arc, r.busy)
    }

    @Test
    fun detourUnderAngleIsReported() {
        val episodes = listOf(DetourEpisode(ms(5L * 3_600_000), ms(7L * 3_600_000), "Slack"))
        val bodies = detourBodies(windowStart, windowEnd, episodes)
        val body = bodies.first()
        val r = ringReadoutAt(
            body.angleDegrees,
            windowStart,
            windowEnd,
            busyBlockArcs = emptyList(),
            detourBodies = bodies,
            focusArcs = emptyList(),
            momentAngleDegrees = null,
        )
        assertEquals(body, r.detour)
    }

    @Test
    fun focusArcUnderAngleSetsFocus() {
        val focus = FocusArc(startAngleDegrees = -10f, sweepDegrees = 20f)
        val r = ringReadoutAt(
            0f,
            windowStart,
            windowEnd,
            busyBlockArcs = emptyList(),
            detourBodies = emptyList(),
            focusArcs = listOf(focus),
            momentAngleDegrees = null,
        )
        assertTrue(r.focus)
    }

    @Test
    fun angleNearMomentMarkerIsNow() {
        val near = ringReadoutAt(
            0f,
            windowStart,
            windowEnd,
            emptyList(),
            emptyList(),
            emptyList(),
            momentAngleDegrees = 1f,
        )
        assertTrue(near.isNow)
        val far = ringReadoutAt(
            0f,
            windowStart,
            windowEnd,
            emptyList(),
            emptyList(),
            emptyList(),
            momentAngleDegrees = 90f,
        )
        assertFalse(far.isNow)
    }

    @Test
    fun overlappingLayersAreAllReported() {
        val arc = BusyBlockArc(-5f, 10f, 0, listOf("Standup"), "Work")
        val focus = FocusArc(-10f, 20f)
        val episodes = listOf(DetourEpisode(ms(5L * 3_600_000), ms(7L * 3_600_000), "Slack"))
        val bodies = detourBodies(windowStart, windowEnd, episodes) // midpoint angle ~0°
        val r = ringReadoutAt(
            0f,
            windowStart,
            windowEnd,
            busyBlockArcs = listOf(arc),
            detourBodies = bodies,
            focusArcs = listOf(focus),
            momentAngleDegrees = 0f,
        )
        assertEquals(arc, r.busy)
        assertEquals(bodies.first(), r.detour)
        assertTrue(r.focus)
        assertTrue(r.isNow)
    }

    @Test
    fun normalizeRingAngleFoldsIntoDrawArcDomain() {
        assertEquals(225f, normalizeRingAngle(-135f), 0.001f)
        assertEquals(0f, normalizeRingAngle(0f), 0.001f)
        assertEquals(180f, normalizeRingAngle(180f), 0.001f)
        assertEquals(-90f, normalizeRingAngle(270f), 0.001f)
    }

    @Test
    fun topLeftQuadrantAngleMapsToLateInTheDay() {
        // A raw atan2 result of -135° (top-left quadrant) must normalize to 225°, which is
        // fraction 0.875 of the 24h window -> 21:00, not windowStart as the un-normalized
        // angle would incorrectly coerce to.
        val r = ringReadoutAt(
            angleDegrees = normalizeRingAngle(-135f),
            windowStart = windowStart,
            windowEnd = windowEnd,
            busyBlockArcs = emptyList(),
            detourBodies = emptyList(),
            focusArcs = emptyList(),
            momentAngleDegrees = null,
        )
        assertEquals(ms(21L * 3_600_000), r.time)
    }
}
