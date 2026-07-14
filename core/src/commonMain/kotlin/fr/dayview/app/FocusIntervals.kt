package fr.dayview.app

/**
 * Coalesce a list of focus intervals into a sorted, disjoint set: overlapping or
 * touching runs are merged. Required before summing durations, since [focusedTime]
 * adds intervals without de-overlapping — unioning two devices' (or two sessions')
 * intervals otherwise double-counts.
 */
fun mergeIntervals(intervals: List<FocusPresenceInterval>): List<FocusPresenceInterval> {
    if (intervals.isEmpty()) return emptyList()
    val sorted = intervals.sortedBy { it.start }
    val merged = mutableListOf(sorted.first())
    for (next in sorted.drop(1)) {
        val last = merged.last()
        if (next.start <= last.end) {
            if (next.end > last.end) merged[merged.lastIndex] = last.copy(end = next.end)
        } else {
            merged.add(next)
        }
    }
    return merged
}
