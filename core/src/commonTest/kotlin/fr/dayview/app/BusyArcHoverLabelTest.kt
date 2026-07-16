package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

class BusyArcHoverLabelTest {
    private fun arcWith(titles: List<String>, calendarName: String) = BusyBlockArc(
        startAngleDegrees = 0f,
        sweepDegrees = 10f,
        colorIndex = 0,
        titles = titles,
        calendarName = calendarName,
        start = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        end = Instant.fromEpochMilliseconds(1_699_959_600_000L),
    )

    @Test
    fun joinsNonBlankTitles() {
        assertTrue(busyArcHoverLabel(arcWith(listOf("", "A", "B"), "Cal"), true).startsWith("A, B · "))
    }

    @Test
    fun fallsBackToBusyWhenNameless() {
        assertTrue(busyArcHoverLabel(arcWith(emptyList(), ""), true).startsWith("Busy · "))
    }
}
