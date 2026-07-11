package fr.dayview.app

/**
 * Computes the pomodoro end time for a focus session started at [nowMillis]
 * with the given [durationMinutes], coercing the duration into the valid
 * 5..180 minute range (matching calculatePomodoroProgress).
 */
fun focusStartEndMillis(nowMillis: Long, durationMinutes: Int): Long = nowMillis + durationMinutes.coerceIn(5, 180) * 60_000L
