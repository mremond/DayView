package fr.dayview.app

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

private fun arc(name: String, colorIndex: Int = 0): BusyBlockArc = BusyBlockArc(
    startAngleDegrees = 0f,
    sweepDegrees = 30f,
    colorIndex = colorIndex,
    titles = listOf(name),
    calendarName = name,
    start = Instant.fromEpochMilliseconds(0L),
    end = Instant.fromEpochMilliseconds(3_600_000L),
)

class BlockedZoneTapTest {
    @Test
    fun tappingEmptyRingClosesTheTooltip() {
        val current = HoveredBusyArc(arc("Work"), Offset(10f, 10f))
        assertNull(nextHoveredBusyOnTap(current, tapped = null, position = Offset(5f, 5f)))
    }

    @Test
    fun tappingTheShownZoneClosesTheTooltip() {
        val work = arc("Work")
        val current = HoveredBusyArc(work, Offset(10f, 10f))
        assertNull(nextHoveredBusyOnTap(current, tapped = work, position = Offset(12f, 12f)))
    }

    @Test
    fun tappingAnotherZoneSwitchesTheTooltip() {
        val work = arc("Work", colorIndex = 0)
        val gym = arc("Gym", colorIndex = 1)
        val current = HoveredBusyArc(work, Offset(10f, 10f))
        val next = nextHoveredBusyOnTap(current, tapped = gym, position = Offset(40f, 40f))
        assertEquals(HoveredBusyArc(gym, Offset(40f, 40f)), next)
    }

    @Test
    fun tappingAZoneWithNothingShownOpensTheTooltip() {
        val gym = arc("Gym")
        val next = nextHoveredBusyOnTap(current = null, tapped = gym, position = Offset(40f, 40f))
        assertEquals(HoveredBusyArc(gym, Offset(40f, 40f)), next)
    }
}
