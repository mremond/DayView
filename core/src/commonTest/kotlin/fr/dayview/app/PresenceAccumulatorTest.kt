package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PresenceAccumulatorTest {
    private val day = 20_000L
    private fun ms(v: Long): Instant = Instant.fromEpochMilliseconds(v)
    private fun on(a: PresenceAccumulator, t: Long) = a.observe(ms(t), OnGoalState.ON_GOAL, day)
    private fun off(a: PresenceAccumulator, t: Long) = a.observe(ms(t), OnGoalState.OFF_GOAL, day)
    private fun neutral(a: PresenceAccumulator, t: Long) = a.observe(ms(t), OnGoalState.NEUTRAL, day)

    @Test
    fun aContinuousOnGoalRunBecomesOneInterval() {
        val a = PresenceAccumulator()
        on(a, 0L)
        val result = on(a, 130_000L) // 2m10s on-goal, exceeds 2m minimum
        assertEquals(listOf(FocusPresenceInterval(ms(0L), ms(130_000L))), result)
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
        assertEquals(listOf(FocusPresenceInterval(ms(0L), ms(150_000L))), result)
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
                FocusPresenceInterval(ms(0L), ms(130_000L)),
                FocusPresenceInterval(ms(200_000L), ms(400_000L)),
            ),
            result,
        )
    }

    @Test
    fun dayRolloverClearsAccumulatedIntervals() {
        val a = PresenceAccumulator()
        on(a, 0L)
        on(a, 130_000L)
        val next = a.observe(ms(500_000L), OnGoalState.ON_GOAL, day + 1) // new day
        assertEquals(emptyList(), next)
    }

    @Test
    fun restoreSeedsClosedIntervalsForTheDay() {
        val a = PresenceAccumulator()
        a.restore(listOf(FocusPresenceInterval(ms(0L), ms(130_000L))), day)
        val result = neutral(a, 200_000L)
        assertEquals(listOf(FocusPresenceInterval(ms(0L), ms(130_000L))), result)
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
                FocusPresenceInterval(ms(0L), ms(130_000L)),
                FocusPresenceInterval(ms(3_730_000L), ms(3_860_000L)),
            ),
            result,
        )
    }

    private fun lenient() = PresenceAccumulator(
        presentStates = setOf(OnGoalState.ON_GOAL, OnGoalState.NEUTRAL),
        bridge = 120.seconds,
        minInterval = 60.seconds,
        interruptionGap = 15.seconds,
    )

    // Feed ticks from [from]..[to] inclusive at [step] cadence (models continuous 1s observation).
    private fun run(a: PresenceAccumulator, from: Long, to: Long, step: Long, state: OnGoalState): List<FocusPresenceInterval> {
        var result = emptyList<FocusPresenceInterval>()
        var t = from
        while (t <= to) {
            result = a.observe(ms(t), state, day)
            t += step
        }
        return result
    }

    @Test
    fun lenientCountsNeutralTime() {
        val a = lenient()
        val result = run(a, 0L, 90_000L, 10_000L, OnGoalState.NEUTRAL)
        assertEquals(listOf(FocusPresenceInterval(ms(0L), ms(90_000L))), result)
    }

    @Test
    fun lenientCountsBriefOffGoalUnderBridge() {
        val a = lenient()
        run(a, 0L, 60_000L, 10_000L, OnGoalState.ON_GOAL) // lastPresent = 60000
        run(a, 70_000L, 160_000L, 10_000L, OnGoalState.OFF_GOAL) // 100s off-goal, never reaches 120s bridge
        val result = run(a, 170_000L, 170_000L, 10_000L, OnGoalState.ON_GOAL) // back on-goal, blip absorbed
        assertEquals(listOf(FocusPresenceInterval(ms(0L), ms(170_000L))), result)
    }

    @Test
    fun lenientDropsSustainedOffGoalDrift() {
        val a = lenient()
        run(a, 0L, 60_000L, 10_000L, OnGoalState.ON_GOAL) // lastPresent = 60000
        val result = run(a, 70_000L, 200_000L, 10_000L, OnGoalState.OFF_GOAL) // reaches 120s bridge at t=180000
        assertEquals(listOf(FocusPresenceInterval(ms(0L), ms(60_000L))), result)
    }

    @Test
    fun lenientExcludesUnobservedInterruption() {
        val a = lenient()
        run(a, 0L, 60_000L, 10_000L, OnGoalState.ON_GOAL) // interval 1: [0, 60000]
        // 40s tick gap (>= 15s interruptionGap) closes interval 1; a fresh interval starts on resume
        val result = run(a, 100_000L, 170_000L, 10_000L, OnGoalState.ON_GOAL)
        assertEquals(
            listOf(
                FocusPresenceInterval(ms(0L), ms(60_000L)),
                FocusPresenceInterval(ms(100_000L), ms(170_000L)),
            ),
            result,
        )
    }

    @Test
    fun lenientDiscardsRunBelowMinInterval() {
        val a = lenient()
        run(a, 0L, 40_000L, 10_000L, OnGoalState.ON_GOAL) // 40s run, below 60s minInterval
        // 160s tick gap (>= 15s) closes the run; 40s < 60s so it is discarded
        val result = a.observe(ms(200_000L), OnGoalState.ON_GOAL, day)
        assertEquals(emptyList(), result)
    }
}
