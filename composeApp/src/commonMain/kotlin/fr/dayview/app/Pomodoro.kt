package fr.dayview.app

enum class PomodoroStatus { IDLE, ACTIVE, FINISHED }

data class PomodoroProgress(
    val durationMinutes: Int,
    val remainingMillis: Long,
    val remainingRatio: Float,
    val status: PomodoroStatus,
) {
    val remainingMinutes: Long get() = remainingMillis / 60_000
    val remainingSeconds: Long get() = (remainingMillis / 1_000) % 60
}

fun calculatePomodoroProgress(
    nowMillis: Long,
    durationMinutes: Int,
    endMillis: Long?,
): PomodoroProgress {
    val safeDuration = durationMinutes.coerceIn(5, 180)
    val durationMillis = safeDuration * 60_000L
    if (endMillis == null) {
        return PomodoroProgress(safeDuration, durationMillis, 1f, PomodoroStatus.IDLE)
    }

    val remaining = (endMillis - nowMillis).coerceIn(0, durationMillis)
    return PomodoroProgress(
        durationMinutes = safeDuration,
        remainingMillis = remaining,
        remainingRatio = remaining.toFloat() / durationMillis,
        status = if (remaining == 0L) PomodoroStatus.FINISHED else PomodoroStatus.ACTIVE,
    )
}

fun formatPomodoroClock(progress: PomodoroProgress): String =
    "${progress.remainingMinutes.toString().padStart(2, '0')}:" +
        progress.remainingSeconds.toString().padStart(2, '0')

fun formatPomodoroCompactMinutes(progress: PomodoroProgress): String {
    val roundedMinutes = (progress.remainingMillis + 59_999L) / 60_000L
    return "${roundedMinutes}m"
}
