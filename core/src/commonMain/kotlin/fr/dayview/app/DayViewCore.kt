package fr.dayview.app

import kotlin.time.Instant

/**
 * Swift-facing snapshot of [DayProgress] using primitives only, so the value
 * bridges cleanly to Objective-C/Swift (no Kotlin value classes or Duration).
 */
data class DayProgressSnapshot(
    val remainingSeconds: Long,
    val remainingRatio: Double,
    val momentAngleDegrees: Double,
    val isFinished: Boolean,
    val remainingHours: Long,
    val remainingMinutes: Long,
)

/**
 * Stable entry point for native (macOS/Swift) callers. Takes epoch milliseconds
 * instead of [Instant] and returns a flattened [DayProgressSnapshot].
 */
object DayViewCore {
    fun dayProgress(
        nowEpochMillis: Long,
        startMinutes: Int,
        endMinutes: Int,
    ): DayProgressSnapshot {
        val progress = calculateDayProgress(
            now = Instant.fromEpochMilliseconds(nowEpochMillis),
            startMinutesOfDay = startMinutes,
            endMinutesOfDay = endMinutes,
        )
        return DayProgressSnapshot(
            remainingSeconds = progress.remaining.inWholeSeconds,
            remainingRatio = progress.remainingRatio.toDouble(),
            momentAngleDegrees = currentMomentAngleDegrees(progress.remainingRatio).toDouble(),
            isFinished = progress.isFinished,
            remainingHours = progress.remainingHours,
            remainingMinutes = progress.remainingMinutes,
        )
    }
}
