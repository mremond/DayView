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
 * Builds today's intense-focus intervals from the per-tick on-goal classification.
 * On-goal ticks extend the open interval; off-goal/neutral gaps shorter than [bridge]
 * are tolerated; a longer gap closes it; intervals below [minInterval] are discarded.
 */
class PresenceAccumulator(
    private val bridge: Duration = 30.seconds,
    private val minInterval: Duration = 120.seconds,
) {
    private val closed = mutableListOf<FocusPresenceInterval>()
    private var openStart: Instant? = null
    private var lastOnGoal: Instant? = null
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
    }

    /**
     * Finalize the open run at a focus-session boundary: commit it if it meets the
     * minimum, then clear it so the next session starts a fresh interval rather than
     * bridging across the inactive gap. Returns the current closed intervals.
     */
    fun endSession(): List<FocusPresenceInterval> {
        val start = openStart
        val last = lastOnGoal
        if (start != null && last != null) {
            if (last - start >= minInterval) {
                closed.add(FocusPresenceInterval(start, last))
            }
            openStart = null
        }
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
            lastOnGoal = null
        }
        when (state) {
            OnGoalState.ON_GOAL -> {
                if (openStart == null) openStart = now
                lastOnGoal = now
            }
            OnGoalState.OFF_GOAL, OnGoalState.NEUTRAL -> {
                val start = openStart
                val last = lastOnGoal
                if (start != null && last != null && now - last >= bridge) {
                    if (last - start >= minInterval) {
                        closed.add(FocusPresenceInterval(start, last))
                    }
                    openStart = null
                }
            }
        }
        return snapshotIntervals()
    }

    private fun snapshotIntervals(): List<FocusPresenceInterval> {
        val start = openStart
        val last = lastOnGoal
        if (start != null && last != null && last - start >= minInterval) {
            return closed + FocusPresenceInterval(start, last)
        }
        return closed.toList()
    }
}
