package fr.dayview.app

import kotlin.time.Instant

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

/**
 * Engaged intervals for one Pomodoro session: the window [sessionStart, effectiveEnd]
 * with declared detours carved out. `effectiveEnd` is the caller's `min(stopInstant,
 * pomodoroEnd)`, so early stops end early and overtime is already capped. Returns 0..N
 * disjoint pieces (a detour in the middle splits the window).
 */
fun deriveEngagedIntervals(
    sessionStart: Instant,
    effectiveEnd: Instant,
    detours: List<DetourEpisode>,
): List<FocusPresenceInterval> {
    if (effectiveEnd <= sessionStart) return emptyList()
    // Detour spans clipped to the window, coalesced, walked left-to-right.
    val cuts = mergeIntervals(
        detours.mapNotNull {
            val s = maxOf(it.start, sessionStart)
            val e = minOf(it.end, effectiveEnd)
            if (e > s) FocusPresenceInterval(s, e) else null
        },
    )
    val pieces = mutableListOf<FocusPresenceInterval>()
    var cursor = sessionStart
    for (cut in cuts) {
        if (cut.start > cursor) pieces.add(FocusPresenceInterval(cursor, cut.start))
        cursor = maxOf(cursor, cut.end)
    }
    if (cursor < effectiveEnd) pieces.add(FocusPresenceInterval(cursor, effectiveEnd))
    return pieces
}
