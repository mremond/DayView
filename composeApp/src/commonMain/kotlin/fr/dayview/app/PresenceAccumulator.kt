package fr.dayview.app

/** A stretch of continuous on-goal presence (local-day relative), for the ring arcs. */
data class FocusPresenceInterval(val startMillis: Long, val endMillis: Long)

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
