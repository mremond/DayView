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
 * Stateless day-progress entry point retained from the walking skeleton: takes epoch
 * milliseconds instead of [Instant] and returns a flattened [DayProgressSnapshot].
 *
 * The live native UI does NOT use this — it observes [TodaySnapshot] via `DayViewSession`,
 * which recomputes the same day-progress fields from the controller's reactive state. This
 * facade remains as the minimal, dependency-free proof of the Kotlin↔Swift bridge (exercised
 * by the Swift `smoke` target); [TodaySnapshot] is authoritative for the running app.
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
