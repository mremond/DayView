package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class FocusIntervalsTest {
    private fun at(sec: Long) = Instant.fromEpochMilliseconds(sec * 1000)
    private fun iv(s: Long, e: Long) = FocusPresenceInterval(at(s), at(e))

    @Test
    fun mergeIntervalsCoalescesOverlappingAndAdjacentRuns() {
        val merged = mergeIntervals(
            listOf(iv(30, 40), iv(0, 10), iv(8, 15), iv(15, 20)),
        )
        // 0-10 and 8-15 overlap -> 0-15; 15-20 is adjacent -> 0-20; 30-40 stays separate.
        assertEquals(listOf(iv(0, 20), iv(30, 40)), merged)
    }

    @Test
    fun mergeIntervalsIsIdempotent() {
        val once = mergeIntervals(listOf(iv(0, 10), iv(5, 20)))
        assertEquals(once, mergeIntervals(once))
    }

    @Test
    fun mergeIntervalsOnEmptyIsEmpty() {
        assertEquals(emptyList(), mergeIntervals(emptyList()))
    }

    private fun detour(s: Long, e: Long) = DetourEpisode(at(s), at(e), category = "x")

    @Test
    fun deriveEngagedWithNoDetoursIsTheWholeWindow() {
        assertEquals(listOf(iv(100, 1600)), deriveEngagedIntervals(at(100), at(1600), emptyList()))
    }

    @Test
    fun deriveEngagedSubtractsAnInteriorDetourIntoTwoPieces() {
        val pieces = deriveEngagedIntervals(at(0), at(1000), listOf(detour(400, 600)))
        assertEquals(listOf(iv(0, 400), iv(600, 1000)), pieces)
    }

    @Test
    fun deriveEngagedClampsDetoursToTheWindowEdges() {
        val pieces = deriveEngagedIntervals(at(100), at(1000), listOf(detour(0, 300), detour(900, 5000)))
        assertEquals(listOf(iv(300, 900)), pieces)
    }

    @Test
    fun deriveEngagedIsEmptyWhenWindowInvertedOrFullyCovered() {
        assertEquals(emptyList(), deriveEngagedIntervals(at(500), at(100), emptyList()))
        assertEquals(emptyList(), deriveEngagedIntervals(at(0), at(1000), listOf(detour(0, 1000))))
    }
}
