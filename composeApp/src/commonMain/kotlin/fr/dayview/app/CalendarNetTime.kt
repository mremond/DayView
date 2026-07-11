package fr.dayview.app

data class BusyInterval(
    val startMillis: Long,
    val endMillis: Long,
    val titles: List<String> = emptyList(),
)

fun mergeBusyIntervals(intervals: List<BusyInterval>): List<BusyInterval> {
    val sorted = intervals.filter { it.endMillis > it.startMillis }.sortedBy { it.startMillis }
    val merged = mutableListOf<BusyInterval>()
    for (interval in sorted) {
        val last = merged.lastOrNull()
        if (last != null && interval.startMillis <= last.endMillis) {
            merged[merged.lastIndex] = last.copy(
                endMillis = maxOf(last.endMillis, interval.endMillis),
                titles = last.titles + interval.titles,
            )
        } else {
            merged.add(interval)
        }
    }
    return merged
}
