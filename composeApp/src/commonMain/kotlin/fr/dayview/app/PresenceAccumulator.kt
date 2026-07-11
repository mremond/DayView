package fr.dayview.app

/**
 * A stretch of continuous on-goal presence, for the ring arcs. The millis are absolute
 * epoch millis (projected against the absolute day window), not local-day relative.
 */
data class FocusPresenceInterval(val startMillis: Long, val endMillis: Long)

/** Serialize intervals to one `start,end` line each, for preference storage. */
fun encodeFocusPresence(intervals: List<FocusPresenceInterval>): String = intervals.joinToString("\n") { "${it.startMillis},${it.endMillis}" }

/** Inverse of [encodeFocusPresence]; skips blank / malformed lines. */
fun decodeFocusPresence(encoded: String): List<FocusPresenceInterval> = encoded.split("\n").mapNotNull { line ->
    val parts = line.split(",")
    val s = parts.getOrNull(0)?.toLongOrNull()
    val e = parts.getOrNull(1)?.toLongOrNull()
    if (parts.size == 2 && s != null && e != null) FocusPresenceInterval(s, e) else null
}

/**
 * Builds today's intense-focus intervals from the per-tick on-goal classification.
 * On-goal ticks extend the open interval; off-goal/neutral gaps shorter than [bridgeMillis]
 * are tolerated; a longer gap closes it; intervals below [minIntervalMillis] are discarded.
 */
class PresenceAccumulator(
    private val bridgeMillis: Long = 30_000L,
    private val minIntervalMillis: Long = 120_000L,
) {
    private val closed = mutableListOf<FocusPresenceInterval>()
    private var openStart: Long? = null
    private var lastOnGoalMillis: Long = 0L
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
        if (start != null) {
            if (lastOnGoalMillis - start >= minIntervalMillis) {
                closed.add(FocusPresenceInterval(start, lastOnGoalMillis))
            }
            openStart = null
        }
        return closed.toList()
    }

    fun observe(
        nowMillis: Long,
        state: OnGoalState,
        dayKey: Long,
    ): List<FocusPresenceInterval> {
        if (dayKey != currentDayKey) {
            currentDayKey = dayKey
            closed.clear()
            openStart = null
            lastOnGoalMillis = 0L
        }
        when (state) {
            OnGoalState.ON_GOAL -> {
                if (openStart == null) openStart = nowMillis
                lastOnGoalMillis = nowMillis
            }
            OnGoalState.OFF_GOAL, OnGoalState.NEUTRAL -> {
                val start = openStart
                if (start != null && nowMillis - lastOnGoalMillis >= bridgeMillis) {
                    if (lastOnGoalMillis - start >= minIntervalMillis) {
                        closed.add(FocusPresenceInterval(start, lastOnGoalMillis))
                    }
                    openStart = null
                }
            }
        }
        return snapshotIntervals()
    }

    private fun snapshotIntervals(): List<FocusPresenceInterval> {
        val start = openStart
        if (start != null && lastOnGoalMillis - start >= minIntervalMillis) {
            return closed + FocusPresenceInterval(start, lastOnGoalMillis)
        }
        return closed.toList()
    }
}
