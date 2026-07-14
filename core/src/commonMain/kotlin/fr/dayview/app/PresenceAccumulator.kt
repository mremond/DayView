package fr.dayview.app

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A stretch of continuous on-goal presence, for the ring arcs. The instants are absolute
 * (projected against the absolute day window), not local-day relative.
 */
data class FocusPresenceInterval(val start: Instant, val end: Instant)

/** Serialize intervals to one `start,end` line each (epoch millis), for preference storage. */
fun encodeFocusPresence(intervals: List<FocusPresenceInterval>): String = intervals.joinToString("\n") { "${it.start.toEpochMilliseconds()},${it.end.toEpochMilliseconds()}" }

/** Inverse of [encodeFocusPresence]; skips blank / malformed lines. */
fun decodeFocusPresence(encoded: String): List<FocusPresenceInterval> = encoded.split("\n").mapNotNull { line ->
    val parts = line.split(",")
    val s = parts.getOrNull(0)?.toLongOrNull()
    val e = parts.getOrNull(1)?.toLongOrNull()
    if (parts.size == 2 && s != null && e != null) {
        FocusPresenceInterval(Instant.fromEpochMilliseconds(s), Instant.fromEpochMilliseconds(e))
    } else {
        null
    }
}

/**
 * Builds today's presence intervals from the per-tick state classification. Ticks whose
 * state is in [presentStates] extend the open interval; gaps where the state falls outside
 * [presentStates] are tolerated up to [bridge], a longer such gap closes the interval; an
 * unobserved gap between ticks (machine asleep / app backgrounded) of at least
 * [interruptionGap], if set, also closes the interval regardless of state. Runs below
 * [minInterval] are discarded when closed.
 */
class PresenceAccumulator(
    private val presentStates: Set<OnGoalState> = setOf(OnGoalState.ON_GOAL),
    private val bridge: Duration = 30.seconds,
    private val minInterval: Duration = 120.seconds,
    private val interruptionGap: Duration? = null,
) {
    private val closed = mutableListOf<FocusPresenceInterval>()
    private var openStart: Instant? = null
    private var lastPresent: Instant? = null
    private var lastObserved: Instant? = null
    private var currentDayKey: Long = Long.MIN_VALUE

    /** Seed closed intervals for [dayKey] at startup (persisted state). */
    fun restore(
        intervals: List<FocusPresenceInterval>,
        dayKey: Long,
    ) {
        currentDayKey = dayKey
        closed.clear()
        closed.addAll(intervals)
        openStart = null
        lastPresent = null
        lastObserved = null
    }

    /**
     * Finalize the open run at a focus-session boundary: commit it if it meets the
     * minimum, then clear it so the next session starts a fresh interval rather than
     * bridging across the inactive gap. Returns the current closed intervals.
     */
    fun endSession(): List<FocusPresenceInterval> {
        closeOpenRun()
        return closed.toList()
    }

    fun observe(
        now: Instant,
        state: OnGoalState,
        dayKey: Long,
    ): List<FocusPresenceInterval> {
        if (dayKey != currentDayKey) {
            currentDayKey = dayKey
            closed.clear()
            openStart = null
            lastPresent = null
            lastObserved = null
        }
        // An unobserved gap between ticks (machine asleep / app backgrounded) never counts:
        // close the open run at the last observed present tick before continuing.
        val previousObserved = lastObserved
        if (interruptionGap != null && previousObserved != null && now - previousObserved >= interruptionGap) {
            closeOpenRun()
        }
        if (state in presentStates) {
            if (openStart == null) openStart = now
            lastPresent = now
        } else {
            val start = openStart
            val last = lastPresent
            if (start != null && last != null && now - last >= bridge) {
                closeOpenRun()
            }
        }
        lastObserved = now
        return snapshotIntervals()
    }

    /** Commit `[openStart, lastPresent]` iff it meets [minInterval], then clear the open run. */
    private fun closeOpenRun() {
        val start = openStart
        val last = lastPresent
        if (start != null && last != null && last - start >= minInterval) {
            closed.add(FocusPresenceInterval(start, last))
        }
        openStart = null
    }

    private fun snapshotIntervals(): List<FocusPresenceInterval> {
        val start = openStart
        val last = lastPresent
        if (start != null && last != null && last - start >= minInterval) {
            return closed + FocusPresenceInterval(start, last)
        }
        return closed.toList()
    }
}
