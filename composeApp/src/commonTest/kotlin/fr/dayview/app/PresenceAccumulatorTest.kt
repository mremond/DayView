package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PresenceAccumulatorTest {
    private val day = 20_000L
    private fun on(a: PresenceAccumulator, t: Long) = a.observe(t, OnGoalState.ON_GOAL, day)
    private fun off(a: PresenceAccumulator, t: Long) = a.observe(t, OnGoalState.OFF_GOAL, day)
    private fun neutral(a: PresenceAccumulator, t: Long) = a.observe(t, OnGoalState.NEUTRAL, day)

    @Test
    fun aContinuousOnGoalRunBecomesOneInterval() {
        val a = PresenceAccumulator()
        on(a, 0L)
        val result = on(a, 130_000L) // 2m10s on-goal, exceeds 2m minimum
        assertEquals(listOf(FocusPresenceInterval(0L, 130_000L)), result)
    }

    @Test
    fun shortOnGoalDipIsDiscardedBelowMinimum() {
        val a = PresenceAccumulator()
        on(a, 0L)
        on(a, 60_000L) // only 1 min on-goal
        val result = off(a, 120_000L) // gap ≥ bridge closes it → discarded
        assertEquals(emptyList(), result)
    }

    @Test
    fun briefBlipUnderBridgeDoesNotSplitTheInterval() {
        val a = PresenceAccumulator()
        on(a, 0L)
        on(a, 120_000L) // on-goal through 2 min (lastOnGoal = 120s)
        neutral(a, 140_000L) // 20s blip (< 30s bridge), interval still open
        val result = on(a, 150_000L) // back on-goal, one continuous interval
        assertEquals(listOf(FocusPresenceInterval(0L, 150_000L)), result)
    }

    @Test
    fun gapAtOrAboveBridgeClosesThenANewIntervalStarts() {
        val a = PresenceAccumulator()
        on(a, 0L)
        on(a, 130_000L) // interval 1: [0, 130000]
        off(a, 170_000L) // 40s gap ≥ bridge → closes interval 1
        on(a, 200_000L)
        on(a, 400_000L) // interval 2 grows
        val result = on(a, 400_000L)
        assertEquals(
            listOf(
                FocusPresenceInterval(0L, 130_000L),
                FocusPresenceInterval(200_000L, 400_000L),
            ),
            result,
        )
    }

    @Test
    fun dayRolloverClearsAccumulatedIntervals() {
        val a = PresenceAccumulator()
        on(a, 0L)
        on(a, 130_000L)
        val next = a.observe(500_000L, OnGoalState.ON_GOAL, day + 1) // new day
        assertEquals(emptyList(), next)
    }

    @Test
    fun restoreSeedsClosedIntervalsForTheDay() {
        val a = PresenceAccumulator()
        a.restore(listOf(FocusPresenceInterval(0L, 130_000L)), day)
        val result = neutral(a, 200_000L)
        assertEquals(listOf(FocusPresenceInterval(0L, 130_000L)), result)
    }

    @Test
    fun endSessionClosesTheRunSoTheNextSessionStartsFresh() {
        val a = PresenceAccumulator()
        on(a, 0L)
        on(a, 130_000L) // session 1: [0, 130000]
        a.endSession() // focus ends
        on(a, 3_730_000L) // session 2 starts an hour later
        val result = on(a, 3_860_000L) // session 2 run to +130s
        assertEquals(
            listOf(
                FocusPresenceInterval(0L, 130_000L),
                FocusPresenceInterval(3_730_000L, 3_860_000L),
            ),
            result,
        )
    }
}
